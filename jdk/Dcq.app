void ConcurrentG1RefineThread::run_young_rs_sampling() {

  while(!_should_terminate) {
    sample_young_list_rs_lengths();

    MutexLockerEx x(_monitor, Mutex::_no_safepoint_check_flag);
    if (_should_terminate) {
      break;
    }
	// 使用参数 G1ConcRefinementServiceIntervalMillis 控制抽样refine线程运行的频率，让while停一会儿
	// 生产中如果发现采样不足可以减少该时间，加快采样频率；如果系统运行稳定满足预测时间，可以增加该值减少采样频率
    _monitor->wait(Mutex::_no_safepoint_check_flag, G1ConcRefinementServiceIntervalMillis);
  }
}

// 因为G1每次都是全量young分区回收，所以需要根据用户设置的预测回收时长目标，对young分区数量做调整
// 至于新生代也采取分区机制的原因，则是因为这样跟老年代的策略统一，方便调整代的大小。
void ConcurrentG1RefineThread::sample_young_list_rs_lengths() {
  SuspendibleThreadSetJoiner sts;
  G1CollectedHeap* g1h = G1CollectedHeap::heap();
  G1CollectorPolicy* g1p = g1h->g1_policy();
  if (g1p->adaptive_young_list_length()) {
    int regions_visited = 0;
    g1h->young_list()->rs_length_sampling_init();
    // young_list是所有新生代分区形成的一个链表
    while (g1h->young_list()->rs_length_sampling_more()) {
      // rs_length_sampling_next 表示本次循环中有多少个分区可以加入到新生代分区
      // 当前分区有多少个引用的分区，包括稀疏、细粒度和粗粒度的分区个数，把这个数字加入到新生代总回收的要处理的分区数目
      // 也可以看到停顿时间指回收新生代分区要花费的时间，这个时间当然也包括分区中间引用的处理
      g1h->young_list()->rs_length_sampling_next();
      ++regions_visited;

      // we try to yield every time we visit 10 regions
      // 每处理10个分区主动让出CPU，为了在GC发生时VMThread能顺利进入到安全点
      if (regions_visited == 10) {
        if (sts.should_yield()) {
          sts.yield();
          // we just abandon the iteration
          break;
        }
        regions_visited = 0;
      }
    }
    // 利用上面的抽样数据更新新生代分区梳理
    g1p->revise_young_list_target_length_if_necessary();
  }
}

void G1CollectorPolicy::revise_young_list_target_length_if_necessary() {
  guarantee( adaptive_young_list_length(), "should not call this otherwise" );

  size_t rs_lengths = _g1->young_list()->sampled_rs_lengths();
  if (rs_lengths > _rs_lengths_prediction) {
    // add 10% to avoid having to recalculate often
    // 增加10%的冗余
    size_t rs_lengths_prediction = rs_lengths * 1100 / 1000;
    update_young_list_target_length(rs_lengths_prediction);
  }
}

// Dirty Card Queue Set初始化
dirty_card_queue_set().initialize(NULL, // Should never be called by the Java code
                                    // 全局的monitor，任意Mutator都可以通过静态方法找到DCQS静态变量
                                    // 每当一个DCQ满了就加入到DCQS中，当DCQS中DCQ个数超过阈值，
                                    // 通过这个Monitor发送Notify通知0号Refine线程启动
                                    DirtyCardQ_CBL_mon, 
                                    DirtyCardQ_FL_lock,
                                    -1, // never trigger processing
                                    -1, // no limit on length
                                    Shared_DirtyCardQ_lock,
                                    &JavaThread::dirty_card_queue_set());

// 把DCQ加入到DCQS
bool PtrQueueSet::process_or_enqueue_complete_buffer(void** buf) {
  if (Thread::current()->is_Java_thread()) {
    // We don't lock. It is fine to be epsilon-precise here.
    // DCQS已经满了，说明引用变更太多了，Refine线程负载太重，不继续添加DCQ
    // Mutator就会暂停其他代码执行，替代Refine线程来更新RSet
    if (_max_completed_queue == 0 || _max_completed_queue > 0 &&
        _n_completed_buffers >= _max_completed_queue + _completed_queue_padding) {
      bool b = mut_process_buffer(buf);
      if (b) {
        // True here means that the buffer hasn't been deallocated and the caller may reuse it.
        return true;
      }
    }
  }
  // The buffer will be enqueued. The caller will have to get a new one.
  // 把buffer加到DCQS中，注意这里加入之后调用者将会分配一个新的buffer
  enqueue_complete_buffer(buf);
  return false;
}

// DCQ形成一个链表
void PtrQueueSet::enqueue_complete_buffer(void** buf, size_t index) {
  MutexLockerEx x(_cbl_mon, Mutex::_no_safepoint_check_flag);
  BufferNode* cbn = BufferNode::new_from_buffer(buf);
  cbn->set_index(index);
  if (_completed_buffers_tail == NULL) {
    assert(_completed_buffers_head == NULL, "Well-formedness");
    _completed_buffers_head = cbn;
    _completed_buffers_tail = cbn;
  } else {
    _completed_buffers_tail->set_next(cbn);
    _completed_buffers_tail = cbn;
  }
  _n_completed_buffers++;

  // 判断是否需要Refine线程工作，如果没有线程工作通过Notify通知启动
  if (!_process_completed && _process_completed_threshold >= 0 &&
      _n_completed_buffers >= _process_completed_threshold) {
    _process_completed = true;
    if (_notify_when_complete)
      // 通知0号Refine线程
      _cbl_mon->notify();
  }
  debug_only(assert_completed_buffer_list_len_correct_locked());
}

// Enqueues the given "obj".
// 把对象加到DCQ，实际上DCQ就是一个buffer
void enqueue(void* ptr) {
  if (!_active) return;
  else enqueue_known_active(ptr);
}

void PtrQueue::enqueue_known_active(void* ptr) {
  // index为0表示DCQ已经满了，需要把DCQ加入到DCQS中，并申请新的DCQ
  while (_index == 0) {
    handle_zero_index();
  }

  // 在这里，无论如何都有合适的DCQ可以使用，因为满的DCQ会申请新的，直接加入对象到DCQ
  _index -= oopSize;
  _buf[byte_index_to_index((int)_index)] = ptr;
  assert(0 <= _index && _index <= _sz, "Invariant.");
}

// DCQ满了会调用这个方法
void PtrQueue::handle_zero_index() {
  assert(_index == 0, "Precondition.");

  // This thread records the full buffer and allocates a new one (while
  // holding the lock if there is one).
  // 先进行二次判断，防止DCQ满的情况下同一线程多次进入分配
  if (_buf != NULL) {
    if (!should_enqueue_buffer()) {
      assert(_index > 0, "the buffer can only be re-used if it's not full");
      return;
    }

    if (_lock) {

      // The current PtrQ may be the shared dirty card queue and
      // may be being manipulated by more than one worker thread
      // during a pause. Since the enqueuing of the completed
      // buffer unlocks the Shared_DirtyCardQ_lock more than one
      // worker thread can 'race' on reading the shared queue attributes
      // (_buf and _index) and multiple threads can call into this
      // routine for the same buffer. This will cause the completed
      // buffer to be added to the CBL multiple times.

      // We "claim" the current buffer by caching value of _buf in
      // a local and clearing the field while holding _lock. When
      // _lock is released (while enqueueing the completed buffer)
      // the thread that acquires _lock will skip this code,
      // preventing the subsequent the multiple enqueue, and
      // install a newly allocated buffer below.
      // 进入这里说明使用的是全局的DCQ，这里需要考虑多线程情况，这里引入局部变量buf目的在于处理多线程的竞争
      void** buf = _buf;   // local pointer to completed buffer
      _buf = NULL;         // clear shared _buf field

      locking_enqueue_completed_buffer(buf);  // enqueue completed buffer

      // While the current thread was enqueuing the buffer another thread
      // may have a allocated a new buffer and inserted it into this pointer
      // queue. If that happens then we just return so that the current
      // thread doesn't overwrite the buffer allocated by the other thread
      // and potentially losing some dirtied cards.
      // 如果buffer不为null，说明其他的线程已经成功地为全局DCQ申请到空间了，直接返回
      if (_buf != NULL) return;
    } else {
      // 此处是普通的DCQ处理
      if (qset()->process_or_enqueue_complete_buffer(_buf)) {
        // Recycle the buffer. No allocation.
        // 说明Mutator暂停执行，帮助处理DCQ，所以此时可以重用DCQ
        _sz = qset()->buffer_size();
        _index = _sz;
        return;
      }
    }
  }
  // Reallocate the buffer
  // 为DCQ申请新的空间
  _buf = qset()->allocate_buffer();
  _sz = qset()->buffer_size();
  _index = _sz;
  assert(0 <= _index && _index <= _sz, "Invariant.");
}