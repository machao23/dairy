public final class RecordAccumulator {
	
	// Sender线程准备关闭
	private volatile boolean closed;
	// 批次大小
	private final int batchSize;
	// 缓存了发往对应topicPartition的N个消息批次
	// Deque是非线程安全，所以出入队列需要加锁
	private final ConcurrentMap<TopicPartition, Deque<RecordBatch>> batches;
	private final BufferPool free;
	// 未发送完成的批次集合，底层通过Set<RecordBatch>实现
	private final IncompleteRecordBatches incomplete;
	// 使用drain方法批量导出批次，为了防止饥饿，使用drainIndex记录上次发送停止时的位置，下次发送继续从该位置开始
	private int drainIndex;

	// 发送追加消息
	public RecordAppendResult append(TopicPartition tp, long timestamp, byte[] key, byte[] value, Callback callback,
                                     long maxTimeToBlock) throws InterruptedException {
		// 通过topic-partition查找map里指定的消息队列
		Deque<RecordBatch> dq = getOrCreateDeque(tp);
		synchronized (dq) {
			// 尝试往Dequeue的最后一个批次里追加消息
			RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
			if (appendResult != null)
				// 追加成功返回
				return appendResult;
		}

		// 追加失败, 尝试申请缓存
		// 注意这里走出synchronized代码块，释放dq锁了，可能堵塞在申请缓存空间上，把发送消息让给其他需要更少空间的发送消息线程
		int size = Math.max(this.batchSize, Records.LOG_OVERHEAD + Record.recordSize(key, value));
		ByteBuffer buffer = free.allocate(size, maxTimeToBlock);
		synchronized (dq) {
			RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
			if (appendResult != null) {
				// appendResult不为null说明第2次重试tryAppend成功了
				// 说明在synchronized释放申请缓存的时候，有其他线程释放了空间
				// 所以这里要把申请的缓存还回去
				free.deallocate(buffer);
				return appendResult;
			}
			// 申请到新空间后创建一个新批次
			// 之前从free那申请的缓存分配给MemoryRecords
			MemoryRecords records = MemoryRecords.emptyRecords(buffer, compression, this.batchSize);
			RecordBatch batch = new RecordBatch(tp, records, time.milliseconds());
			FutureRecordMetadata future = Utils.notNull(batch.tryAppend(timestamp, key, value, callback, time.milliseconds()));

			// 新建的batch加到Dequeue和incomplete里
			dq.addLast(batch);
			incomplete.add(batch);
			// 返回result,return后面会根据条件判断是否要唤醒Sender线程
			return new RecordAppendResult(future, dq.size() > 1 || batch.records.isFull(), true);
		}
    }

	// 获取集群中符合发送消息条件的节点集合
	public ReadyCheckResult ready(Cluster cluster, long nowMs) {
        Set<Node> readyNodes = new HashSet<>();
        long nextReadyCheckDelayMs = Long.MAX_VALUE;
        boolean unknownLeadersExist = false;

		// waiter队列里有成员在排队，说明有其他线程在等待BufferPool释放空间，需要发送消息释放空间
        boolean exhausted = this.free.queued() > 0;
		// 遍历每个topicPartition
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
            TopicPartition part = entry.getKey();
            Deque<RecordBatch> deque = entry.getValue();

			// 获取parition的leader节点
            Node leader = cluster.leaderFor(part);
            if (leader == null) {
				// 标记需要更新Metadata
                unknownLeadersExist = true;
            } else if (!readyNodes.contains(leader) && !muted.contains(part)) {
                synchronized (deque) {
					// 加锁从队列里取出第一个批次
                    RecordBatch batch = deque.peekFirst();
                    if (batch != null) {
                        boolean backingOff = batch.attempts > 0 && batch.lastAttemptMs + retryBackoffMs > nowMs;
                        long waitedTimeMs = nowMs - batch.lastAttemptMs;
                        long timeToWaitMs = backingOff ? retryBackoffMs : lingerMs;
                        long timeLeftMs = Math.max(timeToWaitMs - waitedTimeMs, 0);
						// Dequeue中有多个批次或者第一个批次已经满了
						// 如果缓存已用容量position超过writeLimit,表示批次已满
                        boolean full = deque.size() > 1 || batch.records.isFull();
						// 超时
                        boolean expired = waitedTimeMs >= timeToWaitMs;
						// flushInProgress有线程正在等待flush操作完成
                        boolean sendable = full || expired || exhausted || closed || flushInProgress();
                        if (sendable && !backingOff) {
							// 满足符合发送消息的节点
                            readyNodes.add(leader);
                        } else {
							// 下次调用ready方法的间隔, 本质是控制selector.select()堵塞时参考的等待时长要素之一
                            nextReadyCheckDelayMs = Math.min(timeLeftMs, nextReadyCheckDelayMs);
                        }
                    }
                }
            }
        }

        return new ReadyCheckResult(readyNodes, nextReadyCheckDelayMs, unknownLeadersExist);
    }

	// 将partition-批次集合 转成 节点-批次集合 映射
	 public Map<Integer, List<RecordBatch>> drain(Cluster cluster, Set<Node> nodes, int maxSize, long now) {

        Map<Integer, List<RecordBatch>> batches = new HashMap<>();
		// 遍历需要发送消息的节点
        for (Node node : nodes) {
            int size = 0;
			// node上的partition集合
            List<PartitionInfo> parts = cluster.partitionsForNode(node.id());
			// 待发送消息批次的集合
            List<RecordBatch> ready = new ArrayList<>();
			// drainIndex就是断电续传的书签
            int start = drainIndex = drainIndex % parts.size();
            do {
                PartitionInfo part = parts.get(drainIndex);
                TopicPartition tp = new TopicPartition(part.topic(), part.partition());
                if (!muted.contains(tp)) {
					// 获取该partion的消息批次集合
                    Deque<RecordBatch> deque = getDeque(new TopicPartition(part.topic(), part.partition()));
                    if (deque != null) {
                        synchronized (deque) {
							// 获取第一条消息批次
                            RecordBatch first = deque.peekFirst();
                            if (first != null) {
                                boolean backoff = first.attempts > 0 && first.lastAttemptMs + retryBackoffMs > now;
                                if (!backoff) {
                                    if (size + first.records.sizeInBytes() > maxSize && !ready.isEmpty()) {
										// 要发送的集合大小超过配置的单个请求的maxSize，跳出循环
										// 因为外层循环是node节点，所以单个请求只是node粒度的
                                        break;
                                    } else {
										// 每个node的partition只取一个消息批次，后面会drainIndex+1寻找下一个partition
										// 防止只有一个partition在发送，其他partition处于饥饿状态
                                        RecordBatch batch = deque.pollFirst();
										// 关闭输出流
                                        batch.records.close();
                                        size += batch.records.sizeInBytes();
                                        ready.add(batch);
                                        batch.drainedMs = now;
                                    }
                                }
                            }
                        }
                    }
                }
                this.drainIndex = (this.drainIndex + 1) % parts.size();
            } while (start != drainIndex);
            batches.put(node.id(), ready);
        }
        return batches;
    }
}

public final class BufferPool {
	// 整个pool的大小
	private final long totalMemory;
	// 指定free队列里每个byteBuffer的大小,等于batchSize
	private final int poolableSize;
	// 控制多线程并发分配和回收ByteBuffer
	private final ReentrantLock lock;
	// 缓存byteBuffer的队列,缓冲池
	// 池化队列free，避免ByteBuffer.allocate创建开销
	private final Deque<ByteBuffer> free;
	// 记录因申请不到足够的空间而阻塞的线程，实际记录阻塞线程对应的Condition对象
	private final Deque<Condition> waiters;
	// totalMemory - free
	private long availableMemory;

	// 追加消息时候会调用
	// 从缓冲池free里申请ByteBuffer，缓冲池空间不足就会阻塞调用线程
	// size是申请空间大小,maxTimeToBlockMs申请最大等待时间
	public ByteBuffer allocate(int size, long maxTimeToBlockMs) throws InterruptedException {
		// 加锁
        this.lock.lock();
        try {
            if (size == poolableSize && !this.free.isEmpty())
				// 申请大小正好等于一个ByteBuffer对象的大小，直接从队列里取出
				// 所以消息的长度不要超过batchSize
                return this.free.pollFirst();

            int freeListSize = this.free.size() * this.poolableSize;
			// 缓冲池+剩余可用足够申请
            if (this.availableMemory + freeListSize >= size) {
				// 先从free里一块块划走ByteBuffer缓存到availableMemory，直到满足size
                freeUp(size);
				// 然后availableMemory里扣除size部分的缓存
                this.availableMemory -= size;
                lock.unlock();
                return ByteBuffer.allocate(size);
            } else {
                int accumulated = 0;
                ByteBuffer buffer = null;
				// Condition阻塞等待有足够的缓存
                Condition moreMemory = this.lock.newCondition();
                long remainingTimeToBlockNs = TimeUnit.MILLISECONDS.toNanos(maxTimeToBlockMs);
				// 加入waiters等待队列
                this.waiters.addLast(moreMemory);
                while (accumulated < size) {
                    long startWaitNs = time.nanoseconds();
                    long timeNs;
					// 阻塞Condition等待
                    boolean waitingTimeElapsed = !moreMemory.await(remainingTimeToBlockNs, TimeUnit.NANOSECONDS);

                    if (waitingTimeElapsed) {
						// 表示等待超时，抛出异常结束
                        this.waiters.remove(moreMemory);
                        throw new TimeoutException("Failed to allocate memory within the configured max blocking time " + maxTimeToBlockMs + " ms.");
                    }

					// 可能有空间申请，但还不够size，所以减去时间后继续等待
                    remainingTimeToBlockNs -= timeNs;
					// 正好size是一个ByteBuffer
                    if (accumulated == 0 && size == this.poolableSize && !this.free.isEmpty()) {
                        buffer = this.free.pollFirst();
                        accumulated = size;
                    } else {
                        freeUp(size - accumulated);
                        int got = (int) Math.min(size - accumulated, this.availableMemory);
                        this.availableMemory -= got;
                        accumulated += got;
                    }
                }

				// 等待队列是先进先出的
                Condition removed = this.waiters.removeFirst();
				// 要是还有剩余空间唤醒下一个阻塞等待的其他线程
                if (this.availableMemory > 0 || !this.free.isEmpty()) {
                    if (!this.waiters.isEmpty())
                        this.waiters.peekFirst().signal();
                }

                lock.unlock();
                if (buffer == null)
                    return ByteBuffer.allocate(size);
                else
                    return buffer;
            }
        } finally {
            if (lock.isHeldByCurrentThread())
                lock.unlock();
        }
    }

	// 释放缓存归还
	public void deallocate(ByteBuffer buffer, int size) {
        lock.lock();
        try {
			// 正好等于单个ByteBuffer就放回free队列
            if (size == this.poolableSize && size == buffer.capacity()) {
                buffer.clear();
                this.free.add(buffer);
            } else {
				// 不等于就加到availableMemory,不放回free队列?
                this.availableMemory += size;
            }
            Condition moreMem = this.waiters.peekFirst();
            if (moreMem != null)
				// 唤醒正在堵塞等待可用空间的线程
                moreMem.signal();
        } finally {
            lock.unlock();
        }
    }
}

public final class RecordBatch {
	// 消息个数
	public int recordCount = 0;
	// 最大的消息的字节数
    public int maxRecordSize = 0;
	// 尝试发送当前批次的次数
    public volatile int attempts = 0;
	// 最后一次尝试发送的时间戳
    public long lastAttemptMs;
	// 消息最终存放的地方
    public final MemoryRecords records;
	// 当前批次发送给哪个topic的partition
    public final TopicPartition topicPartition;
	// 标志RecordBatch状态的Future对象
    public final ProduceRequestResult produceFuture;
	// 最后一次往批次里追加消息的时间戳
    public long lastAppendTime;
    private final List<Thunk> thunks;
	// 记录某消息在批次中的offset
    private long offsetCounter = 0L;
	// 是否正在重试
    private boolean retry;

	// 追加消息
	public FutureRecordMetadata tryAppend(long timestamp, byte[] key, byte[] value, Callback callback, long now) {
        if (!this.records.hasRoomFor(key, value)) {
			// buffer没有空间返回null
            return null;
        } else {
			// 委托给MemoryRecords追加消息到缓存
            long checksum = this.records.append(offsetCounter++, timestamp, key, value);
            this.maxRecordSize = Math.max(this.maxRecordSize, Record.recordSize(key, value));
            this.lastAppendTime = now;
			// 通过produceFuture的latchDown实现异步future的特性
            FutureRecordMetadata future = new FutureRecordMetadata(this.produceFuture, this.recordCount,
                                                                   timestamp, checksum,
                                                                   key == null ? -1 : key.length,
                                                                   value == null ? -1 : value.length);
            if (callback != null)
				// thunks就是批次里所有消息callback的队列集合
                thunks.add(new Thunk(callback, future));
            this.recordCount++;
            return future;
        }
    }

	// 当批次成功收到正常响应、或超时、或关闭producer时，调用done方法
	public void done(long baseOffset, long timestamp, RuntimeException exception) {
        // 回调所有消息的callback
        for (int i = 0; i < this.thunks.size(); i++) {
			Thunk thunk = this.thunks.get(i);
			if (exception == null) {
				// thunk.future即服务端返回的结果，封装成RecordMetadata
				RecordMetadata metadata = new RecordMetadata(this.topicPartition,  baseOffset, thunk.future.relativeOffset(),
															 timestamp == Record.NO_TIMESTAMP ? thunk.future.timestamp() : timestamp,
															 thunk.future.checksum(),
															 thunk.future.serializedKeySize(),
															 thunk.future.serializedValueSize());
				thunk.callback.onCompletion(metadata, null);
			} else {
				thunk.callback.onCompletion(null, exception);
			}
        }
		// 这里会调用latchDown.donw()
        this.produceFuture.done(topicPartition, baseOffset, exception);
    }

	final private static class Thunk {
		// 对应消息的callback
        final Callback callback;
        final FutureRecordMetadata future;
    }
}

// 类名字面意思就是包含RecordMetadata的Future对象
public final class FutureRecordMetadata implements Future<RecordMetadata> {

	// 类似指针，指向消息所在批次RecordBatch的produceFuture
	private final ProduceRequestResult result;
	// 消息在批次中的offset
	private final long relativeOffset;

	// 实现future接口方法
	@Override
    public RecordMetadata get() throws InterruptedException, ExecutionException {
		// 本质是委托produceFuture的latchDown.await()
        this.result.await();
        return valueOrError();
    }

	RecordMetadata valueOrError() throws ExecutionException {
        if (this.result.error() != null)
            throw new ExecutionException(this.result.error());
        else
            return value();
    }

	// 当producer收到消息批次的响应时，get方法返回RecordMetadata对象，即消息的元数据
    RecordMetadata value() {
        return new RecordMetadata(result.topicPartition(), this.result.baseOffset(), this.relativeOffset,
                                  this.timestamp, this.checksum, this.serializedKeySize, this.serializedValueSize);
    }

	// 在sender处理服务端返回的响应，回调callback时会被调用
	public void done(TopicPartition topicPartition, long baseOffset, RuntimeException error) {
        this.topicPartition = topicPartition;
        this.baseOffset = baseOffset;
        this.error = error;
		// 通知future有结果了
        this.latch.countDown();
    }
}

public final class ProduceRequestResult {
	// 没有实现Future接口，通过CountDownLatch实现了类似Future的功能
	private final CountDownLatch latch = new CountDownLatch(1);
	// 服务端为批次RecordBatch中第一条消息分配的offset
	// 这个批次下每个消息根据baseOffset能够计算出自己再服务端分区中的偏移量
	private volatile long baseOffset = -1L;
	private volatile RuntimeException error;

	// RecordBatch会调用该方法，通知消息被正常响应
	public void done(TopicPartition topicPartition, long baseOffset, RuntimeException error) {
		this.topicPartition = topicPartition;
        this.baseOffset = baseOffset;
		// 区分消息是正常响应还是异常响应
        this.error = error;
		// 唤醒阻塞在latch.await()的thread
        this.latch.countDown();
    }
}

public class MemoryRecords implements Records {

	// 压缩后的消息输出到buffer(压缩场景主要是瓶颈在网络带宽)
	private final Compressor compressor;
	// buffer最多可以写入多少个字节的数据
	private final int writeLimit;
	// 保存消息数据
	private ByteBuffer buffer;
	// 在MemoryRecords发送前会设置成false-只读
	private boolean writable;

	// 私有构造方法
	private MemoryRecords(ByteBuffer buffer, CompressionType type, boolean writable, int writeLimit) {
		...
    }

	// 只能通过emptyRecords创建MemoryRecords实例
	public static MemoryRecords emptyRecords(ByteBuffer buffer, CompressionType type, int writeLimit) {
        return new MemoryRecords(buffer, type, true, writeLimit);
    }

	// 追加消息
	public void append(long offset, Record record) {
		// 判断是否只读
        if (!writable)
            throw new IllegalStateException("Memory records is not writable");

		// 调用compressor将消息写入ByteBuffer
        int size = record.size();
        compressor.putLong(offset);
        compressor.putInt(size);
        compressor.put(record.buffer());
        compressor.recordWritten(size + Records.LOG_OVERHEAD);
        record.buffer().rewind();
    }

	// 估算MemoryRecords剩余空间是否足够写入消息，主要是压缩后的消息
	// 如果估算不准，会导致底层ByteBuffer扩容
	public boolean hasRoomFor(byte[] key, byte[] value) {
        if (!this.writable)
            return false;

        return this.compressor.numRecordsWritten() == 0 ?
            this.initialCapacity >= Records.LOG_OVERHEAD + Record.recordSize(key, value) :
            this.writeLimit >= this.compressor.estimatedBytesWritten() + Records.LOG_OVERHEAD + Record.recordSize(key, value);
    }

}

public class Compressor {
	// 添加了压缩功能
	private final DataOutputStream appendStream;
	// 封装了ByteBuffer，当写入数据超出ByteBuffer容量时会ByteBufferOutputStream会自动扩容
	private final ByteBufferOutputStream bufferStream;

	public Compressor(ByteBuffer buffer, CompressionType type) {
		// KafkaProducer传入的压缩类型
        this.type = type;
        // create the stream
        bufferStream = new ByteBufferOutputStream(buffer);
		// 根据压缩类型创建合适的压缩流,默认是NONE不压缩
		// 装饰器模式，在ByteBufferOutputStream上装饰一层DataOutputStream
		// 最终压缩流: compressor.put -> appendStream -> bufferStream -> buffer
		// 最后数据都写到MemoryRecords的buffer缓存里
        appendStream = wrapForOutput(bufferStream, type, COMPRESSION_DEFAULT_BUFFER_SIZE);
    }

	// 构造压缩流
	public static DataOutputStream wrapForOutput(ByteBufferOutputStream buffer, CompressionType type, int bufferSize) {
		switch (type) {
			case NONE:
				return new DataOutputStream(buffer);
			case GZIP:
				// GZIPOutputStream是JDK自带的，不需要反射
				return new DataOutputStream(new GZIPOutputStream(buffer, bufferSize));
			case SNAPPY:
				// snappy需要引入额外的依赖包，缺省不适用snappy压缩的时候，不引入依赖包，为了编译通过这里就用反射动态创建
				OutputStream stream = (OutputStream) snappyOutputStreamSupplier.get().newInstance(buffer, bufferSize);
				return new DataOutputStream(stream);
			case LZ4:
				OutputStream stream = (OutputStream) lz4OutputStreamSupplier.get().newInstance(buffer);
				return new DataOutputStream(stream);
		}
    }

	// 写消息到缓存里，实质调用Record消息静态类的write放大
	private void putRecord(final long crc, final byte attributes, final long timestamp, final byte[] key, final byte[] value, final int valueOffset, final int valueSize) {
        maxTimestamp = Math.max(maxTimestamp, timestamp);
        Record.write(this, crc, attributes, timestamp, key, value, valueOffset, valueSize);
    }
}

