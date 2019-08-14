class HeapRegionType VALUE_OBJ_CLASS_SPEC {
private:
  typedef enum {
	// 自由分区
    FreeTag       = 0,
    // young分区, 分为eden和survivor
    YoungMask     = 2,
    EdenTag       = YoungMask,
    SurvTag       = YoungMask + 1,
    // 大对象分区，分为头start和连续分区cont
    HumMask       = 4,
    HumStartsTag  = HumMask,
    HumContTag    = HumMask + 1,
    // old分区
    OldTag        = 8
  } Tag;
}

// 初始化计算HeapRegion的大小
void HeapRegion::setup_heap_region_size(size_t initial_heap_size, size_t max_heap_size) {
  uintx region_size = G1HeapRegionSize;
  // 没有设置指定的HeapRegion大小
  if (FLAG_IS_DEFAULT(G1HeapRegionSize)) {
	// 根据初始内存 initial_heap_size 和 最大内存 max_heap_size 获得平均值 average_heap_size
    size_t average_heap_size = (initial_heap_size + max_heap_size) / 2;
	// 根据HR的个数 HeapRegionBounds::target_number 得到每个HR的大小 region_size
	// 和HR的下限 HeapRegionBounds::min_size (JDK8里是1M)，取最大值，不能低于下限
    region_size = MAX2(average_heap_size / HeapRegionBounds::target_number(),
                       (uintx) HeapRegionBounds::min_size());
  }

  // region_size 每个HR的大小做幂次对齐
  int region_size_log = log2_long((jlong) region_size);
  // Recalculate the region size to make sure it's a power of
  // 2. This means that region_size is the largest power of 2 that's
  // <= what we've calculated so far.
  region_size = ((uintx)1 << region_size_log);

  // Now make sure that we don't go over or under our limits.
  // 确保每个HR的大小在下限1M和上限32M之间
  if (region_size < HeapRegionBounds::min_size()) {
    region_size = HeapRegionBounds::min_size();
  } else if (region_size > HeapRegionBounds::max_size()) {
    region_size = HeapRegionBounds::max_size();
  }

  // And recalculate the log.
  region_size_log = log2_long((jlong) region_size);

  // Now, set up the globals.
  guarantee(LogOfHRGrainBytes == 0, "we should only set it once");
  LogOfHRGrainBytes = region_size_log;

  guarantee(LogOfHRGrainWords == 0, "we should only set it once");
  LogOfHRGrainWords = LogOfHRGrainBytes - LogHeapWordSize;

  guarantee(GrainBytes == 0, "we should only set it once");
  // The cast to int is safe, given that we've bounded region_size by
  // MIN_REGION_SIZE and MAX_REGION_SIZE.
  GrainBytes = (size_t)region_size;

  guarantee(GrainWords == 0, "we should only set it once");
  GrainWords = GrainBytes >> LogHeapWordSize;
  guarantee((size_t) 1 << LogOfHRGrainWords == GrainWords, "sanity");
  // 根据每个分区的大小，计算CardTable的大小
  guarantee(CardsPerRegion == 0, "we should only set it once");
  CardsPerRegion = GrainBytes >> CardTableModRefBS::card_shift;
}

// 初始化新生代大小
G1YoungGenSizer::G1YoungGenSizer() : _sizer_kind(SizerDefaults), _adaptive_size(true),
        _min_desired_young_length(0), _max_desired_young_length(0) {
  if (FLAG_IS_CMDLINE(NewRatio)) {
    if (FLAG_IS_CMDLINE(NewSize) || FLAG_IS_CMDLINE(MaxNewSize)) {
	  // 同时设置NewRatio、NewSize和MaxNewSize，忽略NewRatio
      warning("-XX:NewSize and -XX:MaxNewSize override -XX:NewRatio");
    } else {
      _sizer_kind = SizerNewRatio;
      _adaptive_size = false;
      return;
    }
  }

  // 根据young相关参数计算HR个数
  if (FLAG_IS_CMDLINE(NewSize)) {
    _min_desired_young_length = MAX2((uint) (NewSize / HeapRegion::GrainBytes),
                                     1U);
    if (FLAG_IS_CMDLINE(MaxNewSize)) {
      _max_desired_young_length =
                             MAX2((uint) (MaxNewSize / HeapRegion::GrainBytes),
                                  1U);
      _sizer_kind = SizerMaxAndNewSize;
      _adaptive_size = _min_desired_young_length == _max_desired_young_length;
    } else {
      _sizer_kind = SizerNewSizeOnly;
    }
  } else if (FLAG_IS_CMDLINE(MaxNewSize)) {
    _max_desired_young_length =
                             MAX2((uint) (MaxNewSize / HeapRegion::GrainBytes),
                                  1U);
    _sizer_kind = SizerMaxNewSizeOnly;
  }
}

// 使用G1NewSizePercent计算新生代的最小值
uint G1YoungGenSizer::calculate_default_min_length(uint new_number_of_heap_regions) {
  uint default_value = (new_number_of_heap_regions * G1NewSizePercent) / 100;
  return MAX2(1U, default_value);
}

// 使用G1MaxNewSizePercent计算新生代的最大值
uint G1YoungGenSizer::calculate_default_max_length(uint new_number_of_heap_regions) {
  uint default_value = (new_number_of_heap_regions * G1MaxNewSizePercent) / 100;
  return MAX2(1U, default_value);
}

// 动态扩展内存，增加GC吞吐量
size_t G1CollectorPolicy::expansion_amount() {
  // recent_avg_pause_time_ratio 平均GC时间
  double recent_gc_overhead = recent_avg_pause_time_ratio() * 100.0;
  double threshold = _gc_overhead_perc;
  // GC时间与应用时间占比超过阈值，表示应用吞吐量不够？需要动态扩容
  if (recent_gc_overhead > threshold) {
    // We will double the existing space, or take
    // G1ExpandByPercentOfAvailable % of the available expansion
    // space, whichever is smaller, bounded below by a minimum
    // expansion (unless that's all that's left.)
	// 扩容下限 min_expand_bytes 是1M
    const size_t min_expand_bytes = 1*M;
    size_t reserved_bytes = _g1->max_capacity();
    size_t committed_bytes = _g1->capacity();
    size_t uncommitted_bytes = reserved_bytes - committed_bytes;
    size_t expand_bytes;
	// 一次扩展的比例 G1ExpandByPercentOfAvailable 默认20%
    size_t expand_bytes_via_pct =
      uncommitted_bytes * G1ExpandByPercentOfAvailable / 100;
	// 与当前已分配的committed_bytes比较，作为扩容上限
    expand_bytes = MIN2(expand_bytes_via_pct, committed_bytes);
    expand_bytes = MAX2(expand_bytes, min_expand_bytes);
    expand_bytes = MIN2(expand_bytes, uncommitted_bytes);

    return expand_bytes;
  } else {
    return 0;
  }
}