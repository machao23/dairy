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

public abstract class AbstractReferenceCountedByteBuf extends AbstractByteBuf {
	// 为什么不直接用 AtomicInteger ？因为 ByteBuf 对象很多，如果都把 int 包一层 AtomicInteger 花销较大，
	// 而AtomicIntegerFieldUpdater 只需要一个全局的静态变量。
	// 其实就是原子地去更新一个原生类型的updater
	private static final AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> refCntUpdater =
            AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCountedByteBuf.class, "refCnt");

    private volatile int refCnt;

    private boolean release0(int decrement) {
        int oldRef = refCntUpdater.getAndAdd(this, -decrement);
        if (oldRef == decrement) {
        	// 真正的释放。这是个抽象方法，需要子类去实现。
            deallocate();
            return true;
        } 
        return false;
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

public interface ReferenceCounted {

    /**
     * 获得引用计数
     */
    int refCnt();

    /**
     * 增加引用计数 1
     */
    ReferenceCounted retain();

    /**
     * 主动记录一个 hint 给 ResourceLeakDetector ，方便我们在发现内存泄露有更多的信息进行排查。
     * 出于调试目的,用一个额外的任意的(arbitrary)信息记录这个对象的当前访问地址. 如果这个对象被检测到泄露了, 这个操作记录的信息将通过ResourceLeakDetector 提供.
     */
    ReferenceCounted touch(Object hint);

    /**
     * 减少引用计数 1 。
     * 当引用计数为 0 时，释放
     */
    boolean release();
}

// 把一个ByteBuf包装成可以检测泄露的ByteBuf
class SimpleLeakAwareByteBuf extends WrappedByteBuf {
	// 被检测的目标
	private final ByteBuf trackedByteBuf;
    final ResourceLeakTracker<ByteBuf> leak;

    // 如果被包装的ByteBuf被正确释放时会调用release
    @Override
    public boolean release() {
        if (super.release()) {
            closeLeak();
            return true;
        }
        return false;
    }

    private void closeLeak() {
        // Close the ResourceLeakTracker with the tracked ByteBuf as argument. This must be the same that was used when
        // calling DefaultResourceLeak.track(...).
        // 实质是调用ResourceLeakTracker.DefaultResourceLeak.close
        boolean closed = leak.close(trackedByteBuf);
        assert closed;
    }
}

// 内存泄露检测器
public class ResourceLeakDetector<T> {
	/**
	 * 每个 DefaultResourceLeak 记录的 Record 数量
	 */
	private static final int TARGET_RECORDS;

	/**
	 * 内存泄露检测等级
	 */
	private static Level level;

	/**
	 * 已汇报的内存泄露的资源类型的集合
	 */
	private final ConcurrentMap<String, Boolean> reportedLeaks = PlatformDependent.newConcurrentHashMap();

	/**
	 * 采集评率
	 */
	private final int samplingInterval

	// 因为 Java 没有自带的 ConcurrentSet ，所以只好使用使用 ConcurrentMap 。也就是说，value 属性实际没有任何用途。
	private final ConcurrentMap<DefaultResourceLeak<?>, LeakEntry> allLeaks = PlatformDependent.newConcurrentHashMap();

	// 跟踪不需要的引用即已经被标记成垃圾的弱引用对象 DefaultResourceLeak
	private final ReferenceQueue<Object> refQueue = new ReferenceQueue<Object>();

	// 给指定资源( 例如 ByteBuf 对象 )创建一个检测它是否泄漏的 ResourceLeakTracker 对象。
	private DefaultResourceLeak track0(T obj) {
        Level level = ResourceLeakDetector.level;

        if (level.ordinal() < Level.PARANOID.ordinal()) {
        	// SIMPLE 和 ADVANCED 级别时，随机，概率为 1 / samplingInterval ，创建 DefaultResourceLeak 对象。
        	// 默认情况下 samplingInterval = 128 ，约等于 1%
            if ((PlatformDependent.threadLocalRandom().nextInt(samplingInterval)) == 0) {
                reportLeak();
                return new DefaultResourceLeak(obj, refQueue, allLeaks);
            }
            // 大部分情况下不采集返回null
            return null;
        }
        reportLeak();
        return new DefaultResourceLeak(obj, refQueue, allLeaks);
    }

    // 检测是否有内存泄露。若有，则进行汇报。
    private void reportLeak() {

    	// 循环引用队列，直到为空
        // Detect and report previous leaks.
        for (;;) {
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            if (ref == null) {
                break;
            }
            // 从allLeaks里清理这个DefaultResourceLeak，并返回是否内存泄露。
            // 如果未泄露，就直接 continue
            if (!ref.dispose()) {
                continue;
            }

            // 获得 Record 日志
            String records = ref.toString();
            // 相同 Record 日志( 即“创建的地方与访问路径一致” )，只汇报一次
            if (reportedLeaks.putIfAbsent(records, Boolean.TRUE) == null) {
                if (records.isEmpty()) {
                    reportUntracedLeak(resourceType);
                } else {
                    reportTracedLeak(resourceType, records);
                }
            }
        }
    }

    // 内部类，内存泄露追踪器
    private static final class DefaultResourceLeak<T> extends WeakReference<Object> implements ResourceLeakTracker<T>, ResourceLeak {

    	// Record 链的头节点
    	// 实际上，head 是尾节点，即最后( 新 )的一条 Record 。
    	private volatile Record head;

    	
    	DefaultResourceLeak(
                Object referent,
                ReferenceQueue<Object> refQueue,
                ConcurrentMap<DefaultResourceLeak<?>, LeakEntry> allLeaks) {

    		// 当你在构造WeakReference时传入一个ReferenceQueue对象（即这里的refQueue）
	    	// 当该引用指向的对象(即referent，ByteBuf对象）被标记为垃圾的时候（ByteBuf对象被正确释放），
	    	// 这个引用对象（ResourceLeakTracker ）会自动地加入到引用队列里面。
	    	// 接下来，你就可以在固定的周期，处理传入的引用队列，比如做一些清理工作来处理这些没有用的引用对象。
            super(referent, refQueue);

            assert referent != null;

            // Store the hash of the tracked object to later assert it in the close(...) method.
            // It's important that we not store a reference to the referent as this would disallow it from
            // be collected via the WeakReference.
            trackedHash = System.identityHashCode(referent);
            // 将自己添加到 allLeaks 中。
            allLeaks.put(this, LeakEntry.INSTANCE);
            // 默认创建尾节点
            // Create a new Record so we always have the creation stacktrace included.
            headUpdater.set(this, new Record(Record.BOTTOM));
            this.allLeaks = allLeaks;
        }

        // 创建 Record 对象，添加到 head 链中。
        private void record0(Object hint) {
            // Check TARGET_RECORDS > 0 here to avoid similar check before remove from and add to lastRecords
            if (TARGET_RECORDS > 0) {
                Record oldHead;
                Record prevHead;
                Record newHead;
                boolean dropped;
                do {
                	// 通过 headUpdater 获得 head 属性，若为 null 时，说明 DefaultResourceLeak 已经关闭。
                    if ((prevHead = oldHead = headUpdater.get(this)) == null) {
                        // already closed.
                        return;
                    }
                    final int numElements = oldHead.pos + 1;
                    if (numElements >= TARGET_RECORDS) {
                    	// 当前 DefaultResourceLeak 对象所拥有的 Record 数量超过 TARGET_RECORDS 时，随机丢弃当前 head 节点的数据。
                    	// 就是说，尽量保留老的 Record 节点。这是为什么呢?越是老( 开始 )的 Record 节点，越有利于排查问题。
                    	// 另外，随机丢弃的的概率，按照 1 - (1 / 2^n） 几率，越来越大。
                        final int backOffFactor = Math.min(numElements - TARGET_RECORDS, 30);
                        if (dropped = PlatformDependent.threadLocalRandom().nextInt(1 << backOffFactor) != 0) {
                            prevHead = oldHead.next;
                        }
                    } else {
                        dropped = false;
                    }
                    // 创建新 Record 对象，作为头节点，指向原头节点。
                    // 这也是为什么说，“实际上，head 是尾节点，即最后( 新 )的一条 Record”。
                    newHead = hint != null ? new Record(prevHead, hint) : new Record(prevHead);
                } while (!headUpdater.compareAndSet(this, oldHead, newHead)); // 通过 CAS 的方式，修改新创建的 Record 对象为头节点。
                if (dropped) {
                    droppedRecordsUpdater.incrementAndGet(this);
                }
            }
        }

        // 清理，并返回是否内存泄露
		boolean dispose() {
		    // 清理 referent 的引用
		    clear();
		    // 移除出 allLeaks 。移除成功，意味着内存泄露。
		    return allLeaks.remove(this, LeakEntry.INSTANCE);
		}

		// 被检测的ByteBuf正确release会最终调用到这里，把自己从allLeaks里移除
		// 所以之前reportLeak里遍历队列时没有被移除的都是内存泄露的ByteBuf
		// （这个队列在被跟踪的ByteBuf标记为垃圾的时候（应该是JVM GC标记的吧？）是弱引用自动会被添加的）
		@Override
        public boolean close() {
            // Use the ConcurrentMap remove method, which avoids allocating an iterator.
            if (allLeaks.remove(this, LeakEntry.INSTANCE)) {
                // Call clear so the reference is not even enqueued.
                clear();
                headUpdater.set(this, null);
                return true;
            }
            return false;
        }
    }
}