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

void CMTask::do_marking_step(double time_target_ms,
                             bool do_termination,
                             bool is_serial) {
  // 根据过去的标记信息，预测本次标记要花费的时间
  double diff_prediction_ms =
    g1_policy->get_new_prediction(&_marking_step_diffs_ms);
  _time_target_ms = time_target_ms - diff_prediction_ms;

  // 这里设置的closure会在后面用到
  CMBitMapClosure bitmap_closure(this, _cm, _nextMarkBitMap);
  G1CMOopClosure  cm_oop_closure(_g1h, _cm, this);
  set_cm_oop_closure(&cm_oop_closure);

  // 处理satb队列
  drain_satb_buffers();
  // ...then partially drain the local queue and the global stack
  // 根据根对象标记时发现的对象开始处理
  drain_local_queue(true);
  // 针对全局标记栈开始处理，把全局标记栈的对象移入CMTask（CM是并发标记的缩写）的队列中，等待处理
  drain_global_stack(true);

  do {
    if (!has_aborted() && _curr_region != NULL) {
      // MemRegion是新增的对象，所以从finger开始到结束全部标记
      MemRegion mr = MemRegion(_finger, _region_limit);

      if (mr.is_empty()) {
        giveup_current_region();
        regular_clock_call();
      } else if (_curr_region->isHumongous() && mr.start() == _curr_region->bottom()) {
        if (_nextMarkBitMap->isMarked(mr.start())) {
          // The object is marked - apply the closure
          BitMap::idx_t offset = _nextMarkBitMap->heapWordToOffset(mr.start());
		  // 调整finger，处理本对象的field所指向的oop对象
          bitmap_closure.do_bit(offset);
        }
        // Even if this task aborted while scanning the humongous object
        // we can (and should) give up the current region.
        giveup_current_region();
        regular_clock_call();
      } else if (_nextMarkBitMap->iterate(&bitmap_closure, mr)) {
		// 处理本分区的标记对象
        giveup_current_region();
        regular_clock_call();
      } 
    }
    // 再次处理本地队列和全局标记栈
    drain_local_queue(true);
    drain_global_stack(true);

    // Read the note on the claim_region() method on why it might
    // return NULL with potentially more regions available for
    // claiming and why we have to check out_of_regions() to determine
    // whether we're done or not.
    while (!has_aborted() && _curr_region == NULL && !_cm->out_of_regions()) {
      // We are going to try to claim a new region. We should have
      // given up on the previous one.
      // Separated the asserts so that we know which one fires.
      assert(_curr_region  == NULL, "invariant");
      assert(_finger       == NULL, "invariant");
      assert(_region_limit == NULL, "invariant");
      if (_cm->verbose_low()) {
        gclog_or_tty->print_cr("[%u] trying to claim a new region", _worker_id);
      }
      HeapRegion* claimed_region = _cm->claim_region(_worker_id);
      if (claimed_region != NULL) {
        // Yes, we managed to claim one
        statsOnly( ++_regions_claimed );

        if (_cm->verbose_low()) {
          gclog_or_tty->print_cr("[%u] we successfully claimed "
                                 "region "PTR_FORMAT,
                                 _worker_id, p2i(claimed_region));
        }

        setup_for_region(claimed_region);
        assert(_curr_region == claimed_region, "invariant");
      }
      // It is important to call the regular clock here. It might take
      // a while to claim a region if, for example, we hit a large
      // block of empty regions. So we need to call the regular clock
      // method once round the loop to make sure it's called
      // frequently enough.
      regular_clock_call();
    }

    if (!has_aborted() && _curr_region == NULL) {
      assert(_cm->out_of_regions(),
             "at this point we should be out of regions");
    }
  } while ( _curr_region != NULL && !has_aborted());

  if (!has_aborted()) {
    // We cannot check whether the global stack is empty, since other
    // tasks might be pushing objects to it concurrently.
    assert(_cm->out_of_regions(),
           "at this point we should be out of regions");

    if (_cm->verbose_low()) {
      gclog_or_tty->print_cr("[%u] all regions claimed", _worker_id);
    }

    // Try to reduce the number of available SATB buffers so that
    // remark has less work to do.
    drain_satb_buffers();
  }

  // Since we've done everything else, we can now totally drain the
  // local queue and global stack.
  drain_local_queue(false);
  drain_global_stack(false);

  // Attempt at work stealing from other task's queues.
  if (do_stealing && !has_aborted()) {
    // We have not aborted. This means that we have finished all that
    // we could. Let's try to do some stealing...

    // We cannot check whether the global stack is empty, since other
    // tasks might be pushing objects to it concurrently.
    assert(_cm->out_of_regions() && _task_queue->size() == 0,
           "only way to reach here");

    if (_cm->verbose_low()) {
      gclog_or_tty->print_cr("[%u] starting to steal", _worker_id);
    }

    while (!has_aborted()) {
      oop obj;
      statsOnly( ++_steal_attempts );

      if (_cm->try_stealing(_worker_id, &_hash_seed, obj)) {
        if (_cm->verbose_medium()) {
          gclog_or_tty->print_cr("[%u] stolen "PTR_FORMAT" successfully",
                                 _worker_id, p2i((void*) obj));
        }

        statsOnly( ++_steals );

        assert(_nextMarkBitMap->isMarked((HeapWord*) obj),
               "any stolen object should be marked");
        scan_object(obj);

        // And since we're towards the end, let's totally drain the
        // local queue and global stack.
        drain_local_queue(false);
        drain_global_stack(false);
      } else {
        break;
      }
    }
  }

  // If we are about to wrap up and go into termination, check if we
  // should raise the overflow flag.
  if (do_termination && !has_aborted()) {
    if (_cm->force_overflow()->should_force()) {
      _cm->set_has_overflown();
      regular_clock_call();
    }
  }

  // We still haven't aborted. Now, let's try to get into the
  // termination protocol.
  if (do_termination && !has_aborted()) {
    // We cannot check whether the global stack is empty, since other
    // tasks might be concurrently pushing objects on it.
    // Separated the asserts so that we know which one fires.
    assert(_cm->out_of_regions(), "only way to reach here");
    assert(_task_queue->size() == 0, "only way to reach here");

    if (_cm->verbose_low()) {
      gclog_or_tty->print_cr("[%u] starting termination protocol", _worker_id);
    }

    _termination_start_time_ms = os::elapsedVTime() * 1000.0;

    // The CMTask class also extends the TerminatorTerminator class,
    // hence its should_exit_termination() method will also decide
    // whether to exit the termination protocol or not.
    bool finished = (is_serial ||
                     _cm->terminator()->offer_termination(this));
    double termination_end_time_ms = os::elapsedVTime() * 1000.0;
    _termination_time_ms +=
      termination_end_time_ms - _termination_start_time_ms;

    if (finished) {
      // We're all done.

      if (_worker_id == 0) {
        // let's allow task 0 to do this
        if (concurrent()) {
          assert(_cm->concurrent_marking_in_progress(), "invariant");
          // we need to set this to false before the next
          // safepoint. This way we ensure that the marking phase
          // doesn't observe any more heap expansions.
          _cm->clear_concurrent_marking_in_progress();
        }
      }

      // We can now guarantee that the global stack is empty, since
      // all other tasks have finished. We separated the guarantees so
      // that, if a condition is false, we can immediately find out
      // which one.
      guarantee(_cm->out_of_regions(), "only way to reach here");
      guarantee(_cm->mark_stack_empty(), "only way to reach here");
      guarantee(_task_queue->size() == 0, "only way to reach here");
      guarantee(!_cm->has_overflown(), "only way to reach here");
      guarantee(!_cm->mark_stack_overflow(), "only way to reach here");

      if (_cm->verbose_low()) {
        gclog_or_tty->print_cr("[%u] all tasks terminated", _worker_id);
      }
    } else {
      // Apparently there's more work to do. Let's abort this task. It
      // will restart it and we can hopefully find more things to do.

      if (_cm->verbose_low()) {
        gclog_or_tty->print_cr("[%u] apparently there is more work to do",
                               _worker_id);
      }

      set_has_aborted();
      statsOnly( ++_aborted_termination );
    }
  }

  // Mainly for debugging purposes to make sure that a pointer to the
  // closure which was statically allocated in this frame doesn't
  // escape it by accident.
  set_cm_oop_closure(NULL);
  double end_time_ms = os::elapsedVTime() * 1000.0;
  double elapsed_time_ms = end_time_ms - _start_time_ms;
  // Update the step history.
  _step_times_ms.add(elapsed_time_ms);

  if (has_aborted()) {
    // The task was aborted for some reason.

    statsOnly( ++_aborted );

    if (_has_timed_out) {
      double diff_ms = elapsed_time_ms - _time_target_ms;
      // Keep statistics of how well we did with respect to hitting
      // our target only if we actually timed out (if we aborted for
      // other reasons, then the results might get skewed).
      _marking_step_diffs_ms.add(diff_ms);
    }

    if (_cm->has_overflown()) {
      // This is the interesting one. We aborted because a global
      // overflow was raised. This means we have to restart the
      // marking phase and start iterating over regions. However, in
      // order to do this we have to make sure that all tasks stop
      // what they are doing and re-initialise in a safe manner. We
      // will achieve this with the use of two barrier sync points.

      if (_cm->verbose_low()) {
        gclog_or_tty->print_cr("[%u] detected overflow", _worker_id);
      }

      if (!is_serial) {
        // We only need to enter the sync barrier if being called
        // from a parallel context
        _cm->enter_first_sync_barrier(_worker_id);

        // When we exit this sync barrier we know that all tasks have
        // stopped doing marking work. So, it's now safe to
        // re-initialise our data structures. At the end of this method,
        // task 0 will clear the global data structures.
      }

      statsOnly( ++_aborted_overflow );

      // We clear the local state of this task...
      clear_region_fields();

      if (!is_serial) {
        // ...and enter the second barrier.
        _cm->enter_second_sync_barrier(_worker_id);
      }
      // At this point, if we're during the concurrent phase of
      // marking, everything has been re-initialized and we're
      // ready to restart.
    }

    if (_cm->verbose_low()) {
      gclog_or_tty->print_cr("[%u] <<<<<<<<<< ABORTING, target = %1.2lfms, "
                             "elapsed = %1.2lfms <<<<<<<<<<",
                             _worker_id, _time_target_ms, elapsed_time_ms);
      if (_cm->has_aborted()) {
        gclog_or_tty->print_cr("[%u] ========== MARKING ABORTED ==========",
                               _worker_id);
      }
    }
  } else {
    if (_cm->verbose_low()) {
      gclog_or_tty->print_cr("[%u] <<<<<<<<<< FINISHED, target = %1.2lfms, "
                             "elapsed = %1.2lfms <<<<<<<<<<",
                             _worker_id, _time_target_ms, elapsed_time_ms);
    }
  }

  _claimed = false;
}
