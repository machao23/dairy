typedef struct dict {
    dictType *type;
    void *privdata;
    dictht ht[2]; // 两个hash表dictht，交替使用，用以rehash操作
    long rehashidx; /* 是否在rehash
	rehashing not in progress if rehashidx == -1 */
    unsigned long iterators; /* number of iterators currently running */
} dict;

/* Expand the hash table if needed */
static int _dictExpandIfNeeded(dict *d)
{
    /* Incremental rehashing already in progress. Return. */
    if (dictIsRehashing(d)) return DICT_OK;

    /* If the hash table is empty expand it to the initial size. */
	// Hash表为空，扩为初始化大小
    if (d->ht[0].size == 0) return dictExpand(d, DICT_HT_INITIAL_SIZE);

    /* If we reached the 1:1 ratio, and we are allowed to resize the hash
     * table (global setting) or we should avoid it but the ratio between
     * elements/buckets is over the "safe" threshold, we resize doubling
     * the number of buckets. */
	// hash表里的元素个数used 超过hash表大小size
    if (d->ht[0].used >= d->ht[0].size &&
		// dict_can_rehash 满足rehash条件：没有执行RBD快照和没有进行AOF重写
        (dict_can_resize ||
		// 或者元素个数used 已经是当前大小size的 5倍（dict_force_resize_ration值），过载严重
         d->ht[0].used/d->ht[0].size > dict_force_resize_ratio))
    {
		// 扩容大小是当前使用大小used的2倍
        return dictExpand(d, d->ht[0].used*2);
    }
    return DICT_OK;
}

// 扩容大小是2的N次方，找到合适的N值满足当前容量
static unsigned long _dictNextPower(unsigned long size)
{
    unsigned long i = DICT_HT_INITIAL_SIZE;

    if (size >= LONG_MAX) return LONG_MAX + 1LU;
    while(1) {
        if (i >= size)
            return i;
		// 扩容大小i没有满足目标大小size，就乘以2
        i *= 2;
    }
}

/* Performs N steps of incremental rehashing. Returns 1 if there are still
 * keys to move from the old to the new hash table, otherwise 0 is returned.
 *
 * Note that a rehashing step consists in moving a bucket (that may have more
 * than one key as we use chaining) from the old to the new hash table, however
 * since part of the hash table may be composed of empty spaces, it is not
 * guaranteed that this function will rehash even a single bucket, since it
 * will visit at max N*10 empty buckets in total, otherwise the amount of
 * work it does would be unbound and the function may block for a long time. */
 // 渐进式rehash，执行键拷贝，入参是全局hash表d（包括了ht[0]和ht[1]） 和 需要进行键拷贝的非空bucket数量 n 
 // bucket数量 != used键数量，因为1个bucket可能有N个键链表，也可能是个空bucket
 // rehash会阻塞主线程，所以使用渐进式减少其他请求的影响（那会不会渐进式部分rehash之间穿插对这个key的访问，出现问题？）
 // 返回1表示rehash没有全部完成，等待下一次rehash
int dictRehash(dict *d, int n) {
    int empty_visits = n*10; /* Max number of empty buckets to visit. */
    if (!dictIsRehashing(d)) return 0;
	
	// 循环一次执行要进行键拷贝的bucket数量n，且ht[0]所有键used都拷贝完成
	//（拷贝完成会减少used数量，因为redis是单线程所以不会有并发问题）
    while(n-- && d->ht[0].used != 0) {
        dictEntry *de, *nextde;

        while(d->ht[0].table[d->rehashidx] == NULL) {
			// 跳过空的bucket
            d->rehashidx++;
			// empty_visits表示已经检查过的空bucket，避免连续空bucket太多导致阻塞主线程
            if (--empty_visits == 0) return 1;
        }
        de = d->ht[0].table[d->rehashidx];
        /* Move all the keys in this bucket from the old to the new hash HT */
		// 遍历并迁移bucket里链表？
        while(de) {
            uint64_t h;

            nextde = de->next;
            /* Get the index in the new hash table */
            h = dictHashKey(d, de->key) & d->ht[1].sizemask;
            de->next = d->ht[1].table[h];
            d->ht[1].table[h] = de;
            d->ht[0].used--;
            d->ht[1].used++;
            de = nextde;
        }
        d->ht[0].table[d->rehashidx] = NULL;
		// 迁移完成一个bucket，迁移进度rehashidx加1
		// rehashidx等于0，表示对ht[0]里第一个bucket迁移，rehashidx等于1，表示对第二个bucket迁移，以此类推
        d->rehashidx++;
    }

    /* Check if we already rehashed the whole table... */
	// 判断ht[0]的数据是否迁移完成
    if (d->ht[0].used == 0) {
		// 释放ht[0]
        zfree(d->ht[0].table);
		// ht[0]指向ht[1]
        d->ht[0] = d->ht[1];
		// 重置ht[1]的大小为0
        _dictReset(&d->ht[1]);
		// rehashidx表示rehash结束
        d->rehashidx = -1;
		// 返回0，表示rehash结束
        return 0;
    }

    /* More to rehash... */
	// ht[0].used > 0，表示还没有全部迁移完成，返回1
    return 1;
}