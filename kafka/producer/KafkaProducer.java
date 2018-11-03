public class KafkaProducer<K, V> implements Producer<K, V> {

	// 缺省的clientId生成器
	private static final AtomicInteger PRODUCER_CLIENT_ID_SEQUENCE = new AtomicInteger(1);
	private String clientId;
	// 分区选择器
	private final Partitioner partitioner;
	// 消息的最大长度
	private final int maxRequestSize;
	// 单个消息的缓存区大小
	private final long totalMemorySize;
	// 整个kafka集群的元数据
	private final Metadata metadata;
	// 收集缓存消息，等待Sender线程发送
	private final RecordAccumulator accumulator;
	// 发送消息的Sender任务,在ioThread线程中执行
	private final Sender sender;
	private final Thread ioThread;
	// 压缩算法
	private final CompressionType compressionType;
	private final Serializer<K> keySerializer;
    private final Serializer<V> valueSerializer;
	// 配置对象
	private final ProducerConfig producerConfig;
	// 等待更新kafka集群元数据的最大时长（刷新metadata缓存的间隔?)
	private final long maxBlockTimeMs;
	// 消息等待ACK的超时时长
	private final int requestTimeoutMs;
	// 消息发送前的拦截器；也可以先于用户的Callback对ACK进行预处理(发送和接受都可以做拦截?)
	private final ProducerInterceptors<K, V> interceptors;

	// 构造方法
	private KafkaProducer(ProducerConfig config, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
		// 获取配置项, 一般是外部的props键值对
		this.producerConfig = config;
		// 默认的实现是DefaultPartitioner
		this.partitioner = config.getConfiguredInstance(ProducerConfig.PARTITIONER_CLASS_CONFIG, Partitioner.class);
		this.keySerializer = config.getConfiguredInstance(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        Serializer.class);
		this.valueSerializer = config.getConfiguredInstance(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        Serializer.class);

		// 创建kafka集群的metadata
		this.metadata = new Metadata(retryBackoffMs, config.getLong(ProducerConfig.METADATA_MAX_AGE_CONFIG));

		// 创建收集器
		this.accumulator = new RecordAccumulator(
					// batch.size指定每个RecordBatch的大小
					config.getInt(ProducerConfig.BATCH_SIZE_CONFIG),
                    this.totalMemorySize, this.compressionType, config.getLong(ProducerConfig.LINGER_MS_CONFIG),
                    retryBackoffMs, metrics, time);

		// addresses 就是kafka集群的地址
		List<InetSocketAddress> addresses = ClientUtils.parseAndValidateAddresses(config.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        this.metadata.update(Cluster.bootstrap(addresses), time.milliseconds());

		// kafkaProducer网络IO的核心, 跟着Producer一起初始化创建
		NetworkClient client = new NetworkClient(
				new Selector(config.getLong(ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG), this.metrics, time, "producer", channelBuilder),
				this.metadata,
				clientId,
				config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION),
				config.getLong(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG),
				config.getInt(ProducerConfig.SEND_BUFFER_CONFIG),
				config.getInt(ProducerConfig.RECEIVE_BUFFER_CONFIG),
				this.requestTimeoutMs, time);

		// 创建完的NetworkClient交给Sender
		this.sender = new Sender(client,
				this.metadata,
				this.accumulator,
				config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION) == 1,
				config.getInt(ProducerConfig.MAX_REQUEST_SIZE_CONFIG),
				(short) parseAcks(config.getString(ProducerConfig.ACKS_CONFIG)),
				config.getInt(ProducerConfig.RETRIES_CONFIG),
				this.metrics,
				new SystemTime(),
				clientId,
				this.requestTimeoutMs);

		// 启动Sender对应的thread,异步执行Sender
		String ioThreadName = "kafka-producer-network-thread" + (clientId.length() > 0 ? " | " + clientId : "");
		this.ioThread = new KafkaThread(ioThreadName, this.sender, true);
		this.ioThread.start();
	}

	// 发送消息,被用户调用
	@Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
        // 拦截修改消息
        ProducerRecord<K, V> interceptedRecord = this.interceptors == null ? record : this.interceptors.onSend(record);
        return doSend(interceptedRecord, callback);
    }

	private Future<RecordMetadata> doSend(ProducerRecord<K, V> record, Callback callback) {
		TopicPartition tp = null;
		// 唤醒Sender线程更新Metadata,准备发送报文
		long waitedOnMetadataMs = waitOnMetadata(record.topic(), this.maxBlockTimeMs);
		long remainingWaitMs = Math.max(0, this.maxBlockTimeMs - waitedOnMetadataMs);
		// 序列化成字节数组
		byte[] serializedKey = keySerializer.serialize(record.topic(), record.key());
		byte[] serializedValue = valueSerializer.serialize(record.topic(), record.value());
		// 为消息选择合适的partition
		int partition = partition(record, serializedKey, serializedValue, metadata.fetch());
		tp = new TopicPartition(record.topic(), partition);
		Callback interceptCallback = this.interceptors == null ? callback : new InterceptorCallback<>(callback, this.interceptors, tp);
		// 将消息追加到RecordAccumulator中，积攒消息
		RecordAccumulator.RecordAppendResult result = accumulator.append(tp, timestamp, serializedKey, serializedValue, interceptCallback, remainingWaitMs);
		// 批次满了(即compressor的estimatedBytesWritten不够了）或者队列中不止一个批次
		if (result.batchIsFull || result.newBatchCreated) {
			// 唤醒Sender线程异步发送缓存中的消息,不等待
			this.sender.wakeup();
		}
		// 最后立即返回future结果
		return result.future;
	}

	// 触发更新集群元数据，阻塞主线程等待
	private long waitOnMetadata(String topic, long maxWaitMs) throws InterruptedException {
		// 检查metadata里是否包含指定topic的元数据
        if (!this.metadata.containsTopic(topic))
            this.metadata.add(topic);

        if (metadata.fetch().partitionsForTopic(topic) != null)
			// topic中分区详细信息已经有了就不更新了？(老版本更新不是在这里做的？）
			// 所以只是第一次连接后发送消息会拉取metadata而已？
            return 0;

        long begin = time.milliseconds();
        long remainingWaitMs = maxWaitMs;
        while (metadata.fetch().partitionsForTopic(topic) == null) {
			// 请求更新，设置needUpdate = true
            int version = metadata.requestUpdate();
			// 唤醒sender,其实是唤醒sender的selector堵塞，更新元信息生效
            sender.wakeup();
			// 主线程阻塞等待sender从服务端获取元信息后更新唤醒
            metadata.awaitUpdate(version, remainingWaitMs);
            long elapsed = time.milliseconds() - begin;
			// 检查超时
            if (elapsed >= maxWaitMs)
                throw new TimeoutException("Failed to update metadata after " + maxWaitMs + " ms.");
            remainingWaitMs = maxWaitMs - elapsed;
        }
        return time.milliseconds() - begin;
    }

	private int partition(ProducerRecord<K, V> record, byte[] serializedKey , byte[] serializedValue, Cluster cluster) {
        Integer partition = record.partition();
        if (partition != null) {
			// 优先消息指定分区
            List<PartitionInfo> partitions = cluster.partitionsForTopic(record.topic());
            return partition;
        }
        return this.partitioner.partition(record.topic(), record.key(), serializedKey, record.value(), serializedValue,
            cluster);
    }
}
