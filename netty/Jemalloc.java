final class PoolChunk<T> implements PoolChunkMetric {
	final PoolArena<T> arena;

	// 内存空间
	// Direct ByteBuffer 和 byte[] 字节数组。
	final T memory;

	// 是否非池化
	// 默认情况下，对于 分配 16M 以内的内存空间时，Netty 会分配一个 Normal 类型的 Chunk 块。
	// 并且，该 Chunk 块在使用完后，进行池化缓存，重复使用。
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