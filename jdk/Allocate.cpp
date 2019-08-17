// JVM会先创建instanceklass，然后通过allocate_instance方法分配一个instanceOop
instanceOop InstanceKlass::allocate_instance(TRAPS) {
  int size = size_helper();  // Query before forming handle.

  KlassHandle h_k(THREAD, this);

  instanceOop i;
  // 内存分配，主要工作是调用CollectHeap::common_mem_allocate_noinit
  i = (instanceOop)CollectedHeap::obj_allocate(h_k, size, CHECK_NULL);
  return i;
}

HeapWord* CollectedHeap::common_mem_allocate_noinit(KlassHandle klass, size_t size, TRAPS) {

  HeapWord* result = NULL;
  if (UseTLAB) {
  	// TLAB快速分配
    result = allocate_from_tlab(klass, THREAD, size);
    if (result != NULL) {
      assert(!HAS_PENDING_EXCEPTION,
             "Unexpected exception, will result in uninitialized storage");
      return result;
    }
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