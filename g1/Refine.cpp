// 每个Refine线程的运行逻辑
void ConcurrentG1RefineThread::run() {
  // 初始化线程私有信息
  initialize_in_thread();
  wait_for_universe_init();

  if (_worker_id >= cg1r()->worker_thread_num()) {
    // Refine的最后一个线程用于处理Young分区的抽样，为了预测停顿时间调整分区数目
    run_young_rs_sampling();
    terminate();
    return;
  }

  // 0~n-1 线程是真正的Refine线程，处理RSet
  while (!_should_terminate) {
    DirtyCardQueueSet& dcqs = JavaThread::dirty_card_queue_set();

    // Wait for work
    // 等待前一号线程通知
    wait_for_completed_buffers();

    if (_should_terminate) {
      break;
    }

    {
      SuspendibleThreadSetJoiner sts;

      do {

        // 根据负载均衡判断是否工作量闲置停止当前的Refine线程
        if (_worker_id > 0 && curr_buffer_num <= _deactivation_threshold) {
          // If the number of the buffer has fallen below our threshold
          // we should deactivate. The predecessor will reactivate this
          // thread should the number of the buffers cross the threshold again.
          // 停止线程
          deactivate();
          break;
        }

        // Check if we need to activate the next thread.
        // 根据负载均衡判断是否工作量很大通知/启动新的线程
        if (_next != NULL && !_next->is_active() && curr_buffer_num > _next->_threshold) {
          // 发送通知给下一号Refine线程
          _next->activate();
        }
      }
      // 处理DCQS，_refine_closure 真正处理卡表
      // _worker_id + _worker_id_offset 工作线程要处理的开始位置，让不同的Refine线程处理DCQS中不同的DCQ
      // cg1r()->green_zone() 所有的Refine线程在处理DCQS的时候都知道要跳过至少Green个数的DCQ
      while (dcqs.apply_closure_to_completed_buffer(_refine_closure, _worker_id + _worker_id_offset, cg1r()->green_zone()));

      // We can exit the loop above while being active if there was a yield request.
      // 当有Yield请求时退出循环，目的是为了进入安全点
      if (is_active()) {
        deactivate();
      }
    }

    if (os::supports_vtime()) {
      _vtime_accum = (os::elapsedVTime() - _vtime_start);
    } else {
      _vtime_accum = 0.0;
    }
  }
  assert(_should_terminate, "just checking");
  terminate();
}

// Refine线程在处理dcq时候调用
bool G1RemSet::refine_card(jbyte* card_ptr, uint worker_i,
                           bool check_for_refs_into_cset) {

  // If the card is no longer dirty, nothing to do.
  // 如果卡表指针对应的值已经不是dirty，说明该指针已经处理过了，所以不需要处理，直接返回
  if (*card_ptr != CardTableModRefBS::dirty_card_val()) {
    // No need to return that this card contains refs that point
    // into the collection set.
    return false;
  }

  // Construct the region representing the card.
  HeapWord* start = _ct_bs->addr_for(card_ptr);
  // And find the region containing it.
  // 找到卡表指针所在的分区
  HeapRegion* r = _g1->heap_region_containing(start);

  // Why do we have to check here whether a card is on a young region,
  // given that we dirty young regions and, as a result, the
  // post-barrier is supposed to filter them out and never to enqueue
  // them? When we allocate a new region as the "allocation region" we
  // actually dirty its cards after we release the lock, since card
  // dirtying while holding the lock was a performance bottleneck. So,
  // as a result, it is possible for other threads to actually
  // allocate objects in the region (after the acquire the lock)
  // before all the cards on the region are dirtied. This is unlikely,
  // and it doesn't happen often, but it can happen. So, the extra
  // check below filters out those cards.
  // 引用者时young分区或在CSet都不需要更新，因为它们都会在GC中被收集，实际上在引用关系进入到队列时候会被过滤
  // 为什么在这里还要过滤，主要是考虑并发的因素，比如并发分配或者并行任务窃取
  if (r->is_young()) {
    return false;
  }

  // While we are processing RSet buffers during the collection, we
  // actually don't want to scan any cards on the collection set,
  // since we don't want to update remebered sets with entries that
  // point into the collection set, given that live objects from the
  // collection set are about to move and such entries will be stale
  // very soon. This change also deals with a reliability issue which
  // involves scanning a card in the collection set and coming across
  // an array that was being chunked and looking malformed. Note,
  // however, that if evacuation fails, we have to scan any objects
  // that were not moved and create any missing entries.
  // 是否在CSet检查
  if (r->in_collection_set()) {
    return false;
  }

  // The result from the hot card cache insert call is either:
  //   * pointer to the current card
  //     (implying that the current card is not 'hot'),
  //   * null
  //     (meaning we had inserted the card ptr into the "hot" card cache,
  //     which had some headroom),
  //   * a pointer to a "hot" card that was evicted from the "hot" cache.
  //
  // 对于热表可以通过参数控制，如果发现不热则直接处理
  // 如果热表存的对象太多，最老的会被赶出继续处理
  G1HotCardCache* hot_card_cache = _cg1r->hot_card_cache();
  if (hot_card_cache->use_cache()) {
    // 如果热就留待后续批量处理，
    assert(!check_for_refs_into_cset, "sanity");
    assert(!SafepointSynchronize::is_at_safepoint(), "sanity");

    card_ptr = hot_card_cache->insert(card_ptr);
    if (card_ptr == NULL) {
      // There was no eviction. Nothing to do.
      return false;
    }

    start = _ct_bs->addr_for(card_ptr);
    r = _g1->heap_region_containing(start);

    // Checking whether the region we got back from the cache
    // is young here is inappropriate. The region could have been
    // freed, reallocated and tagged as young while in the cache.
    // Hence we could see its young type change at any time.
  }

  // Don't use addr_for(card_ptr + 1) which can ask for
  // a card beyond the heap.  This is not safe without a perm
  // gen at the upper end of the heap.
  // 确定要处理的内存块是512字节，即一个Card？
  HeapWord* end   = start + CardTableModRefBS::card_size_in_words;
  MemRegion dirtyRegion(start, end);

  G1ParPushHeapRSClosure* oops_in_heap_closure = NULL;
  if (check_for_refs_into_cset) {
    // ConcurrentG1RefineThreads have worker numbers larger than what
    // _cset_rs_update_cl[] is set up to handle. But those threads should
    // only be active outside of a collection which means that when they
    // reach here they should have check_for_refs_into_cset == false.
    assert((size_t)worker_i < n_workers(), "index of worker larger than _cset_rs_update_cl[].length");
    oops_in_heap_closure = _cset_rs_update_cl[worker_i];
  }
  G1UpdateRSOrPushRefOopClosure update_rs_oop_cl(_g1,
                                                 _g1->g1_rem_set(),
                                                 oops_in_heap_closure,
                                                 check_for_refs_into_cset,
                                                 worker_i);
  update_rs_oop_cl.set_from(r);

  G1TriggerClosure trigger_cl;
  FilterIntoCSClosure into_cs_cl(NULL, _g1, &trigger_cl);
  G1InvokeIfNotTriggeredClosure invoke_cl(&trigger_cl, &into_cs_cl);
  G1Mux2Closure mux(&invoke_cl, &update_rs_oop_cl);

  FilterOutOfRegionClosure filter_then_update_rs_oop_cl(r,
                        (check_for_refs_into_cset ?
                                (OopClosure*)&mux :
                                (OopClosure*)&update_rs_oop_cl));

  // The region for the current card may be a young region. The
  // current card may have been a card that was evicted from the
  // card cache. When the card was inserted into the cache, we had
  // determined that its region was non-young. While in the cache,
  // the region may have been freed during a cleanup pause, reallocated
  // and tagged as young.
  //
  // We wish to filter out cards for such a region but the current
  // thread, if we're running concurrently, may "see" the young type
  // change at any time (so an earlier "is_young" check may pass or
  // fail arbitrarily). We tell the iteration code to perform this
  // filtering when it has been determined that there has been an actual
  // allocation in this region and making it safe to check the young type.
  bool filter_young = true;

  // 具体处理DCQ里Dirty Card所在的dirty region 在这里
  HeapWord* stop_point =
    r->oops_on_card_seq_iterate_careful(dirtyRegion,
                                        &filter_then_update_rs_oop_cl,
                                        filter_young,
                                        card_ptr);

  // If stop_point is non-null, then we encountered an unallocated region
  // (perhaps the unfilled portion of a TLAB.)  For now, we'll dirty the
  // card and re-enqueue: if we put off the card until a GC pause, then the
  // unallocated portion will be filled in.  Alternatively, we might try
  // the full complexity of the technique used in "regular" precleaning.
  if (stop_point != NULL) {
    // 如果处理结果出现问题，比如内存不连续等，
    // The card might have gotten re-dirtied and re-enqueued while we
    // worked.  (In fact, it's pretty likely.)
    if (*card_ptr != CardTableModRefBS::dirty_card_val()) {
      *card_ptr = CardTableModRefBS::dirty_card_val();
      MutexLockerEx x(Shared_DirtyCardQ_lock,
                      Mutex::_no_safepoint_check_flag);
      // 把该引用放到公共的DCQS，等待后续处理
      DirtyCardQueue* sdcq =
        JavaThread::dirty_card_queue_set().shared_dirty_card_queue();
      sdcq->enqueue(card_ptr);
    }
  } else {
    _conc_refine_cards++;
  }

  // This gets set to true if the card being refined has
  // references that point into the collection set.
  bool has_refs_into_cset = trigger_cl.triggered();

  return has_refs_into_cset;
}

// 具体处理DCQ里Dirty Card所在的dirty region 在这里
HeapWord* HeapRegion::oops_on_card_seq_iterate_careful(MemRegion mr,
                                 FilterOutOfRegionClosure* cl,
                                 bool filter_young,
                                 jbyte* card_ptr) {

  // We can only clean the card here, after we make the decision that
  // the card is not young. And we only clean the card if we have been
  // asked to (i.e., card_ptr != NULL).
  if (card_ptr != NULL) {
      // 把card改成clean状态，说明该内存块正在被处理
    *card_ptr = CardTableModRefBS::clean_card_val();
    // We must complete this write before we do any of the reads below.
    OrderAccess::storeload();
  }

  // Cache the boundaries of the memory region in some const locals
  HeapWord* const start = mr.start();
  HeapWord* const end = mr.end();

  // We used to use "block_start_careful" here.  But we're actually happy
  // to update the BOT while we do this...
  HeapWord* cur = block_start(start);
  assert(cur <= start, "Postcondition");

  oop obj;

  HeapWord* next = cur;
  // 跳过不需要处理的内存块，直到到达这512字节的内存
  do {
    cur = next;
    obj = oop(cur);
    if (obj->klass_or_null() == NULL) {
      // Ran into an unparseable point.
      return cur;
    }
    // Otherwise...
    next = cur + block_size(cur);
  } while (next <= start);

  // 然后遍历这512字节内存块
  do {
    obj = oop(cur);
    assert((cur + block_size(cur)) > (HeapWord*)obj, "Loop invariant");
    if (obj->klass_or_null() == NULL) {
      // Ran into an unparseable point.
      return cur;
    }

    // Advance the current pointer. "obj" still points to the object to iterate.
    cur = cur + block_size(cur);

    // 这里判断对象是否死亡的依据是根据内存的快照
    if (!g1h->is_obj_dead(obj)) {
      // Non-objArrays are sometimes marked imprecise at the object start. We
      // always need to iterate over them in full.
      // We only iterate over object arrays in full if they are completely contained
      // in the memory region.
      // 遍历对象
      if (!obj->is_objArray() || (((HeapWord*)obj) >= start && cur <= end)) {
        obj->oop_iterate(cl);
      } else {
        obj->oop_iterate(cl, mr);
      }
    }
  } while (cur < end);

  return NULL;
}

// 遍历对象的时候，调用该方法更新RSet
template <class T> inline void G1UpdateRSOrPushRefOopClosure::do_oop_nv(T* p) {

  HeapRegion* to = _g1->heap_region_containing(obj);
  // 只处理不同分区之间的引用关系，相同分区肯定是一起回收的不需要建立引用关系
  if (_from == to) {
    // Normally this closure should only be called with cross-region references.
    // But since Java threads are manipulating the references concurrently and we
    // reload the values things may have changed.
    return;
  }
  // The _record_refs_into_cset flag is true during the RSet
  // updating part of an evacuation pause. It is false at all
  // other times:
  //  * rebuilding the remembered sets after a full GC
  //  * during concurrent refinement.
  //  * updating the remembered sets of regions in the collection
  //    set in the event of an evacuation failure (when deferred
  //    updates are enabled).

  if (_record_refs_into_cset && to->in_collection_set()) {
    // We are recording references that point into the collection
    // set and this particular reference does exactly that...
    // If the referenced object has already been forwarded
    // to itself, we are handling an evacuation failure and
    // we have already visited/tried to copy this object
    // there is no need to retry.
    // Evac的情况下才会走到这里？
    if (!self_forwarded(obj)) {
      assert(_push_ref_cl != NULL, "should not be null");
      // Push the reference in the refs queue of the G1ParScanThreadState
      // instance for this worker thread.
      _push_ref_cl->do_oop(p);
     }

    // Deferred updates to the CSet are either discarded (in the normal case),
    // or processed (if an evacuation failure occurs) at the end
    // of the collection.
    // See G1RemSet::cleanup_after_oops_into_collection_set_do().
  } else {
    // We either don't care about pushing references that point into the
    // collection set (i.e. we're not during an evacuation pause) _or_
    // the reference doesn't point into the collection set. Either way
    // we add the reference directly to the RSet of the region containing
    // the referenced object.
    assert(to->rem_set() != NULL, "Need per-region 'into' remsets.");
    // 正常情况下添加引用关系到RSet，更新PRT信息
    to->rem_set()->add_reference(p, _worker_i);
  }
}