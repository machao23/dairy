IRT_ENTRY(void, InterpreterRuntime::_new(JavaThread* thread, ConstantPool* pool, int index))
  //从运行时常量池中获取KlassOop
  Klass* k_oop = pool->klass_at(index, CHECK);
  instanceKlassHandle klass (THREAD, k_oop);

  // Make sure we are not instantiating an abstract klass
  // 确保我们没有实例化一个抽象的klass
  klass->check_valid_for_instantiation(true, CHECK);

  // Make sure klass is initialized
  // 保证已经完成类加载和初始化，如果未加载，通过类加载器加载class
  klass->initialize(CHECK);

  // At this point the class may not be fully initialized
  // because of recursive initialization. If it is fully
  // initialized & has_finalized is not set, we rewrite
  // it into its fast version (Note: no locking is needed
  // here since this is an atomic byte write and can be
  // done more than once).
  //
  // Note: In case of classes with has_finalized we don't
  //       rewrite since that saves us an extra check in
  //       the fast version which then would call the
  //       slow version anyway (and do a call back into
  //       Java).
  //       If we have a breakpoint, then we don't rewrite
  //       because the _breakpoint bytecode would be lost.
  // 分配对象
  oop obj = klass->allocate_instance(CHECK);
  thread->set_vm_result(obj);
IRT_END

// JVM会先创建instanceklass，然后通过allocate_instance方法分配一个instanceOop
instanceOop InstanceKlass::allocate_instance(TRAPS) {
  int size = size_helper();  // Query before forming handle.

  KlassHandle h_k(THREAD, this);

  instanceOop i;
  // 在堆内存上进行分配
  i = (instanceOop)CollectedHeap::obj_allocate(h_k, size, CHECK_NULL);
  return i;
}

oop CollectedHeap::obj_allocate(KlassHandle klass, int size, TRAPS) {
  // 实质是调用 common_mem_allocate_noinit
  HeapWord* obj = common_mem_allocate_init(klass, size, CHECK_NULL);
  post_allocation_setup_obj(klass, obj, size);
  NOT_PRODUCT(Universe::heap()->check_for_bad_heap_word_value(obj, size));
  return (oop)obj;
}

HeapWord* CollectedHeap::common_mem_allocate_noinit(KlassHandle klass, size_t size, TRAPS) {

  HeapWord* result = NULL;
  // TLAB快速分配
  result = allocate_from_tlab(klass, THREAD, size);
  if (result != NULL) {
    assert(!HAS_PENDING_EXCEPTION,
           "Unexpected exception, will result in uninitialized storage");
    return result;
  }

  bool gc_overhead_limit_was_exceeded = false;
  // 快速分配失败后慢速分配
  result = Universe::heap()->mem_allocate(size,
                                          &gc_overhead_limit_was_exceeded);
  if (result != NULL) {
    NOT_PRODUCT(Universe::heap()->
      check_for_non_bad_heap_word_value(result, size));
    assert(!HAS_PENDING_EXCEPTION,
           "Unexpected exception, will result in uninitialized storage");
    THREAD->incr_allocated_bytes(size * HeapWordSize);

    AllocTracer::send_allocation_outside_tlab_event(klass, size * HeapWordSize);

    return result;
  }

  // 慢速分配也不成功就抛出异常
  if (!gc_overhead_limit_was_exceeded) {
  	// 内存不够
    // -XX:+HeapDumpOnOutOfMemoryError and -XX:OnOutOfMemoryError support
    report_java_out_of_memory("Java heap space");

    if (JvmtiExport::should_post_resource_exhausted()) {
      JvmtiExport::post_resource_exhausted(
        JVMTI_RESOURCE_EXHAUSTED_OOM_ERROR | JVMTI_RESOURCE_EXHAUSTED_JAVA_HEAP,
        "Java heap space");
    }

    THROW_OOP_0(Universe::out_of_memory_error_java_heap());
  } else {
  	// 超过垃圾回收的最大次数
    // -XX:+HeapDumpOnOutOfMemoryError and -XX:OnOutOfMemoryError support
    report_java_out_of_memory("GC overhead limit exceeded");

    if (JvmtiExport::should_post_resource_exhausted()) {
      JvmtiExport::post_resource_exhausted(
        JVMTI_RESOURCE_EXHAUSTED_OOM_ERROR | JVMTI_RESOURCE_EXHAUSTED_JAVA_HEAP,
        "GC overhead limit exceeded");
    }

    THROW_OOP_0(Universe::out_of_memory_error_gc_overhead_limit());
  }
}

HeapWord* CollectedHeap::allocate_from_tlab(KlassHandle klass, Thread* thread, size_t size) {
  assert(UseTLAB, "should use UseTLAB");

  // 从TLAB已分配的缓存区空间直接分配对象，也称为指针碰撞分配
  HeapWord* obj = thread->tlab().allocate(size);
  if (obj != NULL) {
    return obj;
  }
  // Otherwise...
  // 分配失败
  return allocate_from_tlab_slow(klass, thread, size);
}

// TLAB的全称：ThreadLocalAllocBuffer
class ThreadLocalAllocBuffer: public CHeapObj<mtThread> {
  friend class VMStructs;
private:
  // start 和 end 是占位用的，标识出 eden 里被这个 TLAB 所管理的区域
  // 卡住eden里的一块空间不让其它线程来这里分配
  HeapWord* _start;                              // address of TLAB
  HeapWord* _end;                                // allocation end (excluding alignment_reserve)
  // top 就是里面的分配指针，一开始指向跟 start 同样的位置，然后逐渐分配，
  // 直到再要分配下一个对象就会撞上 end 的时候就会触发一次 TLAB refill
  HeapWord* _top;                                // address after last allocation
  // _desired_size 是指TLAB的内存大小。
  size_t    _desired_size;                       // desired size   (including alignment_reserve)
  // _refill_waste_limit 是指最大的浪费空间
  size_t    _refill_waste_limit;                 // hold onto tlab if free() is larger than this

 }

// 执行new Thread()的时候，会被触发
void JavaThread::run() {
  // initialize thread-local alloc buffer related fields
  // 初始化TLAB
  this->initialize_tlab();
  ...
}

void ThreadLocalAllocBuffer::initialize() {
  initialize(NULL,                    // start
             NULL,                    // top
             NULL);                   // end
  // 设置当前TLAB的_desired_size
  set_desired_size(initial_desired_size());

  // Following check is needed because at startup the main (primordial)
  // thread is initialized before the heap is.  The initialization for
  // this thread is redone in startup_initialization below.
  if (Universe::heap() != NULL) {
    size_t capacity   = Universe::heap()->tlab_capacity(myThread()) / HeapWordSize;
    double alloc_frac = desired_size() * target_refills() / (double) capacity;
    _allocation_fraction.sample(alloc_frac);
  }
  // 设置当前TLAB的_refill_waste_limit
  set_refill_waste_limit(initial_refill_waste_limit());

  // 初始化一些统计字段
  initialize_statistics();
}

// 计算TLAB的初始值
size_t ThreadLocalAllocBuffer::initial_desired_size() {
  size_t init_sz = 0;
  // TLABSize默认会设置大小为 256K
  init_sz = TLABSize / HeapWordSize;
  init_sz = MIN2(MAX2(init_sz, min_size()), max_size());
  return init_sz;
}

// TLABRefillWasteFraction默认 64， 即容忍浪费1/64的TLAB大小
size_t initial_refill_waste_limit()            { return desired_size() / TLABRefillWasteFraction; }


// TLAB快速分配内存给对象
inline HeapWord* ThreadLocalAllocBuffer::allocate(size_t size) {
  HeapWord* obj = top();
  // 判断当前TLAB的剩余容量是否大于需要分配的大小
  if (pointer_delta(end(), obj) >= size) {
    // successful thread-local allocation
#ifdef ASSERT
    // Skip mangling the space corresponding to the object header to
    // ensure that the returned space is not considered parsable by
    // any concurrent GC thread.
    size_t hdr_size = oopDesc::header_size();
    Copy::fill_to_words(obj + hdr_size, size - hdr_size, badHeapWordVal);
#endif // ASSERT
    // This addition is safe because we know that top is
    // at least size below end, so the add can't wrap.
    set_top(obj + size);

    invariants();
    return obj;
  }
  // 如果当前剩余容量不够，就返回NULL，表示分配失败。
  return NULL;
}

// 当前TLAB容量不够，进入TLAB慢分配
HeapWord* CollectedHeap::allocate_from_tlab_slow(KlassHandle klass, Thread* thread, size_t size) {

  // Retain tlab and allocate object in shared space if
  // the amount free in the tlab is too large to discard.
  // 如果当前TLAB的剩余容量大于浪费阈值，就不在当前TLAB分配，直接在共享的Eden区进行分配，并且记录慢分配的内存大小
  if (thread->tlab().free() > thread->tlab().refill_waste_limit()) {
  	// 不能丢掉当前的TLAB，根据TLABWasteIncrement动态调整refill_waste的阈值
    thread->tlab().record_slow_allocation(size);
    return NULL;
  }

  // 如果剩余容量小于浪费阈值，需要重新分配一个TLAB，老的TLAB不用处理，因为它属于Eden，YGC可以回收该空间
  // Discard tlab and allocate a new one.
  // To minimize fragmentation, the last TLAB may be smaller than the rest.
  size_t new_tlab_size = thread->tlab().compute_size(size);
  // 分配之前先清理老的TLAB，为了让堆保持parsable可解析
  // 这个过程中最重要的动作是将TLAB末尾尚未分配给Java对象的空间（浪费掉的空间）分配成一个假的“filler object”
  //（目前是用int[]作为filler object）
  // GC扫描堆时，如果是对象直接跳过对象的长度，空白的地方只能一个个字扫描效率低
  thread->tlab().clear_before_allocation();

  if (new_tlab_size == 0) {
    return NULL;
  }

  // 通过allocate_new_tlab()方法，从eden新分配一块裸的空间出来（这一步可能会失败），
  // 如果失败说明eden没有足够空间来分配这个新TLAB，就会触发YGC。
  // Allocate a new TLAB...
  HeapWord* obj = Universe::heap()->allocate_new_tlab(new_tlab_size);
  if (obj == NULL) {
    return NULL;
  }

  // 申请好新的TLAB内存之后，执行TLAB的fill()方法
  // 分配对象，并设置TLAB的start、top、end等信息
  thread->tlab().fill(obj, obj + size, new_tlab_size);
  return obj;
}

// 申请一个新的TLAB
HeapWord* G1CollectedHeap::allocate_new_tlab(size_t word_size) {
  uint dummy_gc_count_before;
  uint dummy_gclocker_retry_count = 0;
  return attempt_allocation(word_size, &dummy_gc_count_before, &dummy_gclocker_retry_count);
}

// 在申请新的TLAB时候被调用
inline HeapWord* G1CollectedHeap::attempt_allocation(size_t word_size,
                                                     uint* gc_count_before_ret,
                                                     uint* gclocker_retry_count_ret) {
  AllocationContext_t context = AllocationContext::current();
  // 快速无锁分配
  HeapWord* result = _allocator->mutator_alloc_region(context)->attempt_allocation(word_size,
                                                                                   false /* bot_updates */);
  if (result == NULL) {
  	// 慢速加锁分配
    result = attempt_allocation_slow(word_size,
                                     context,
                                     gc_count_before_ret,
                                     gclocker_retry_count_ret);
  }
  assert_heap_not_locked();
  if (result != NULL) {
    dirty_young_block(result, word_size);
  }
  return result;
}

void ThreadLocalAllocBuffer::fill(HeapWord* start,
                                  HeapWord* top,
                                  size_t    new_size) {
  // 统计refill的次数
  _number_of_refills++;
  // 初始化重新申请到的内存块
  initialize(start, top, start + new_size - alignment_reserve());

  // Reset amount of internal fragmentation
  set_refill_waste_limit(initial_refill_waste_limit());
}