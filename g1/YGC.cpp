 // YGC并行处理
 void work(uint worker_id) {

      // 处理root
      _root_processor->evacuate_roots(strong_root_cl,
                                      weak_root_cl,
                                      strong_cld_cl,
                                      weak_cld_cl,
                                      trace_metadata,
                                      worker_id);
      // 处理DCQS中的dcq，以及把RSet作为根处理
      _root_processor->scan_remembered_sets(&push_heap_rs_cl,
                                            weak_root_cl,
                                            worker_id);
      {
        // 开始复制
        G1ParEvacuateFollowersClosure evac(_g1h, &pss, _queues, &_terminator);
        evac.do_void();
      }
};

// 处理root
void G1RootProcessor::evacuate_roots(OopClosure* scan_non_heap_roots,
                                     OopClosure* scan_non_heap_weak_roots,
                                     CLDClosure* scan_strong_clds,
                                     CLDClosure* scan_weak_clds,
                                     bool trace_metadata,
                                     uint worker_i) {
  // First scan the shared roots.
  // 这里使用的 BufferingOopClosure 主要是为了缓存对象，然后一次性处理，大小为1024
  BufferingOopClosure buf_scan_non_heap_roots(scan_non_heap_roots);
  BufferingOopClosure buf_scan_non_heap_weak_roots(scan_non_heap_weak_roots);

  OopClosure* const weak_roots = &buf_scan_non_heap_weak_roots;
  OopClosure* const strong_roots = &buf_scan_non_heap_roots;

  // CodeBlobClosures are not interoperable with BufferingOopClosures
  G1CodeBlobClosure root_code_blobs(scan_non_heap_roots);
  // 处理java roots，主要指类加载器和线程栈
  process_java_roots(strong_roots,
                     trace_metadata ? scan_strong_clds : NULL,
                     scan_strong_clds,
                     trace_metadata ? NULL : scan_weak_clds,
                     &root_code_blobs,
                     phase_times,
                     worker_i);

  // This is the point where this worker thread will not find more strong CLDs/nmethods.
  // Report this so G1 can synchronize the strong and weak CLDs/nmethods processing.
  if (trace_metadata) {
    worker_has_discovered_all_strong_classes();
  }

  // 处理jvm roots，通常是JVM的全局对象
  process_vm_roots(strong_roots, weak_roots, phase_times, worker_i);

  {
    // Now the CM ref_processor roots.
    // 处理引用发现
    G1GCParPhaseTimesTracker x(phase_times, G1GCPhaseTimes::CMRefRoots, worker_i);
    if (!_process_strong_tasks->is_task_claimed(G1RP_PS_refProcessor_oops_do)) {
      // We need to treat the discovered reference lists of the
      // concurrent mark ref processor as roots and keep entries
      // (which are added by the marking threads) on them live
      // until they can be processed at the end of marking.
      _g1h->ref_processor_cm()->weak_oops_do(&buf_scan_non_heap_roots);
    }
  }

  // During conc marking we have to filter the per-thread SATB buffers
  // to make sure we remove any oops into the CSet (which will show up
  // as implicitly live).
  {
    // 在mixed gc 的时候，把并发标记总已经失效的引用关系移除，YGC不会走到这里
    G1GCParPhaseTimesTracker x(phase_times, G1GCPhaseTimes::SATBFiltering, worker_i);
    if (!_process_strong_tasks->is_task_claimed(G1RP_PS_filter_satb_buffers) && _g1h->mark_in_progress()) {
      JavaThread::satb_mark_queue_set().filter_thread_buffers();
    }
  }
  // 等待所有的任务结束
  _process_strong_tasks->all_tasks_completed();
}

void JavaThread::oops_do(OopClosure* f, CLDClosure* cld_f, CodeBlobClosure* cf) {

  // The ThreadProfiler oops_do is done from FlatProfiler::oops_do
  // since there may be more than one thread using each ThreadProfiler.

  // Traverse the GCHandles
  // 处理JNI本地代码栈和JVM内部本地方法栈
  Thread::oops_do(f, cld_f, cf);

  if (has_last_Java_frame()) {
    // Record JavaThread to GC thread
    RememberProcessedThread rpt(this);

    // Traverse the privileged stack
    // privileged_stack用于实现java安全功能的类
    if (_privileged_stack_top != NULL) {
      _privileged_stack_top->oops_do(f);
    }

    // traverse the registered growable array
    if (_array_for_gc != NULL) {
      for (int index = 0; index < _array_for_gc->length(); index++) {
        f->do_oop(_array_for_gc->adr_at(index));
      }
    }

    // Traverse the monitor chunks
    // 遍历Monitor块
    for (MonitorChunk* chunk = monitor_chunks(); chunk != NULL; chunk = chunk->next()) {
      chunk->oops_do(f);
    }

    // Traverse the execution stack
    // 遍历栈
    for(StackFrameStream fst(this); !fst.is_done(); fst.next()) {
      fst.current()->oops_do(f, cld_f, cf, fst.register_map());
    }
  }

  // If we have deferred set_locals there might be oops waiting to be
  // written
  // 遍历jvmti
  GrowableArray<jvmtiDeferredLocalVariableSet*>* list = deferred_locals();
  if (list != NULL) {
    for (int i = 0; i < list->length(); i++) {
      list->at(i)->oops_do(f);
    }
  }

  // Traverse instance variables at the end since the GC may be moving things
  // around using this function
  // 遍历这些实例对象，这些对象可能引用了堆对象
  f->do_oop((oop*) &_threadObj);
  f->do_oop((oop*) &_vm_result);
  f->do_oop((oop*) &_exception_oop);
  f->do_oop((oop*) &_pending_async_exception);

  if (jvmti_thread_state() != NULL) {
    jvmti_thread_state()->oops_do(f);
  }
}

// 把对象复制到新的分区：survivor或old分区
void G1ParCopyClosure<barrier, do_mark_object>::do_oop_work(T* p) {
  // 对象头信息
  T heap_oop = oopDesc::load_heap_oop(p);

  if (oopDesc::is_null(heap_oop)) {
    return;
  }

  oop obj = oopDesc::decode_heap_oop_not_null(heap_oop);

  assert(_worker_id == _par_scan_state->queue_num(), "sanity");

  const InCSetState state = _g1->in_cset_state(obj);
  if (state.is_in_cset()) {
    oop forwardee;
    markOop m = obj->mark();
    // 对象是否已经复制完成
    if (m->is_marked()) {
      // 如果完成，直接找到新的对象
      forwardee = (oop) m->decode_pointer();
    } else {
      // 如果对象还没有复制，则复制对象
      forwardee = _par_scan_state->copy_to_survivor_space(state, obj, m);
    }
    assert(forwardee != NULL, "forwardee should not be NULL");
    oopDesc::encode_store_heap_oop(p, forwardee);
    if (do_mark_object != G1MarkNone && forwardee != obj) {
      // If the object is self-forwarded we don't need to explicitly
      // mark it, the evacuation failure protocol will do so.
      // 如果对象成功复制，把新对象的地址设置到老对象的对象头
      mark_forwarded_object(obj, forwardee);
    }

    if (barrier == G1BarrierKlass) {
      do_klass_barrier(p, forwardee);
    }
  } else {
    // 对于不在CSet的对象，先把对象暴击为活的，在并发标记的时候作为根对象
    if (state.is_humongous()) {
      _g1->set_humongous_is_live(obj);
    }
    // The object is not in collection set. If we're a root scanning
    // closure during an initial mark pause then attempt to mark the object.
    if (do_mark_object == G1MarkFromRoot) {
      mark_object(obj);
    }
  }

  if (barrier == G1BarrierEvac) {
    // 如果evac失败，需要将对象记录到一个特殊的队列，在最后Redirty时需要重构RSet
    _par_scan_state->update_rs(_from, p, _worker_id);
  }
}

// 对象复制
oop G1ParScanThreadState::copy_to_survivor_space(InCSetState const state,
                                                 oop const old,
                                                 markOop const old_mark) {

  uint age = 0;
  // 判断对象下一步是要到survivor还是old，判断的依据是对象的age
  // 当对象的age超过晋升阈值或者survivor不能存放就放到old
  InCSetState dest_state = next_state(state, old_mark, age);
  // 使用PLAB方法直接在PLAB中分配新对象

  HeapWord* obj_ptr = _g1_par_allocator->plab_allocate(dest_state, word_sz, context);

  // PLAB allocations should succeed most of the time, so we'll
  // normally check against NULL once and that's it.
  if (obj_ptr == NULL) {
    // 如果分配失败，尝试分配一个PLAB或者直接在堆中分配对象，和TLAB类似
    obj_ptr = _g1_par_allocator->allocate_direct_or_new_plab(dest_state, word_sz, context);
    if (obj_ptr == NULL) {
      // 仍然失败，如果上次尝试是在survivor，就尝试old分区分配，如果上次是old分区就直接报错
      obj_ptr = allocate_in_next_plab(state, &dest_state, word_sz, context);
      if (obj_ptr == NULL) {
        // This will either forward-to-self, or detect that someone else has
        // installed a forwarding pointer.
        // 还是失败说明无法复制对象，需要把对象头设置为自己
        return _g1h->handle_evacuation_failure_par(this, old);
      }
    }
  }


  const oop obj = oop(obj_ptr);
  const oop forward_ptr = old->forward_to_atomic(obj);
  if (forward_ptr == NULL) {
    // 对象头里没有指针说明这是第一次复制，所以复制后引用关系不变，相当于被引用者多了一个新的引用
    Copy::aligned_disjoint_words((HeapWord*) old, obj_ptr, word_sz);
    // 更新age和对象头
    if (dest_state.is_young()) {
      if (age < markOopDesc::max_age) {
        age++;
      }
      if (old_mark->has_displaced_mark_helper()) {
        // In this case, we have to install the mark word first,
        // otherwise obj looks to be forwarded (the old mark word,
        // which contains the forward pointer, was copied)
        // 对于重量级锁，前面的ptr指向是Monitor对象，其中ObjectMonitor的第一个字段是oopDes
        // 所以要设置old mark 再获得Monitor， 最后更新age
        obj->set_mark(old_mark);
        markOop new_mark = old_mark->displaced_mark_helper()->set_age(age);
        old_mark->set_displaced_mark_helper(new_mark);
      } else {
        obj->set_mark(old_mark->set_age(age));
      }
      age_table()->add(age, word_sz);
    } else {
      obj->set_mark(old_mark);
    }

    // 把字符串放到字符串去重队列，由去重线程处理
    if (G1StringDedup::is_enabled()) {
      const bool is_from_young = state.is_young();
      const bool is_to_young = dest_state.is_young();
      assert(is_from_young == _g1h->heap_region_containing_raw(old)->is_young(),
             "sanity");
      assert(is_to_young == _g1h->heap_region_containing_raw(obj)->is_young(),
             "sanity");
      G1StringDedup::enqueue_from_evacuation(is_from_young,
                                             is_to_young,
                                             queue_num(),
                                             obj);
    }

    size_t* const surv_young_words = surviving_young_words();
    surv_young_words[young_index] += word_sz;
    // 如果对象是对象类型的数组，长度超过 ParGCArrayScanChunk 阈值，可以先把它放到队列中后续处理，而不是深度搜索的对象栈
    // 目的是为了防止再遍历对象数组里面的每一个元素时，因为数组太长而导致处理队列溢出，只能放原始对象数组
    if (obj->is_objArray() && arrayOop(obj)->length() >= ParGCArrayScanChunk) {
      // We keep track of the next start index in the length field of
      // the to-space object. The actual length can be found in the
      // length field of the from-space object.
      arrayOop(obj)->set_length(0);
      oop* old_p = set_partial_array_mask(old);
      push_on_queue(old_p);
    } else {
      HeapRegion* const to_region = _g1h->heap_region_containing_raw(obj_ptr);
      _scanner.set_region(to_region);
      // 遍历扫描obj的每一个Field对象
      obj->oop_iterate_backwards(&_scanner);
    }
    return obj;
  } else {
    // 已经分配过了，不需要重复分配
    _g1_par_allocator->undo_allocation(dest_state, obj_ptr, word_sz, context);
    return forward_ptr;
  }
}

// 处理obj的每一个field，将所有的filed放到待处理的队列中
template <class T>
inline void G1ParScanClosure::do_oop_nv(T* p) {
  T heap_oop = oopDesc::load_heap_oop(p);

  if (!oopDesc::is_null(heap_oop)) {
    oop obj = oopDesc::decode_heap_oop_not_null(heap_oop);
    const InCSetState state = _g1->in_cset_state(obj);
    if (state.is_in_cset()) {
      // We're not going to even bother checking whether the object is
      // already forwarded or not, as this usually causes an immediate
      // stall. We'll try to prefetch the object (for write, given that
      // we might need to install the forwarding reference) and we'll
      // get back to it when pop it from the queue
      // 如果field需要回收，即在CSet中，放入队列，准备后续复制
      _par_scan_state->push_on_queue(p);
    } else {
      if (state.is_humongous()) {
        _g1->set_humongous_is_live(obj);
      }
      // 如果不需要，仅仅只需在后面重构RSet，保持引用关系
      _par_scan_state->update_rs(_from, p, _worker_id);
    }
  }
}