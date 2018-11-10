public class Sender implements Runnable {
	// NetworkClient实现
	private final KafkaClient client;

	public void run() {
        log.debug("Starting Kafka producer I/O thread.");

        // 轮询监听发送消息
        while (running) {
            run(time.milliseconds());
        }

        log.debug("Beginning shutdown of Kafka producer I/O thread, sending remaining records.");
	}

	// 负责和Accumulator打交道，获取缓存要发送的批次（因为有了批次这个概念，才会有Accumulator这个组件）
	// 后面的网络IO委托给NetworkClient搞定
	void run(long now) {
		 
		Cluster cluster = metadata.fetch();
		// 获取集群中符合发送消息条件的节点集合

		// 被唤醒后从累加器里捞取待发送的消息批次
        RecordAccumulator.ReadyCheckResult result = this.accumulator.ready(cluster, now);

		// 标记需要更新kafka的集群信息
		if (result.unknownLeadersExist)
            this.metadata.requestUpdate();

        Iterator<Node> iter = result.readyNodes.iterator();
        long notReadyTimeout = Long.MAX_VALUE;
        while (iter.hasNext()) {
            Node node = iter.next();
			// 经过NetworkClient的过滤
			// 检查网络IO方面(connection,channel...)是否符合发送消息的条件
            if (!this.client.ready(node, now)) {
                iter.remove();
                notReadyTimeout = Math.min(notReadyTimeout, this.client.connectionDelay(node, now));
            }
        }

        // 获取要发送的消息批次集合
		// 映射成 nodeId -> List<批次>
        Map<Integer, List<RecordBatch>> batches = this.accumulator.drain(cluster, result.readyNodes, this.maxRequestSize, now);
		// 处理RecordAccumulator中超时的消息
        List<RecordBatch> expiredBatches = this.accumulator.abortExpiredBatches(this.requestTimeout, now);
		// 将等待发送的消息封装成ClientRequest
        List<ClientRequest> requests = createProduceRequests(batches, now);
        long pollTimeout = Math.min(result.nextReadyCheckDelayMs, notReadyTimeout);
        if (result.readyNodes.size() > 0) {
            pollTimeout = 0;
        }

		// send和poll这2个阶段实质是委托的NetworkClient去做的
		// 将ClientRequest写入KafkaChannel的send字段
        for (ClientRequest request : requests)
            client.send(request, now);
		// 发送ClientRequest，同时还会处理服务端的响应、处理超时请求、调用用户自定义的Callback等
		// 一般没有消息发送时，会堵塞在poll方法,委托给NetworkClient的selector.select堵塞
        this.client.poll(pollTimeout, now);
    }

	// createProduceRequests -> produceRequest
	private ClientRequest produceRequest(long now, int destination, short acks, int timeout, List<RecordBatch> batches) {
        Map<TopicPartition, ByteBuffer> produceRecordsByPartition = new HashMap<TopicPartition, ByteBuffer>(batches.size());
        final Map<TopicPartition, RecordBatch> recordsByPartition = new HashMap<TopicPartition, RecordBatch>(batches.size());
		// 将这个node下要发送的消息批次，再映射成partition -> batch
        for (RecordBatch batch : batches) {
            TopicPartition tp = batch.topicPartition;
            produceRecordsByPartition.put(tp, batch.records.buffer());
            recordsByPartition.put(tp, batch);
        }
		// 创建ProduceRequest
        ProduceRequest request = new ProduceRequest(acks, timeout, produceRecordsByPartition);
		// 创建RequestSend，是真正通过网络IO发送对象
        RequestSend send = new RequestSend(Integer.toString(destination),
                                           this.client.nextRequestHeader(ApiKeys.PRODUCE),
                                           request.toStruct());
		// 创建回调对象
        RequestCompletionHandler callback = new RequestCompletionHandler() {
            public void onComplete(ClientResponse response) {
                handleProduceResponse(response, recordsByPartition, time.milliseconds());
            }
        };
		// 以上全部封装到ClientRequest返回
        return new ClientRequest(now, acks != 0, send, callback);
    }
}

// 通用的网络客户端实现，不仅仅用于producer发送消息，也用于consumer消费消息，还有broker之间的通信
public class NetworkClient implements KafkaClient {
	// 本质上网络IO都是委托给selector搞定
	private final Selectable selector;
	// 默认实现是内部类DefaultMetadataUpdater, 负责更新集群元信息
	private final MetadataUpdater metadataUpdater;
	// 底层是Map实现 NodeId -> NodeConnectionState, 管理每个node的连接状态
	// 主要用于检测连接状态
	private final ClusterConnectionStates connectionStates;
	// 缓存已经发出去但没有收到响应的ClientRequest,管理在途的request
	// 底层是Map<NodeId, Dequeue<Request>>
	private final InFlightRequests inFlightRequests;

	// 检测node是否可以接受请求
	// 发送消息前做防御性检查筛选
	@Override
    public boolean ready(Node node, long now) {
		// node可以接受请求
        if (isReady(node, now))
            return true;

		// 先发起连接
        if (connectionStates.canConnect(node.idString(), now))
            initiateConnect(node, now);

        return false;
    }

	@Override
    public void send(ClientRequest request, long now) {
        String nodeId = request.request().destination();
		// 检测连接状态、身份认证、在途请求最大限制
		// 会调用InFlightRequest.canSendMore检查，保证不会覆盖发往该node对应KafkaChannel的send字段，引发KafkaChannel.send抛异常
        if (!canSendRequest(nodeId))
			// 检查能否填充指定node对应的KafkaChannel的send字段
            throw new IllegalStateException("Attempt to send a request to node " + nodeId + " which is not ready.");
		// 最后调用的顺序是 NetworkClient.send -> Selector.send -> KafkaChannel.setSend
        doSend(request, now);
    }

	private void doSend(ClientRequest request, long now) {
        request.setSendTimeMs(now);
		// 添加到在途请求队列,等待响应
        this.inFlightRequests.add(request);
		// 本质是委托给selector去send. selector根据目标node找到对应的KafkaChannel，填写send
        selector.send(request.request());
    }

	@Override
    public List<ClientResponse> poll(long timeout, long now) {
        long metadataTimeout = metadataUpdater.maybeUpdate(now); // 更新Metadata
        this.selector.poll(Utils.min(timeout, metadataTimeout, requestTimeoutMs)); // 执行IO操作

        List<ClientResponse> responses = new ArrayList<>(); // 响应队列
        handleCompletedSends(responses, updatedNow); // 处理completedSends队列
        handleCompletedReceives(responses, updatedNow); // 处理completedReceives队列
        handleDisconnections(responses, updatedNow); // 处理disconnected队列
        handleConnections(); // 处理connected队列
        handleTimedOutRequests(responses, updatedNow); // 处理在途请求队列里的超时请求

		// 经过一系列的handleXXX方法处理后，所有的ClientResponse都在response集合里
        // 调用ClientRequest里的callback
        for (ClientResponse response : responses) {
            if (response.request().hasCallback()) {
				// callback()会调用Sender.handleProduceResponse()
                response.request().callback().onComplete(response);
            }
        }

        return responses;
    }

	// 主要是从在途请求队列里移除不需要响应的请求
	private void handleCompletedSends(List<ClientResponse> responses, long now) {
        for (Send send : this.selector.completedSends()) {
			// 获取指定目的地队列的第一个请求
            ClientRequest request = this.inFlightRequests.lastSent(send.destination());
            if (!request.expectResponse()) {
				// 请求不需要响应，从在途请求队列的移除
                this.inFlightRequests.completeLastSent(send.destination());
				// 添加到response集合
                responses.add(new ClientResponse(request, now, false, null));
            }
        }
    }

	// 主要是从在途请求队列里移除已经响应的请求
	private void handleCompletedReceives(List<ClientResponse> responses, long now) {
        for (NetworkReceive receive : this.selector.completedReceives()) {
            String source = receive.source();
            ClientRequest req = inFlightRequests.completeNext(source);
            Struct body = parseResponse(receive.payload(), req.request().header());
            if (!metadataUpdater.maybeHandleCompletedReceive(req, now, body)) // 更新metadata的响应，回调metadataUpdater
                responses.add(new ClientResponse(req, now, false, body));
        }
    }

	private void handleDisconnections(List<ClientResponse> responses, long now) {
        for (String node : this.selector.disconnected()) {
            processDisconnection(responses, node, now);
        }
        if (this.selector.disconnected().size() > 0)
			// 需要更新Metadata
            metadataUpdater.requestUpdate();
    }

	private void processDisconnection(List<ClientResponse> responses, String nodeId, long now) {
		// 更新断开的连接状态, 
        connectionStates.disconnected(nodeId, now);
		// 从在途请求队列里移除该异常node下所有的request
        for (ClientRequest request : this.inFlightRequests.clearAll(nodeId)) {
            if (!metadataUpdater.maybeHandleDisconnection(request))
                responses.add(new ClientResponse(request, now, true, null)); // 自己创建一个断开连接异常的respone
        }
    }

	// 处理服务端的响应
	private void handleProduceResponse(ClientResponse response, Map<TopicPartition, RecordBatch> batches, long now) {
        int correlationId = response.request().request().header().correlationId();
        if (response.wasDisconnected()) {
            for (RecordBatch batch : batches.values())
				// 连接断开而创建的response，会重试发送request，如果不能重试，调用每条消息的callback
                completeBatch(batch, Errors.NETWORK_EXCEPTION, -1L, Record.NO_TIMESTAMP, correlationId, now);
        } else {
            if (response.hasResponse()) {
                ProduceResponse produceResponse = new ProduceResponse(response.responseBody());
                for (Map.Entry<TopicPartition, ProduceResponse.PartitionResponse> entry : produceResponse.responses().entrySet()) {
                    TopicPartition tp = entry.getKey();
                    ProduceResponse.PartitionResponse partResp = entry.getValue();
                    Errors error = Errors.forCode(partResp.errorCode);
                    RecordBatch batch = batches.get(tp);
                    completeBatch(batch, error, partResp.baseOffset, partResp.timestamp, correlationId, now);
                }
            } else {
                for (RecordBatch batch : batches.values())
                    completeBatch(batch, Errors.NONE, -1L, Record.NO_TIMESTAMP, correlationId, now);
            }
        }
    }

	// 实质处理消息应答
	private void completeBatch(RecordBatch batch, Errors error, long baseOffset, long timestamp, long correlationId, long now) {
        if (error != Errors.NONE && canRetry(batch, error)) {
            // 重试请求
			// 添加到accumulator等待发送
            this.accumulator.reenqueue(batch, now);
        } else {
            if (error == Errors.TOPIC_AUTHORIZATION_FAILED)
                exception = new TopicAuthorizationException(batch.topicPartition.topic());
            else
                exception = error.exception();
			// 调用消息的callback
            batch.done(baseOffset, timestamp, exception);
			// 释放消息缓存
            this.accumulator.deallocate(batch);
            if (error != Errors.NONE)
                this.sensors.recordErrors(batch.topicPartition.topic(), batch.recordCount);
        }
        if (error.exception() instanceof InvalidMetadataException)
			// 标记需要更新Metadata
            metadata.requestUpdate();
    }


	class DefaultMetadataUpdater implements MetadataUpdater {
		// 集群元数据
        private final Metadata metadata;
		// 是否已经发送了Metadata的更新请求
        private boolean metadataFetchInProgress;
		// 检测到没有可用节点时，记录时间戳
        private long lastNoNodeAvailableMs;

		// 是否需要发送更新Metadata的请求
		@Override
        public long maybeUpdate(long now) {
			// 得到下次更新集群元数据的时间戳
            long timeToNextMetadataUpdate = metadata.timeToNextUpdate(now);
			// 得到下次尝试重新连接服务端的时间戳
            long timeToNextReconnectAttempt = Math.max(this.lastNoNodeAvailableMs + metadata.refreshBackoff() - now, 0);
			// 检测是否已经发送过更新请求了
            long waitForMetadataFetch = this.metadataFetchInProgress ? Integer.MAX_VALUE : 0;
            // 计算下次更新Metadata请求的时间间隔
            long metadataTimeout = Math.max(Math.max(timeToNextMetadataUpdate, timeToNextReconnectAttempt), waitForMetadataFetch);

			// 立即发送更新请求
            if (metadataTimeout == 0) {
				// 找到负载最小的Node
				// 调用的是外部类NetworkClient的leastLoadedNode方法，
				// 本质是根据NetworkClient的InFlightRequests里 NodeId -> ClientRequest集合,看哪个集合成员最少就用哪个
                Node node = leastLoadedNode(now);
				// 创建并缓存请求MetadataRequest，等待下次poll方法才真正发送
                maybeUpdate(now, node);
            }

            return metadataTimeout;
        }

		private void maybeUpdate(long now, Node node) {
            if (node == null) {
				// 没有可以发送更新请求的node可用
                this.lastNoNodeAvailableMs = now;
                return;
            }
            String nodeConnectionId = node.idString();

			// 检测是否允许向此node发送请求
            if (canSendRequest(nodeConnectionId)) {
                this.metadataFetchInProgress = true;
                MetadataRequest metadataRequest;
                if (metadata.needMetadataForAllTopics())
                    metadataRequest = MetadataRequest.allTopics();
                else
					// 指定topic的元数据更新
                    metadataRequest = new MetadataRequest(new ArrayList<>(metadata.topics()));
				// MetadataRequest 封装成 ClientRequest
                ClientRequest clientRequest = request(now, nodeConnectionId, metadataRequest);
				// 缓存请求到send字段，等到下次poll会发送
                doSend(clientRequest, now); 
            } else if (connectionStates.canConnect(nodeConnectionId, now)) {
				// 初始化连接
                initiateConnect(node, now);
            } else { 
				// 已经成功连接到指定node，但不能发送request，更新lastNodeAvailableMs后等待
                this.lastNoNodeAvailableMs = now;
            }
        }

		// 处理服务端的响应
		@Override
        public boolean maybeHandleCompletedReceive(ClientRequest req, long now, Struct body) {
			// apiKey的类型是METADATA，就处理该响应消息
            short apiKey = req.request().header().apiKey();
            if (apiKey == ApiKeys.METADATA.id && req.isInitiatedByNetworkClient()) {
                handleResponse(req.request().header(), body, now);
                return true;
            }
            return false;
        }

		private void handleResponse(RequestHeader header, Struct body, long now) {
			// 在途MetadataRequest标识更新
            this.metadataFetchInProgress = false;
            MetadataResponse response = new MetadataResponse(body);
            Cluster cluster = response.cluster();
            if (cluster.nodes().size() > 0) {
				// 通知Metadata的监听器更新cluster字段，然后唤醒等待Metadata更新完成的thread
				// 被唤醒的thread应该就是之前Producer主线程
                this.metadata.update(cluster, now);
            }
        }
	}
}

public class Selector implements Selectable {
	// jdk的selector,用来监听网络IO事件
	private final java.nio.channels.Selector nioSelector;
	// nodeId -> KafkaChannel
	// 一个node对应一个channel
    private final Map<String, KafkaChannel> channels;

	// 以下集合都是一次poll轮询记录，因为每次poll开始都会清空这些集合
	// 完全发送出去的请求
    private final List<Send> completedSends;
	// 完全接收到的请求
    private final List<NetworkReceive> completedReceives;
	// 暂存一个OP_READ事件处理过程中读取到的全部请求,OP_READ事件处理完，请求转移到completedReceives
    private final Map<KafkaChannel, Deque<NetworkReceive>> stagedReceives;
	private final Set<SelectionKey> immediatelyConnectedKeys; //在调用SocketChannel#connect方法时立即完成的SelectionKey
	// poll过程中发现的断开的链接
    private final List<String> disconnected;
	// poll过程中发现的新建的链接
    private final List<String> connected;
	// 记录向哪些node发送请求失败了
	// 但并不是由于IO异常导致的失败，而是由于SelectionKey被cancel引起的失败，比如对一个已关闭的channel设置interestOps
    private final List<String> failedSends;
	// 创建KafkaChannel的builder
    private final ChannelBuilder channelBuilder;
	// 记录各个连接的使用情况，并据此关闭空闲时间超过connectionMaxIdleNanos的链接
	// 一个连接太久没有用来执行读写操作，为了降低服务器端的压力，需要释放这些的连接
    private final Map<String, Long> lruConnections;

	// 连接kafka集群的node，创建channel
	@Override
    public void connect(String id, InetSocketAddress address, int sendBufferSize, int receiveBufferSize) throws IOException {
		// 创建channel
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        Socket socket = socketChannel.socket();
		// 设置长连接
        socket.setKeepAlive(true);
        socket.setSendBufferSize(sendBufferSize);
        socket.setReceiveBufferSize(receiveBufferSize);
        socket.setTcpNoDelay(true);
		// 这里是非阻塞立即返回，需要通过finishConnect方法确认连接建立
        boolean connected = socketChannel.connect(address);
		// 注册OP_CONNECT，后面连接完成会通知
        SelectionKey key = socketChannel.register(nioSelector, SelectionKey.OP_CONNECT);
		// 创建一个KafkaChannel
        KafkaChannel channel = channelBuilder.buildChannel(id, key, maxReceiveSize);
        key.attach(channel);
        this.channels.put(id, channel);
    }

	@Override
    public void poll(long timeout) throws IOException {
		// 清除上一次poll的结果
        clear();

        long startSelect = time.nanoseconds();
        int readyKeys = select(timeout);

        if (readyKeys > 0 || !immediatelyConnectedKeys.isEmpty()) {
			// 处理已经就绪的selector key集合
            pollSelectionKeys(this.nioSelector.selectedKeys(), false);
            pollSelectionKeys(immediatelyConnectedKeys, true);
        }

		// 处理完从stage移到completed
        addToCompletedReceives();
		// 关闭长时间空闲的连接,依据是lruConnections里记录的modified时间戳
        maybeCloseOldestConnection();
    }

	// 清空上一轮poll保存的集合
	private void clear() {
        this.completedSends.clear();
        this.completedReceives.clear();
        this.connected.clear();
        this.disconnected.clear();
		// 这里之所以把failedSends加到disconnected之中，是因为failedSends里保存的失败的send，并不是上次poll留下来的，
		// 而是上次poll之后，此次poll之前，调用send方法时添加到failedSends集合中的。
		// 当有failedSends时，selector就会关闭这个channel，因此在clear过程中，需要把failedSends里保存的节点加到disconnected之中。
        this.disconnected.addAll(this.failedSends);
        this.failedSends.clear();
    }

	// 处理selector返回的就绪keys
	private void pollSelectionKeys(Iterable<SelectionKey> selectionKeys, boolean isImmediatelyConnected) {
        Iterator<SelectionKey> iterator = selectionKeys.iterator();
        while (iterator.hasNext()) {
            SelectionKey key = iterator.next();
            iterator.remove();
            KafkaChannel channel = channel(key);
			// 更新该channel的modified时间,闲置时长
            lruConnections.put(channel.id(), currentTimeNanos);

			if (isImmediatelyConnected || key.isConnectable()) {
				// 在OP_CONNECT触发后，调用SocketChannel.finishConnect成功后，连接才真正建立
				// 如果我们不调用该方法，就去调用read/write方法，则会抛出一个NotYetConnectedException异常。
				if (channel.finishConnect()) 
					// 确认连接建立成功
					this.connected.add(channel.id());
			}

			if (channel.isConnected() && !channel.ready())
				// 身份验证
				channel.prepare();

			// Read就绪
			if (channel.ready() && key.isReadable() && !hasStagedReceive(channel)) {
				while ((networkReceive = channel.read()) != null)
					addToStagedReceives(channel, networkReceive);
			}

			// Write就绪
			if (channel.ready() && key.isWritable()) {
				// 将KafkaChannel的send字段发送出去
				// 实质调用的是JDK NIO里的SocketChannel.write(byteBuffer[])
				Send send = channel.write();
				if (send != null) {
					this.completedSends.add(send);
				}
			}
        }
    }
}

public class KafkaChannel {
	// 根据网络协议不同，提供不同的子类
	// 默认是PlaintextTransportLayer子类
    private final TransportLayer transportLayer;
	// 读写缓存ByteBuffer
    private NetworkReceive receive;
	// 正在处理待发送的请求
    private Send send;

	// 消息写入send缓存
	public void setSend(Send send) {
		if (this.send != null)
			// send不为空表示有请求待发送，不能覆盖抛出异常
            throw new IllegalStateException("Attempt to begin a send operation with prior send operation still in progress.");
		// 数据写入send字段
        this.send = send;
		// 有消息待发送了，关注OP_WRITE
        this.transportLayer.addInterestOps(SelectionKey.OP_WRITE);
    }

	// 发送send缓存里的消息
	private boolean send(Send send) throws IOException {
        send.writeTo(transportLayer);
        if (send.completed())
			// 发送完消息后取消关注OP_WRITE
            transportLayer.removeInterestOps(SelectionKey.OP_WRITE);

        return send.completed();
    }

	// 读消息到receive缓存
	public NetworkReceive read() throws IOException {
        NetworkReceive result;
        receive(receive);
        if (receive.complete()) {
			// 读取完成后，缓存取出NetworkReceive，然后清空缓存
            receive.payload().rewind();
            result = receive;
            receive = null;
        }
        return result;
    }
}

public class PlaintextTransportLayer implements TransportLayer {

	// NIO网络通讯的2个组件：selectionKey和socketChannel
	private final SelectionKey key;
	private final SocketChannel socketChannel;

	@Override
    public boolean finishConnect() throws IOException {
		// NIO连接建立后，必须要调用SocketChannel.finishConnect，否则读写该channel会报错
        boolean connected = socketChannel.finishConnect();
        if (connected)
			// 建立成功后不关心OP_CONNECT，增加关心OP_READ
			// 连接建立以后，个人感觉这个OP_READ基本不会取消关注
            key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT | SelectionKey.OP_READ);
        return connected;
    }
}

final class InFlightRequests {
	// 每个连接最多缓存的ClientRequest个数
	private final int maxInFlightRequestsPerConnection;

	// nodeId -> ClientRequest集合
	private final Map<String, Deque<ClientRequest>> requests = new HashMap<String, Deque<ClientRequest>>();

	// 是否能向指定node发送请求
	public boolean canSendMore(String node) {
        Deque<ClientRequest> queue = requests.get(node);
        return queue == null || queue.isEmpty() ||
				// 队头的请求指定的就是KafkaChannel的send字段，为了避免消息未发送就被覆盖，因为调用send就会覆盖KafkaChannel的send字段
               (queue.peekFirst().request().completed() && 
				queue.size() < this.maxInFlightRequestsPerConnection);
    }
}

// 请求消息报文头
public class RequestHeader extends AbstractRequestResponse {
	// api标识
	private final short apiKey;
	// api版本号
    private final short apiVersion;
    private final String clientId;
	// client产生，服务端不做任何修改在response中会回传给client
    private final int correlationId;
}

public class ProduceRequest extends AbstractRequest {

	// 指定服务端响应此request前，需要有多少个replication成功复制了该请求的消息，-1表示整个ISR都完成
    private static final String ACKS_KEY_NAME = "acks";
    private static final String TIMEOUT_KEY_NAME = "timeout";

    // topic名
    private static final String TOPIC_KEY_NAME = "topic";

    // partition名称
    private static final String PARTITION_KEY_NAME = "partition";
	// 消息的有效负载:即ByteBuffer
    private static final String RECORD_SET_KEY_NAME = "record_set";
}

// 应答报文头
public class ResponseHeader extends AbstractRequestResponse {
	// 透传请求报文头的correlationId
    private final int correlationId;
}

public class ProduceResponse extends AbstractRequestResponse {

    // topic名称
    private static final String TOPIC_KEY_NAME = "topic";
	// 延迟时长
    private static final String THROTTLE_TIME_KEY_NAME = "throttle_time_ms";

    // partition名称
    private static final String PARTITION_KEY_NAME = "partition";
	// 异常码
    private static final String ERROR_CODE_KEY_NAME = "error_code";

	// 服务端为消息生成的偏移量
    private static final String BASE_OFFSET_KEY_NAME = "base_offset";
	// 服务端产生的时间戳
    private static final String TIMESTAMP_KEY_NAME = "timestamp";
}
