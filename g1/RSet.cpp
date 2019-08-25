class OtherRegionsTable VALUE_OBJ_CLASS_SPEC {
  // 粗粒度：位图表示，每一位表示对应的分区有引用到该分区
  // 细粒度PRT的大小也超过阈值，即非常多的region引用了这个region，位图由存放多个card退化为存放多个分区
  BitMap      _coarse_map;
  // 细粒度PRT：PRT指针的指针，简单理解为PRT指针的数组，数组长度动态计算
  // 稀疏表指定key的card数量超过阈值，考虑到空间优化，使用位图存放多个card
  PerRegionTable** _fine_grain_regions;
  // 稀疏PRT：hash表; key是引用这个region的region id，value是引用region的card数组
  // 因为是3个容器里最细粒度的，适用于被很少region引用的region，所以叫“稀疏”
  SparsePRT   _sparse_table;
}

// 把引用者对象对应的卡表地址存放到RSet里，在RSet里记录一个区域到这个对象所在分区的引用
void OtherRegionsTable::add_reference(OopOrNarrowOopStar from, int tid) {
  // 先从缓存里读取
  if (FromCardCache::contains_or_replace((uint)tid, cur_hrm_ind, from_card)) {
    if (G1TraceHeapRegionRememberedSet) {
      gclog_or_tty->print_cr("  from-card cache hit.");
    }
    assert(contains_reference(from), "We just added it!");
    return;
  }

  // If the region is already coarsened, return.
  // 如果已经是粗粒度，也就是说RSet里记录的是引用者对象的分区而不是卡表地址，可以直接返回
  // 因为已经退无可退了
  if (_coarse_map.at(from_hrm_ind)) {
    if (G1TraceHeapRegionRememberedSet) {
      gclog_or_tty->print_cr("  coarse map hit.");
    }
    assert(contains_reference(from), "We just added it!");
    return;
  }

  // Otherwise find a per-region table to add it to.
  // 由粗往细开始找，粗粒度找不到找细粒度
  size_t ind = from_hrm_ind & _mod_max_fine_entries_mask;
  PerRegionTable* prt = find_region_table(ind, from_hr);
  if (prt == NULL) {
	// 可能有多个线程同时访问一个分区的RSet信息，需要加锁
    MutexLockerEx x(_m, Mutex::_no_safepoint_check_flag);
    // Confirm that it's really not there...
	// 最后从稀疏表里找
    prt = find_region_table(ind, from_hr);
    if (prt == NULL) {
	  // 三个容器都没有找到，开始添加
      // 使用稀疏矩阵存储
      uintptr_t from_hr_bot_card_index =
        uintptr_t(from_hr->bottom())
          >> CardTableModRefBS::card_shift;
      CardIdx_t card_index = from_card - from_hr_bot_card_index;
      assert(0 <= card_index && (size_t)card_index < HeapRegion::CardsPerRegion,
             "Must be in range.");
      if (G1HRRSUseSparseTable &&
          _sparse_table.add_card(from_hrm_ind, card_index)) {
        if (G1RecordHRRSOops) {
          HeapRegionRemSet::record(hr(), from);
          if (G1TraceHeapRegionRememberedSet) {
            gclog_or_tty->print("   Added card " PTR_FORMAT " to region "
                                "[" PTR_FORMAT "...) for ref " PTR_FORMAT ".\n",
                                align_size_down(uintptr_t(from),
                                                CardTableModRefBS::card_size),
                                hr()->bottom(), from);
          }
        }
        if (G1TraceHeapRegionRememberedSet) {
          gclog_or_tty->print_cr("   added card to sparse table.");
        }
        assert(contains_reference_locked(from), "We just added it!");
        return;
      } else {
        if (G1TraceHeapRegionRememberedSet) {
          gclog_or_tty->print_cr("   [tid %d] sparse table entry "
                        "overflow(f: %d, t: %u)",
                        tid, from_hrm_ind, cur_hrm_ind);
        }
      }
	  // 细粒度卡表已经满了，删除所有的PRT，然后把它们放入粗粒度卡表，这是针对分区的BitMap
      if (_n_fine_entries == _max_fine_entries) {
        prt = delete_region_table();
        // There is no need to clear the links to the 'all' list here:
        // prt will be reused immediately, i.e. remain in the 'all' list.
        prt->init(from_hr, false /* clear_links_to_all_list */);
      } else {
		// 稀疏矩阵已经满了，需要分配一个新的细粒度卡表来存储
        prt = PerRegionTable::alloc(from_hr);
        link_to_all(prt);
      }

      PerRegionTable* first_prt = _fine_grain_regions[ind];
      prt->set_collision_list_next(first_prt);
      _fine_grain_regions[ind] = prt;
      _n_fine_entries++;
      // 把稀疏矩阵数据里的数据迁移到细粒度卡表中，添加成功后删除稀疏矩阵
      if (G1HRRSUseSparseTable) {
        // Transfer from sparse to fine-grain.
        SparsePRTEntry *sprt_entry = _sparse_table.get_entry(from_hrm_ind);
        assert(sprt_entry != NULL, "There should have been an entry");
        for (int i = 0; i < SparsePRTEntry::cards_num(); i++) {
          CardIdx_t c = sprt_entry->card(i);
          if (c != SparsePRTEntry::NullEntry) {
            prt->add_card(c);
          }
        }
        // Now we can delete the sparse entry.
        bool res = _sparse_table.delete_entry(from_hrm_ind);
        assert(res, "It should have been there.");
      }
    }
    assert(prt != NULL && prt->hr() == from_hr, "consequence");
  }
  // Note that we can't assert "prt->hr() == from_hr", because of the
  // possibility of concurrent reuse.  But see head comment of
  // OtherRegionsTable for why this is OK.
  assert(prt != NULL, "Inv");
  // 添加引用
  prt->add_reference(from);
}