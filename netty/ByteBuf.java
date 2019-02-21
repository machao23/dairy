public abstract class ByteBuf implements ReferenceCounted, Comparable<ByteBuf> {

	// maxCapacity ，最大容量。当 writerIndex 写入超过 capacity 时，可自动扩容。
	// 每次扩容的大小，为 capacity 的 2 倍。当然，前提是不能超过 maxCapacity 大小。
	public abstract int maxCapacity(); // 最大容量

	public abstract ByteBufAllocator alloc(); // 分配器，用于创建 ByteBuf 对象。

	public abstract ByteBuf unwrap(); // 获得被包装( wrap )的 ByteBuf 对象。

	public abstract boolean isDirect(); // 是否 NIO Direct Buffer

	public abstract int readerIndex(); // 读取位置
	public abstract ByteBuf readerIndex(int readerIndex);
	public abstract int writerIndex(); // 写入位置
	public abstract ByteBuf writerIndex(int writerIndex);
	public abstract ByteBuf setIndex(int readerIndex, int writerIndex); // 设置读取和写入位置
	public abstract int readableBytes(); // 剩余可读字节数
	public abstract int writableBytes(); // 剩余可写字节数

	public abstract ByteBuf markReaderIndex(); // 标记读取位置
	public abstract ByteBuf resetReaderIndex();
	public abstract ByteBuf markWriterIndex(); // 标记写入位置
	public abstract ByteBuf resetWriterIndex();

	// 释放的方式，是通过复制可读段到 ByteBuf 的头部。所以，频繁释放会导致性能下降。
	public abstract ByteBuf discardReadBytes(); // 释放已读的字节空间

	// 清空字节空间。实际是修改 readerIndex=writerIndex=0，标记清空
	// 缺点：数据实际还存在，如果错误修改 writerIndex 时，会导致读到“脏”数据
	public abstract ByteBuf clear(); 。
}

public abstract class AbstractByteBuf extends ByteBuf {

	@Override
    public boolean isReadable() {
    	// 写入的数据比读过的多，就表示可读
        return writerIndex > readerIndex;
    }

    @Override
	public boolean isWritable() {
		// 还有空间未写入，表示可写
	    return capacity() > writerIndex;
	}

	final void ensureWritable0(int minWritableBytes) {
        if (minWritableBytes <= writableBytes()) {
            return;
        }

        // 超过最大上限，抛出 IndexOutOfBoundsException 异常
        if (minWritableBytes > maxCapacity - writerIndex) {
            throw new IndexOutOfBoundsException(String.format(
                    "writerIndex(%d) + minWritableBytes(%d) exceeds maxCapacity(%d): %s",
                    writerIndex, minWritableBytes, maxCapacity, this));
        }

        // 计算新的容量。默认情况下，2 倍扩容，并且不超过最大容量上限。
        int newCapacity = alloc().calculateNewCapacity(writerIndex + minWritableBytes, maxCapacity);
        capacity(newCapacity);
    }

    @Override
    public ByteBuf asReadOnly() {
        if (isReadOnly()) {
            return this;
        }
        // 返回的是新的 io.netty.buffer.ReadOnlyByteBuf 对象。
        // 并且，和原 ByteBuf 对象，共享 readerIndex 和 writerIndex 索引，以及相关的数据。
        return Unpooled.unmodifiableBuffer(this);
    }
}

abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {
	/**
	 * Recycler 处理器，用于回收对象
	 */
	private final Recycler.Handle<PooledByteBuf<T>> recyclerHandle;

	/**
	 * Chunk 对象. 
	 * 在 Netty 中，使用 Jemalloc 算法管理内存，而 Chunk 是里面的一种内存块。
	 * 在这里，我们可以理解 memory 所属的 PoolChunk 对象。
	 */
	protected PoolChunk<T> chunk;
	/**
	 * 从 Chunk 对象中分配的内存块所处的位置
	 */
	protected long handle;
	/**
	 * 内存空间。具体什么样的数据，通过子类设置泛型。
	 * Direct是ByteBuffer ，Heap是byte[]
	 */
	protected T memory;
	/**
	 * 使用 memory 的开始位置。
	 * 因为 memory 属性，可以被多个 ByteBuf 使用。每个 ByteBuf 使用范围为 [offset, maxLength) 。
	 * @see #idx(int)
	 */
	protected int offset;
	/**
	 * 目前使用 memory 的长度( 大小 )。
	 *
	 * @see #capacity()
	 */
	protected int length;

	/**
	 * maxLength 属性有什么用？表示占用 memory 的最大容量( 而不是 PooledByteBuf 对象的最大容量 )。
	 * 在写入数据超过 maxLength 容量时，会进行扩容，但是容量的上限，为 maxCapacity 。
	 */
	int maxLength;

	PoolThreadCache cache;
	/**
	 * 临时 ByteBuff 对象
	 *
	 * @see #internalNioBuffer()
	 */
	private ByteBuffer tmpNioBuf;
	/**
	 * ByteBuf 分配器对象
	 */
	private ByteBufAllocator allocator;

	// 每次在重用 PooledByteBuf 对象时，需要调用该方法，重置属性。
	final void reuse(int maxCapacity) {
        maxCapacity(maxCapacity);
        setRefCnt(1);
        setIndex0(0, 0);
        discardMarks();
    }

    @Override
	protected final void deallocate() {
	    if (handle >= 0) {
	        // 重置属性
	        final long handle = this.handle;
	        this.handle = -1;
	        memory = null;
	        tmpNioBuf = null;
	        // 释放内存回 Arena 中
	        chunk.arena.free(chunk, handle, maxLength, cache);
	        chunk = null;
	        // 回收对象
	        recycle();
	    }
	}

	private void recycle() {
	    recyclerHandle.recycle(this); // 回收对象
	}
}

final class PooledDirectByteBuf extends PooledByteBuf<ByteBuffer> {
	/**
	 * Recycler 对象
	 */
	private static final Recycler<PooledDirectByteBuf> RECYCLER = new Recycler<PooledDirectByteBuf>() {

	    @Override
	    protected PooledDirectByteBuf newObject(Handle<PooledDirectByteBuf> handle) {
	        return new PooledDirectByteBuf(handle, 0); // 真正创建 PooledDirectByteBuf 对象
	    }

	};

	static PooledDirectByteBuf newInstance(int maxCapacity) {
	    // 从 Recycler 的对象池中获得 PooledDirectByteBuf 对象
	    PooledDirectByteBuf buf = RECYCLER.get();
	    // 重置 PooledDirectByteBuf 的属性
	    buf.reuse(maxCapacity);
	    return buf;
	}

	// 获得临时 ByteBuf 对象( tmpNioBuf ) 
	@Override
	protected ByteBuffer newInternalNioBuffer(ByteBuffer memory) {
		// 调用 ByteBuffer#duplicate() 方法，复制一个 ByteBuffer 对象，共享里面的数据。
	    return memory.duplicate();
	}
}

final class PooledUnsafeDirectByteBuf extends PooledByteBuf<ByteBuffer> {

	/**
	 * 内存地址
	 */
	private long memoryAddress;

	@Override
	void init(PoolChunk<ByteBuffer> chunk, long handle, int offset, int length, int maxLength,
	          PoolThreadCache cache) {
	    super.init(chunk, handle, offset, length, maxLength, cache);
	    // 初始化内存地址
	    initMemoryAddress(); 
	}

	@Override
	void initUnpooled(PoolChunk<ByteBuffer> chunk, int length) {
	    super.initUnpooled(chunk, length);
	    // 初始化内存地址
	    initMemoryAddress();
	}

	private void initMemoryAddress() {
	    memoryAddress = PlatformDependent.directBufferAddress(memory) + offset; // <2>
	}
}