final class PoolChunk<T> implements PoolChunkMetric {
	final PoolArena<T> arena;

	// 内存空间
	// Direct ByteBuffer 和 byte[] 字节数组。
	final T memory;

	// 是否非池化
	// 池化情况下，对于 分配 16M 以内的内存空间时，Netty 会分配一个 Normal 类型的 Chunk 块。 
	// 并且，该 Chunk 块在使用完后，进行池化缓存，重复使用。

	// 非池化情况下，对于分配 16M 以上的内存空间时，Netty 会分配一个 Huge 类型的特殊的 Chunk 块。
	// 并且，由于 Huge 类型的 Chunk 占用内存空间较大，比较特殊，所以该 Chunk 块在使用完后，立即释放，不进行重复使用。
	final boolean unpooled;

	/**
	 * 分配信息满二叉树
	 * 值有3种状态：
	 * 等于高度值，表示该节点代表的内存没有被分配
	 * 最大高度 >= memoryMap[id] > depthMap[id] ，至少有一个子节点被分配，不能再分配该高度满足的内存，但可以根据实际分配较小一些的内存。
	 * memoryMap[id] = 最大高度 + 1 ，该节点及其子节点已被完全分配，没有剩余空间。
	 */
	private final byte[] memoryMap;

	/**
	 * 高度信息满二叉树
	 *
	 * index 为节点编号
	 */
	private final byte[] depthMap;

	private final PoolSubpage<T>[] subpages;

	/**
	 * 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。
	 *
	 * Used to determine if the requested capacity is equal to or greater than pageSize.
	 */
	private final int subpageOverflowMask;

	/**
	 * Page 大小，默认 8KB = 8192B
	 */
	private final int pageSize;

	/**
	 * 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192B 。
	 *
	 * 具体用于计算指定容量所在满二叉树的层级，每一层表示2的n次方，从0开始
	 */
	private final int pageShifts;

	/**
	 * 满二叉树的高度。默认为 11 。 层高时从0开始
	 */
	private final int maxOrder;

	/**
	 * Chunk 内存块占用大小。默认为 16M = 16 * 1024  。
	 */
	private final int chunkSize;
	/**
	 * log2 {@link #chunkSize} 的结果。默认为 log2( 16*1024*1024 = 16M) = 24 。
	 */
	private final int log2ChunkSize;

	/**
	 * 可分配 {@link #subpages} 的数量，即数组大小。
	 * 默认为 1 << maxOrder = 1 << 11（maxOrder默认是11） = 2048（2的11次方） 。
	 */
	private final int maxSubpageAllocs;
	/**
	 * 标记节点不可用。默认为 maxOrder + 1 = 12 。
	 *
	 * Used to mark memory as unusable
	 */
	private final byte unusable;

	/**
	 * 剩余可用字节数
	 */
	private int freeBytes;

	/**
	 * 所属 PoolChunkList 对象
	 */
	PoolChunkList<T> parent;
	/**
	 * 上一个 Chunk 对象
	 */
	PoolChunk<T> prev;
	/**
	 * 下一个 Chunk 对象
	 */
	PoolChunk<T> next;

	PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
		// 池化的构造方法
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;

        // 标记节点不可用，即该节点表示的内存已经被分配了。默认为 maxOrder + 1 = 12
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);

        // 默认，-8192 。对于 -8192 的二进制，除了首 bits 为 1 ，其它都为 0 。
        // 这样，对于小于 8K 字节的申请，求 subpageOverflowMask & length 都等于 0 ；
        // 相当于说，做了 if ( length < pageSize ) 的计算优化。
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        // 即 1<<11 = 2048
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        // 表示二叉树的数组大小是 2048 << 1 = 4096
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];

        // 满二叉树的节点编号是从 1 开始。省略 0 是因为这样更容易计算父子关系：子节点加倍，父节点减半，
        // 即父节点n的2个子节点分别是2n和2n+1
        // 例如：512 的子节点为 1024( 512 * 2 )和 1025( 512 * 2 + 1 )。
        int memoryMapIndex = 1;
        // 变量d表示层高
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                // 初始值都是层高d
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        // 创建subpages数组，进一步切分page
        subpages = newSubpageArray(maxSubpageAllocs);
    }

    long allocate(int normCapacity) {
    	// 当申请的 normCapacity 大于等于 Page 大小时，调用 #allocateRun(int normCapacity) 方法，分配 Page 内存块。
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            return allocateRun(normCapacity);
        } else {
        	// 否则分配subPage
            return allocateSubpage(normCapacity);
        }
    }

    private long allocateSubpage(int normCapacity) {
        // 获得对应内存规格的 Subpage 双向链表的 head 节点
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        // 加锁，分配过程会修改双向链表的结构，会存在多线程的情况。
        synchronized (head) {
        	// 获得最底层的一个节点。Subpage 只能使用二叉树的最底层的节点。
            int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
            int id = allocateNode(d);

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

           	// 减少剩余可用字节数
            freeBytes -= pageSize;
            // 获得节点对应的 subpages 数组的编号
            int subpageIdx = subpageIdx(id);
            // 获得节点对应的 subpages 数组的 PoolSubpage 对象
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) { // 不存在，则进行创建 PoolSubpage 对象
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity); // 存在，则重新初始化 PoolSubpage 对象
            }
            // 分配 PoolSubpage 内存块。
            return subpage.allocate();
        }
    }

    // 分配d层里的某个节点
    private int allocateNode(int d) {
        int id = 1;
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        if (val > d) { // unusable
            return -1;
        }
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {
                id ^= 1;
                val = value(id);
            }
        }
        // 更新待分配节点为不可用
        setValue(id, unusable); // mark as unusable
        // 更新获得的节点的祖先
        updateParentsAlloc(id);
        return id;
    }
}

// 虽然，PoolSubpage 类的命名是“Subpage”，实际描述的是，Page 切分为多个 Subpage 内存块的分配情况。
// 即这个类表示的其实是一个被切分成多个subPage的Page用来分配小对象的，因为有些Page分配给超过8K的大对象时候s是不能被切分的
final class PoolSubpage<T> implements PoolSubpageMetric {
	/**
	 * 所属 PoolChunk 对象
	 */
	final PoolChunk<T> chunk;
	/**
	 * 在 {@link PoolChunk#memoryMap} 的节点编号
	 */
	private final int memoryMapIdx;
	/**
	 * 在 Chunk 中，偏移字节量
	 *
	 * @see PoolChunk#runOffset(int) 
	 */
	private final int runOffset;
	/**
	 * Page 大小 {@link PoolChunk#pageSize}
	 */
	private final int pageSize;

	/**
	 * Subpage 分配信息数组
	 *
	 * 每个 long 的 bits 位代表一个 Subpage 是否分配。
	 * 因为 PoolSubpage 可能会超过 64 个( long 的 bits 位数 )，所以使用数组。
	 *   例如：Page 默认大小为 8KB ，Subpage 默认最小为 16 B ，所以一个 Page 最多可包含 8 * 1024 / 16 = 512 个 Subpage 。
	 *        因此，bitmap 数组大小为 512 / 64 = 8 。
	 * 另外，bitmap 的数组大小，使用 {@link #bitmapLength} 来标记。或者说，bitmap 数组，默认按照 Subpage 的大小为 16B 来初始化。
	 *    为什么是这样的设定呢？因为 PoolSubpage 可重用，通过 {@link #init(PoolSubpage, int)} 进行重新初始化。
	 */
	private final long[] bitmap;

	/**
	 * 双向链表，前一个 PoolSubpage 对象
	 */
	PoolSubpage<T> prev;
	/**
	 * 双向链表，后一个 PoolSubpage 对象
	 */
	PoolSubpage<T> next;

	/**
	 * 是否未销毁
	 */
	boolean doNotDestroy;
	/**
	 * 每个 Subpage 的占用内存大小
	 */
	int elemSize;
	/**
	 * 总共 Subpage 的数量
	 */
	private int maxNumElems;
	/**
	 * {@link #bitmap} 长度
	 */
	private int bitmapLength;
	/**
	 * 下一个可分配 Subpage 的数组位置
	 */
	private int nextAvail;
	/**
	 * 剩余可用 Subpage 的数量
	 */
	private int numAvail;
}

final class PoolChunkList<T> implements PoolChunkListMetric {
	/**
	 * 所属 PoolArena 对象
	 */
	private final PoolArena<T> arena;
	/**
	 * 下一个 PoolChunkList 对象
	 * 也就是说，PoolChunkList 除了自身有一条双向链表外，PoolChunkList 和 PoolChunkList 之间也形成了一条双向链表
	 */
	private final PoolChunkList<T> nextList;
	/**
	 * Chunk 最小内存使用率
	 */
	private final int minUsage;
	/**
	 * Chunk 最大内存使用率
	 * 当 Chunk 分配的内存率超过 maxUsage 时，从当前 PoolChunkList 节点移除，添加到下一个 PoolChunkList 节点
	 */
	private final int maxUsage;
	/**
	 * 每个 Chunk 最大可分配的容量
	 *
	 * @see #calculateMaxCapacity(int, int) 方法
	 */
	private final int maxCapacity;
	/**
	 * PoolChunk 头节点
	 */
	private PoolChunk<T> head;

	/**
	 * 前一个 PoolChunkList 对象
	 */
	// This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.
	private PoolChunkList<T> prevList;
}

abstract class PoolArena<T> implements PoolArenaMetric {

	/**
	 * 是否支持 Unsafe 操作
	 */
	static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

	/**
	 * 内存分类
	 */
	enum SizeClass {
	    Tiny,
	    Small,
	    Normal

	    // 还有一个隐藏的，Huge
	}

	/**
	 * {@link #tinySubpagePools} 数组的大小
	 *
	 * 默认为 32
	 */
	static final int numTinySubpagePools = 512 >>> 4;

	/**
	 * 所属 PooledByteBufAllocator 对象
	 */
	final PooledByteBufAllocator parent;

	/**
	 * 满二叉树的高度。默认为 11 。
	 */
	private final int maxOrder;
	/**
	 * Page 大小，默认 8KB = 8192B
	 */
	final int pageSize;
	/**
	 * 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192 。
	 */
	final int pageShifts;
	/**
	 * Chunk 内存块占用大小。默认为 16M = 16 * 1024  。
	 */
	final int chunkSize;
	/**
	 * 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。
	 *
	 * Used to determine if the requested capacity is equal to or greater than pageSize.
	 */
	final int subpageOverflowMask;

	/**
	 * {@link #smallSubpagePools} 数组的大小
	 *
	 * 默认为 23
	 */
	final int numSmallSubpagePools;

	/**
	 * 对齐基准
	 */
	final int directMemoryCacheAlignment;
	/**
	 * {@link #directMemoryCacheAlignment} 掩码
	 */
	final int directMemoryCacheAlignmentMask;

	/**
	 * tiny 类型的 PoolSubpage 数组
	 *
	 * 数组的每个元素，都是双向链表
	 */
	private final PoolSubpage<T>[] tinySubpagePools;
	/**
	 * small 类型的 SubpagePools 数组
	 *
	 * 数组的每个元素，都是双向链表
	 */
	private final PoolSubpage<T>[] smallSubpagePools;

	// PoolChunkList 之间的双向链表
	private final PoolChunkList<T> q050;
	private final PoolChunkList<T> q025;
	private final PoolChunkList<T> q000;
	private final PoolChunkList<T> qInit;
	private final PoolChunkList<T> q075;
	private final PoolChunkList<T> q100;

	/**
	 * PoolChunkListMetric 数组
	 */
	private final List<PoolChunkListMetric> chunkListMetrics;

	// Metrics for allocations and deallocations
	/**
	 * 分配 Normal 内存块的次数
	 */
	private long allocationsNormal;
	// We need to use the LongCounter here as this is not guarded via synchronized block.
	/**
	 * 分配 Tiny 内存块的次数
	 */
	private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
	/**
	 * 分配 Small 内存块的次数
	 */
	private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
	/**
	 * 分配 Huge 内存块的次数
	 */
	private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
	/**
	 * 正在使用中的 Huge 内存块的总共占用字节数
	 */
	private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

	/**
	 * 释放 Tiny 内存块的次数
	 */
	private long deallocationsTiny;
	/**
	 * 释放 Small 内存块的次数
	 */
	private long deallocationsSmall;
	/**
	 * 释放 Normal 内存块的次数
	 */
	private long deallocationsNormal;

	/**
	 * 释放 Huge 内存块的次数
	 */
	// We need to use the LongCounter here as this is not guarded via synchronized block.
	private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

	/**
	 * 该 PoolArena 被多少线程引用的计数器
	 */
	// Number of thread caches backed by this arena.
	final AtomicInteger numThreadCaches = new AtomicInteger();	

	PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
		// 从RECYCLE池子里复用ByteBuf
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    // 分配内存块给 PooledByteBuf 对象
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
    	// 标准化请求分配的容量
        final int normCapacity = normalizeCapacity(reqCapacity);
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;
            } else {
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                if (s != head) {
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    long handle = s.allocate();
                    assert handle >= 0;
                    s.chunk.initBufWithSubpage(buf, handle, reqCapacity);
                    incTinySmallAllocation(tiny);
                    return;
                }
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
            }

            incTinySmallAllocation(tiny);
            return;
        }
        if (normCapacity <= chunkSize) {
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        } else {
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);
        }
    }
}