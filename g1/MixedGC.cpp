// 并发标记
void ConcurrentMarkThread::run() {

  while (!_should_terminate) {
    // wait until started is set.
	// 并发标记线程创建后在一定的条件下才能启动
    sleepBeforeNextCycle();
    if (_should_terminate) {
      break;
    }

    {
      if (!cm()->has_aborted()) {
		// 并发标记开始，从Survivor分区开始扫描
        _cm->scanRootRegions();
      }

      int iter = 0;
      do {
        iter++;
        if (!cm()->has_aborted()) {
		  // 并发标记子阶段
          _cm->markFromRoots();
        }

        if (!cm()->has_aborted()) {
		  // 再标记阶段
          CMCheckpointRootsFinalClosure final_cl(_cm);
          VM_CGC_Operation op(&final_cl, "GC remark", true /* needs_pll */);
          VMThread::execute(&op);
        }
      } while (cm()->restart_for_overflow());

      if (!cm()->has_aborted()) {
		// 执行清理
        CMCleanUp cl_cl(_cm);
        VM_CGC_Operation op(&cl_cl, "GC cleanup", false /* needs_pll */);
        VMThread::execute(&op);
      } else {
        // We don't want to update the marking status if a GC pause
        // is already underway.
        SuspendibleThreadSetJoiner sts;
        g1h->set_marking_complete();
      }

      {
		// 通知下一次GC发生时，应该启动mixed'GC,即要回收old分区
        SuspendibleThreadSetJoiner sts;
        if (!cm()->has_aborted()) {
          g1_policy->record_concurrent_mark_cleanup_completed();
        }
      }

	  // 清理工作后交换了MarkBitmap，需要对nextMarkBitmap重新置位，便于下一次并发标记
      if (!cm()->has_aborted()) {
        SuspendibleThreadSetJoiner sts;
        _cm->clearNextBitmap();
      }
    }
  }

  terminate();
}