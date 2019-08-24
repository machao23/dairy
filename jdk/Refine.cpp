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

void ConcurrentG1RefineThread::sample_young_list_rs_lengths() {
  SuspendibleThreadSetJoiner sts;
  G1CollectedHeap* g1h = G1CollectedHeap::heap();
  G1CollectorPolicy* g1p = g1h->g1_policy();
  if (g1p->adaptive_young_list_length()) {
    int regions_visited = 0;
    g1h->young_list()->rs_length_sampling_init();
    while (g1h->young_list()->rs_length_sampling_more()) {
      g1h->young_list()->rs_length_sampling_next();
      ++regions_visited;

      // we try to yield every time we visit 10 regions
      if (regions_visited == 10) {
        if (sts.should_yield()) {
          sts.yield();
          // we just abandon the iteration
          break;
        }
        regions_visited = 0;
      }
    }

    g1p->revise_young_list_target_length_if_necessary();
  }
}