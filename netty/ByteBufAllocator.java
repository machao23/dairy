public final class ByteBufUtil {
	
	static final ByteBufAllocator DEFAULT_ALLOCATOR;

	static {
        allocType = allocType.toLowerCase(Locale.US).trim();

        ByteBufAllocator alloc = UnpooledByteBufAllocator.DEFAULT;
        // 默认 ByteBufAllocator 对象，通过 ByteBufUtil.DEFAULT_ALLOCATOR 中获得
        DEFAULT_ALLOCATOR = alloc;
    }
}

public interface ByteBufAllocator {

	// 创建一个ByteBuf对象
	// 具体创建的是 Heap ByteBuf 还是 Direct ByteBuf ，由实现类决定。
	ByteBuf buffer();

	// 创建一个用于 IO 操作的 ByteBuf 对象。倾向于 Direct ByteBuf ，因为对于 IO 操作来说，性能更优。
	ByteBuf ioBuffer();

	// 在 ByteBuf 扩容时，计算新的容量，该容量的值在 [minNewCapacity, maxCapacity] 范围内。
	int calculateNewCapacity(int minNewCapacity, int maxCapacity);
}

// 为 PooledByteBufAllocator 和 UnpooledByteBufAllocator 提供公共的方法。
public abstract class AbstractByteBufAllocator implements ByteBufAllocator {
	/**
	 * 是否倾向创建 Direct ByteBuf
	 */
	private final boolean directByDefault;
	/**
	 * 空 ByteBuf 缓存
	 */
	private final ByteBuf emptyBuf;

	@Override
	public ByteBuf buffer() {
	    if (directByDefault) {
	        return directBuffer();
	    }
	    return heapBuffer();
	}

	/**
	 * 默认容量大小
	 */
	static final int DEFAULT_INITIAL_CAPACITY = 256;

	@Override
	public ByteBuf ioBuffer() {
		// 根据是否支持 Unsafe 操作的情况，创建direct
	    if (PlatformDependent.hasUnsafe()) {
	        return directBuffer(DEFAULT_INITIAL_CAPACITY);
	    }
	    return heapBuffer(DEFAULT_INITIAL_CAPACITY);
	}
}

// 基于内存池的 ByteBuf 的分配器；而 PooledByteBufAllocator 的内存池，是基于 Jemalloc 算法进行分配管理
public class PooledByteBufAllocator extends AbstractByteBufAllocator implements ByteBufAllocatorMetricProvider {

	@Override
	protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
	    // 获得线程的 PoolThreadCache 对象
	    PoolThreadCache cache = threadCache.get();
	    PoolArena<byte[]> heapArena = cache.heapArena;

	    // 从 heapArena(类型是poolArena) 中，分配 Heap PooledByteBuf 对象，基于池化
	    final ByteBuf buf;
	    if (heapArena != null) {
	        buf = heapArena.allocate(cache, initialCapacity, maxCapacity);
	    } 

	    return toLeakAwareBuffer(buf);
	}
}