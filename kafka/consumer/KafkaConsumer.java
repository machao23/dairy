public class KafkaConsumer<K, V> implements Consumer<K, V> {
	// clientId的生成器
	private static final AtomicInteger CONSUMER_CLIENT_ID_SEQUENCE = new AtomicInteger(1);
	// comsumer的唯一id
	private final String clientId;
	// 控制着consumer和服务端Coordinator之间的通信逻辑
	private final ConsumerCoordinator coordinator;
	private final Deserializer<K> keyDeserializer;
	private final Deserializer<V> valueDeserializer;
	// poll方法返回用户之前拦截，服务端返回commit响应时拦截
	private final ConsumerInterceptors<K, V> interceptors;
	// 负责consumer与broker之间的通信
	private final ConsumerNetworkClient client;
	// 维护消费者的消费状态
	private final SubscriptionState subscriptions;
	// Kafka集群元信息
	private final Metadata metadata;
	// 当前使用KafkaConsumer的线程id
	private final AtomicLong currentThread = new AtomicLong(NO_CURRENT_THREAD);
	// 重入次数
	// 检测是否有多线程并发操作consumer
	private final AtomicInteger refcount = new AtomicInteger(0);
}

public class ConsumerNetworkClient implements Closeable {
	// NetworkClient
	private final KafkaClient client;
	// consumer之外的thread设置，表示要中断consumer线程
	private final AtomicBoolean wakeup = new AtomicBoolean(false);
	// 定时任务队列，主要是心跳任务
	// 底层实现是PriorityQueue
	private final DelayedTaskQueue delayedTasks = new DelayedTaskQueue();
	// 缓冲队列
	private final Map<Node, List<ClientRequest>> unsent = new HashMap<>();
	// 集群元数据
	private final Metadata metadata;
	// 在unset中缓存的超时时长
	private final long unsentExpiryMs;
	// consumer每进入一个不可中断的method加1，退出时减1
	private int wakeupDisabledCount = 0;

	// 待发送的请求封装成ClientRequest，然后保存到unsent
	public RequestFuture<ClientResponse> send(Node node, ApiKeys api, AbstractRequest request) {
        long now = time.milliseconds();
        RequestFutureCompletionHandler future = new RequestFutureCompletionHandler();
        RequestHeader header = client.nextRequestHeader(api);
        RequestSend send = new RequestSend(node.idString(), header, request.toStruct());
        put(node, new ClientRequest(now, true, send, future));
        return future;
    }

	public void poll(RequestFuture<?> future) {
        while (!future.isDone()) // 同步阻塞等待future完成响应
            poll(Long.MAX_VALUE);
    }

	private void poll(long timeout, long now, boolean executeDelayedTasks) {
        // 遍历处理unsent缓存中的请求
        trySend(now);

		// 比较取最小值，避免影响定时任务执行
        timeout = Math.min(timeout, delayedTasks.nextTimeout(now));
		// 实际发送请求,检测wakeup标识为true就抛出异常中断consumer.poll方法
        clientPoll(timeout, now);
        now = time.milliseconds();

		// 如果连接断开，从unsent队列里删除后，再调用这些request的callback
        checkDisconnects(now);

		// 执行定时任务
        if (executeDelayedTasks)
            delayedTasks.poll(now);

		// 可能已经新建了某些node的连接，再尝试一把
        trySend(now);

        // 遍历unsent中已经超时的request,执行callback，然后从unsent里删除
        failExpiredRequests(now);
    }

	private boolean trySend(long now) {
        boolean requestsSent = false;
        for (Map.Entry<Node, List<ClientRequest>> requestEntry: unsent.entrySet()) {
            Node node = requestEntry.getKey();
            Iterator<ClientRequest> iterator = requestEntry.getValue().iterator();
            while (iterator.hasNext()) {
                ClientRequest request = iterator.next();
				// 检测连接、在途请求队列数量
                if (client.ready(node, now)) {
					// 复制到KafkaChannel的send
                    client.send(request, now);
                    iterator.remove();
                    requestsSent = true;
                }
            }
        }
        return requestsSent;
    }

	// 设置MAX超时时长，同步阻塞等待
	public void awaitMetadataUpdate() {
        int version = this.metadata.requestUpdate();
        do {
            poll(Long.MAX_VALUE);
        } while (this.metadata.version() == version);
    }

	// 等待unsent和InFlightRequests中的请求全部完成
	public void awaitPendingRequests(Node node) {
        while (pendingRequestCount(node) > 0)
            poll(retryBackoffMs);
    }

	public static class RequestFutureCompletionHandler extends RequestFuture<ClientResponse> implements RequestCompletionHandler {

		// 请求是否已经完成
		private boolean isDone = false;
		// 成功响应,与exception互斥
		private T value;
		// 导致异常的类
		private RuntimeException exception;
		// 监听请求完成的情况，onSucess和onFailure方法
		private List<RequestFutureListener<T>> listeners = new ArrayList<>();

        @Override
        public void onComplete(ClientResponse response) {
            if (response.wasDisconnected()) {
                ClientRequest request = response.request();
                RequestSend send = request.request();
                ApiKeys api = ApiKeys.forId(send.header().apiKey());
                int correlation = send.header().correlationId();
                raise(DisconnectException.INSTANCE);
            } else {
                complete(response);
            }
        }

		// 适配将本实例的泛型类型T转换成S
		public <S> RequestFuture<S> compose(final RequestFutureAdapter<T, S> adapter) {
			final RequestFuture<S> adapted = new RequestFuture<S>();
			addListener(new RequestFutureListener<T>() {
				@Override
				public void onSuccess(T value) {
					adapter.onSuccess(value, adapted);
				}

				@Override
				public void onFailure(RuntimeException e) {
					adapter.onFailure(e, adapted);
				}
			});
			return adapted;
		}
    }
}

public class SubscriptionState {

    private enum SubscriptionType {
        NONE, 
		AUTO_TOPICS,  // 指定topic名
		AUTO_PATTERN,  // 正则匹配topic名
		USER_ASSIGNED // 用户指定
    };

	private SubscriptionType subscriptionType; // 表示订阅的模式
	private Pattern subscribedPattern; // 正则匹配模式的表达式
	private final Set<String> subscription; // 所有订阅的topic名
	private final Set<String> groupSubscription; // 只有consumerGroup的leader才有，记录该consumerGroup订阅的所有topic; follower只有自己订阅的topic
	private final Set<TopicPartition> userAssignment; // 手动分配给consumer的topicPartition集合，与subscription互斥
	// 记录每个topicPartition的消费状况
	private final Map<TopicPartition, TopicPartitionState> assignment;
	private boolean needsPartitionAssignment; // 是否需要进行一次分区分配
	private boolean needsFetchCommittedOffsets; // 是否需要拉取offset，在异步提交offset或rebalance分区时候会设置成true
	private final OffsetResetStrategy defaultResetStrategy; // 重置offset策略
	private ConsumerRebalanceListener listener; // 监听分区分配操作

	private static class TopicPartitionState {
		private Long position; // 最近消费消息的offset
		private OffsetAndMetadata committed; // 最近commit的offset
		private boolean paused; // 是否处于暂停状态
		private OffsetResetStrategy resetStrategy; // 重置offset的策略
	}

	// 缺省的listener是 NoOpConsumerRebalanceListener
	public void subscribe(Collection<String> topics, ConsumerRebalanceListener listener) {
        setSubscriptionType(SubscriptionType.AUTO_TOPICS);
        this.listener = listener;
		// 更新subscription、groupSubscription、needsPartitionAssignment=true
        changeSubscription(topics);
    }
}

public final class ConsumerCoordinator extends AbstractCoordinator {
	// consumer发送的JoinGroupRequest中包含了自身支持的PartitionAssigner,
	// GroupCoordinator从所有consumer的分配策略里选择一个,通知leader使用此策略做分区分配
	private final List<PartitionAssignor> assignors; 
	private final Metadata metadata;
	private final SubscriptionState subscriptions;
	private final boolean autoCommitEnabled;
	private final AutoCommitTask autoCommitTask; // 自动提交offset的定时任务
	private final ConsumerInterceptors<?, ?> interceptors;
	private final boolean excludeInternalTopics; // 是否排除内部topic
	// 用来检测topic是否发生了分区数量的变化
	private MetadataSnapshot metadataSnapshot;

	// 构造方法
	public ConsumerCoordinator(ConsumerNetworkClient client, String groupId, int sessionTimeoutMs, int heartbeatIntervalMs,
                               List<PartitionAssignor> assignors, Metadata metadata, SubscriptionState subscriptions,
                               Metrics metrics, String metricGrpPrefix, Time time, long retryBackoffMs,
                               OffsetCommitCallback defaultOffsetCommitCallback, boolean autoCommitEnabled,
                               long autoCommitIntervalMs, ConsumerInterceptors<?, ?> interceptors, boolean excludeInternalTopics) {
        super(client, groupId, sessionTimeoutMs, heartbeatIntervalMs, metrics, metricGrpPrefix, time, retryBackoffMs);
        this.metadata = metadata;

        this.metadata.requestUpdate();
        this.metadataSnapshot = new MetadataSnapshot(subscriptions, metadata.fetch());
        this.subscriptions = subscriptions;
        this.defaultOffsetCommitCallback = defaultOffsetCommitCallback;
        this.autoCommitEnabled = autoCommitEnabled;
        this.assignors = assignors;
		// 添加metadata更新监听
        addMetadataListener();

        if (autoCommitEnabled) {
            this.autoCommitTask = new AutoCommitTask(autoCommitIntervalMs);
            this.autoCommitTask.reschedule();
        } else {
            this.autoCommitTask = null;
        }

        this.interceptors = interceptors;
        this.excludeInternalTopics = excludeInternalTopics;
    }

	// Metadata更新监听
	private void addMetadataListener() {
        this.metadata.addListener(new Metadata.Listener() {
            @Override
            public void onMetadataUpdate(Cluster cluster) {
				// 正则匹配topic模式
                if (subscriptions.hasPatternSubscription()) {

                    final List<String> topicsToSubscribe = new ArrayList<>();
                    for (String topic : cluster.topics())
                        if (filterTopic(topic)) // 正则匹配
                            topicsToSubscribe.add(topic);
					// 更新subscription、groupScription集合、assignment集合
                    subscriptions.changeSubscription(topicsToSubscribe);
					// 更新元信息的topic集合
                    metadata.setTopics(subscriptions.groupSubscription());
                } else if (!cluster.unauthorizedTopics().isEmpty()) {
                    throw new TopicAuthorizationException(new HashSet<>(cluster.unauthorizedTopics()));
                }

				// 非手动，即AUTO_TOPICS或AUTO_PATTERN
                if (subscriptions.partitionsAutoAssigned()) {
                    MetadataSnapshot snapshot = new MetadataSnapshot(subscriptions, cluster);
					// metadataSnapshot底层是map: topic -> partition数量
					// 不相等说明分区产生了变化，需要rebalance
                    if (!snapshot.equals(metadataSnapshot)) {
                        metadataSnapshot = snapshot;
                        subscriptions.needReassignment();
                    }
                }

            }
        });
    }

	// JoinGroup的入口,即rebalance
	public void ensurePartitionAssignment() {
		// 只有自动分配分区的才需要rebalance
        if (subscriptions.partitionsAutoAssigned()) {
            if (subscriptions.hasPatternSubscription())
				// 订阅是正则匹配模式，还需要检查是否需要更新Metadata
				// 防止使用过期的Metadata进行rebalance
                client.ensureFreshMetadata();

            ensureActiveGroup();
        }
    }

	@Override
    protected void onJoinPrepare(int generation, String memberId) {
        // 如果开启了自动提交offset，则同步提交offset
        maybeAutoCommitOffsetsSync();

        ConsumerRebalanceListener listener = subscriptions.listener();
		Set<TopicPartition> revoked = new HashSet<>(subscriptions.assignedPartitions());
		// 调用分区重新分配的callback
		listener.onPartitionsRevoked(revoked);

        assignmentSnapshot = null;
		// groupSubscription收缩到自身的subscription
		// needsPartitionAssignment=true
        subscriptions.needReassignment();
    }

	// 收到JoinGroupResponse后，被指定为join leader的consumer，执行分配策略
	@Override
    protected Map<String, ByteBuffer> performAssignment(String leaderId,
                                                        String assignmentStrategy,
                                                        Map<String, ByteBuffer> allSubscriptions) {
		// 默认是range分配策略
        PartitionAssignor assignor = lookupAssignor(assignmentStrategy);

        Set<String> allSubscribedTopics = new HashSet<>();
        Map<String, Subscription> subscriptions = new HashMap<>();
        for (Map.Entry<String, ByteBuffer> subscriptionEntry : allSubscriptions.entrySet()) {
            Subscription subscription = ConsumerProtocol.deserializeSubscription(subscriptionEntry.getValue());
            subscriptions.put(subscriptionEntry.getKey(), subscription);
            allSubscribedTopics.addAll(subscription.topics());
        }

        // leader需要更新整个consumer group的订阅topic
		// 可能有新的topic加入，需要更新Metadata
        this.subscriptions.groupSubscribe(allSubscribedTopics);
        metadata.setTopics(this.subscriptions.groupSubscription());

        client.ensureFreshMetadata();
        assignmentSnapshot = metadataSnapshot;

		// 默认调用RangeAssignor
		// 分配结果: memberId -> 分配结果
        Map<String, Assignment> assignment = assignor.assign(metadata.fetch(), subscriptions);
        Map<String, ByteBuffer> groupAssignment = new HashMap<>();
        for (Map.Entry<String, Assignment> assignmentEntry : assignment.entrySet()) {
            ByteBuffer buffer = ConsumerProtocol.serializeAssignment(assignmentEntry.getValue());
            groupAssignment.put(assignmentEntry.getKey(), buffer);
        }

        return groupAssignment;
    }

	// 处理SyncGroupResponse
	@Override
    protected void onJoinComplete(int generation, String memberId, String assignmentStrategy, ByteBuffer assignmentBuffer) {
		// 快照与最新的不一致，需要重新分区Assign
        if (assignmentSnapshot != null && !assignmentSnapshot.equals(metadataSnapshot)) {
            subscriptions.needReassignment();
            return;
        }

        PartitionAssignor assignor = lookupAssignor(assignmentStrategy);
        Assignment assignment = ConsumerProtocol.deserializeAssignment(assignmentBuffer);

        // 从服务端获取最近一次的offset标识
        subscriptions.needRefreshCommits();

        // 更新当前consumer订阅的topic
        subscriptions.assignFromSubscribed(assignment.partitions());

        // 重新启动AutoCommitTask定时任务
        if (autoCommitEnabled)
            autoCommitTask.reschedule();

        // rebalance后执行callback
        ConsumerRebalanceListener listener = subscriptions.listener();
		Set<TopicPartition> assigned = new HashSet<>(subscriptions.assignedPartitions());
		listener.onPartitionsAssigned(assigned);
    }
}

public class RangeAssignor extends AbstractPartitionAssignor {
	@Override
    public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                    Map<String, List<String>> subscriptions) {
        for (Map.Entry<String, List<String>> topicEntry : consumersPerTopic.entrySet()) {

            Collections.sort(consumersForTopic);

			// 每个consumer订阅partition数量
            int numPartitionsPerConsumer = numPartitionsForTopic / consumersForTopic.size();
			// 除不尽余数的partition单独分配给consumer
            int consumersWithExtraPartition = numPartitionsForTopic % consumersForTopic.size();

            List<TopicPartition> partitions = AbstractPartitionAssignor.partitions(topic, numPartitionsForTopic);
            for (int i = 0, n = consumersForTopic.size(); i < n; i++) {
                int start = numPartitionsPerConsumer * i + Math.min(i, consumersWithExtraPartition);
                int length = numPartitionsPerConsumer + (i + 1 > consumersWithExtraPartition ? 0 : 1);
                assignment.get(consumersForTopic.get(i)).addAll(partitions.subList(start, start + length));
            }
        }
        return assignment;
    }
}

public abstract class AbstractPartitionAssignor implements PartitionAssignor {
	// 完成partition分配
	@Override
    public Map<String, Assignment> assign(Cluster metadata, Map<String, Subscription> subscriptions) {
        Set<String> allSubscribedTopics = new HashSet<>();
        Map<String, List<String>> topicSubscriptions = new HashMap<>();
		// 父类默认是去掉userData不处理的
		// 如果子类需要用到userData，就要自己实现PartitionAssignor接口的assign方法
        for (Map.Entry<String, Subscription> subscriptionEntry : subscriptions.entrySet()) {
            List<String> topics = subscriptionEntry.getValue().topics();
            allSubscribedTopics.addAll(topics);
            topicSubscriptions.put(subscriptionEntry.getKey(), topics);
        }

		// 统计每个topic的分区数量
        Map<String, Integer> partitionsPerTopic = new HashMap<>();
        for (String topic : allSubscribedTopics) {
            Integer numPartitions = metadata.partitionCountForTopic(topic);
            if (numPartitions != null && numPartitions > 0)
                partitionsPerTopic.put(topic, numPartitions);
        }


        Map<String, List<TopicPartition>> rawAssignments = assign(partitionsPerTopic, topicSubscriptions);

        Map<String, Assignment> assignments = new HashMap<>();
        for (Map.Entry<String, List<TopicPartition>> assignmentEntry : rawAssignments.entrySet())
            assignments.put(assignmentEntry.getKey(), new Assignment(assignmentEntry.getValue()));
        return assignments;
    }
}

public interface PartitionAssignor {

	// 每个member的订阅信息
	class Subscription {
        private final List<String> topics; // 订阅的topic集合
        private final ByteBuffer userData;
	}

	class Assignment {
        private final List<TopicPartition> partitions; // 分区分配的结果
        private final ByteBuffer userData;
	}
}

public abstract class AbstractCoordinator implements Closeable {
	private final Heartbeat heartbeat; // 心跳任务的辅助类
	private final HeartbeatTask heartbeatTask; // 定时任务，发送心跳和处理响应
	protected final String groupId; // consumer group id
	protected final ConsumerNetworkClient client; // 网络通信

	private boolean needsJoinPrepare = true; // 是否需要发送joinGroupRequest前的准备操作
	private boolean rejoinNeeded = true; // 是否需要重新发送JoinGroupRequest的条件之一
	
	protected Node coordinator; // 记录服务端GroupCoordinator所在的node节点
	protected String memberId; // 服务端GroupCoordinator返回的分配给consumer的唯一id
	protected int generation; // 可以理解每次rebalance的版本号，避免消费历史的rebalance请求

	private class HeartbeatTask implements DelayedTask {

		// 外部调用触发心跳任务
		public void reset() {
            long now = time.milliseconds();
            heartbeat.resetSessionTimeout(now);
            client.unschedule(this);

            if (!requestInFlight)
                client.schedule(this, now);
        }

		@Override
        public void run(final long now) {
			// 之前的心跳请求正常收到响应
			// 不处于正在等待rebalance分配结果的状态
			// 服务端的GroupCoordinator已连接
            if (generation < 0 || needRejoin() || coordinatorUnknown()) {
                return;
            }

            if (heartbeat.sessionTimeoutExpired(now)) {
				// 心跳超时则认为服务端GroupCoordinator已经宕机
                coordinatorDead();
                return;
            }

            if (!heartbeat.shouldHeartbeat(now)) {
				// 还没到下一次心跳间隔触发时间，不发送请求（等于本次任务结束），
				// 更新下一个触发时间点，再添加一个新的定时任务
                client.schedule(this, now + heartbeat.timeToNextHeartbeat(now));
            } else {
                heartbeat.sentHeartbeat(now);
                requestInFlight = true; // 防止重复发送

				// 发送心跳请求
                RequestFuture<Void> future = sendHeartbeatRequest();
				// 注册该请求收到响应的callback
                future.addListener(new RequestFutureListener<Void>() {
					// 发送完成后新增定时任务调度
                    @Override
                    public void onSuccess(Void value) {
                        requestInFlight = false;
                        long now = time.milliseconds();
                        heartbeat.receiveHeartbeat(now);
                        long nextHeartbeatTime = now + heartbeat.timeToNextHeartbeat(now);
                        client.schedule(HeartbeatTask.this, nextHeartbeatTime);
                    }

                    @Override
                    public void onFailure(RuntimeException e) {
                        requestInFlight = false;
                        client.schedule(HeartbeatTask.this, time.milliseconds() + retryBackoffMs);
                    }
                });
            }
        }
	}

	// 处理心跳响应
	private class HeartbeatCompletionHandler extends CoordinatorResponseHandler<HeartbeatResponse, Void> {

        @Override
        public void handle(HeartbeatResponse heartbeatResponse, RequestFuture<Void> future) {
            Errors error = Errors.forCode(heartbeatResponse.errorCode());
            if (error == Errors.NONE) {
				// 成功响应，传播成功事件
                future.complete(null);
            } else if (error == Errors.GROUP_COORDINATOR_NOT_AVAILABLE
                    || error == Errors.NOT_COORDINATOR_FOR_GROUP) {
                coordinatorDead();
                future.raise(error);
            } else if (error == Errors.REBALANCE_IN_PROGRESS) {
				// 说明coordinator已经发起了rebalance
				// 触发发送JoinGroupRequest的标识
                AbstractCoordinator.this.rejoinNeeded = true;
                future.raise(Errors.REBALANCE_IN_PROGRESS);
            } else if (error == Errors.ILLEGAL_GENERATION) {
                AbstractCoordinator.this.rejoinNeeded = true;
                future.raise(Errors.ILLEGAL_GENERATION);
            } else if (error == Errors.UNKNOWN_MEMBER_ID) {
                memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID;
                AbstractCoordinator.this.rejoinNeeded = true;
                future.raise(Errors.UNKNOWN_MEMBER_ID);
                future.raise(new GroupAuthorizationException(groupId));
            } else {
                future.raise(new KafkaException("Unexpected error in heartbeat response: " + error.message()));
            }
        }
    }

	protected void coordinatorDead() {
        if (this.coordinator != null) {
			// unsent缓存中的请求清空，并且调用异常的回调
            client.failUnsentRequests(this.coordinator, GroupCoordinatorNotAvailableException.INSTANCE);
			// 表示重新选择GroupCoordinator
            this.coordinator = null;
        }
    }

	// 查找服务端GroupCoordinator入口
	public void ensureCoordinatorReady() {
        while (coordinatorUnknown()) {
            RequestFuture<Void> future = sendGroupCoordinatorRequest();
			// 阻塞等待future有响应
            client.poll(future);

            if (future.failed()) {
                if (future.isRetriable())
                    client.awaitMetadataUpdate();
                else
                    throw future.exception();
            } else if (coordinator != null && client.connectionFailed(coordinator)) {
                coordinatorDead();
                time.sleep(retryBackoffMs);
            }

        }
    }

	// 处理服务端返回查找GroupCoordinator的应答
	// 赋值coordinator字段，连接coordinator，启动心跳任务
	private void handleGroupMetadataResponse(ClientResponse resp, RequestFuture<Void> future) {

        if (!coordinatorUnknown()) {
			// consumer已经找到GroupCoordinator了，不处理这个应答
            future.complete(null);
        } else {
            GroupCoordinatorResponse groupCoordinatorResponse = new GroupCoordinatorResponse(resp.responseBody());
            Errors error = Errors.forCode(groupCoordinatorResponse.errorCode());
            if (error == Errors.NONE) {
                this.coordinator = new Node(Integer.MAX_VALUE - groupCoordinatorResponse.node().id(),
                        groupCoordinatorResponse.node().host(),
                        groupCoordinatorResponse.node().port());

                client.tryConnect(coordinator);

                if (generation > 0)
                    heartbeatTask.reset();
                future.complete(null);
            } else if (error == Errors.GROUP_AUTHORIZATION_FAILED) {
                future.raise(new GroupAuthorizationException(groupId));
            } else {
                future.raise(error);
            }
        }
    }

	public void ensureActiveGroup() {
        if (!needRejoin())
            return;

        if (needsJoinPrepare) {
            onJoinPrepare(generation, memberId);
            needsJoinPrepare = false;
        }

        while (needRejoin()) {
			// 检查已经连接服务端的groupCoordinator
            ensureCoordinatorReady();

			// 如果还有发送给GroupCoordinator的请求，阻塞等待这些请求收到响应
			// 即等待unsent和InFlightRequests队列为空
            if (client.pendingRequestCount(this.coordinator) > 0) {
                client.awaitPendingRequests(this.coordinator);
                continue;
            }

            RequestFuture<ByteBuffer> future = sendJoinGroupRequest();
            future.addListener(new RequestFutureListener<ByteBuffer>() {
                @Override
                public void onSuccess(ByteBuffer value) {
                    onJoinComplete(generation, memberId, protocol, value);
                    needsJoinPrepare = true;
                    heartbeatTask.reset();
                }

                @Override
                public void onFailure(RuntimeException e) {
                }
            });
            client.poll(future);

            if (future.failed()) {
                RuntimeException exception = future.exception();
                if (exception instanceof UnknownMemberIdException ||
                        exception instanceof RebalanceInProgressException ||
                        exception instanceof IllegalGenerationException)
                    continue;
                else if (!future.isRetriable())
                    throw exception;
				// 通过sleep控制重试间隔
                time.sleep(retryBackoffMs);
            }
        }
    }

	// JoinGroupRequest设置到sent字段里
	private RequestFuture<ByteBuffer> sendJoinGroupRequest() {
        JoinGroupRequest request = new JoinGroupRequest( groupId, this.sessionTimeoutMs,
                this.memberId, protocolType(), metadata());

        return client.send(coordinator, ApiKeys.JOIN_GROUP, request)
                .compose(new JoinGroupResponseHandler());
    }

	// 处理JoinGroupResponse
	private class JoinGroupResponseHandler extends CoordinatorResponseHandler<JoinGroupResponse, ByteBuffer> {
		@Override
        public void handle(JoinGroupResponse joinResponse, RequestFuture<ByteBuffer> future) {
            Errors error = Errors.forCode(joinResponse.errorCode());
            if (error == Errors.NONE) {
				// 更新本地信息
                AbstractCoordinator.this.memberId = joinResponse.memberId();
                AbstractCoordinator.this.generation = joinResponse.generationId();
                AbstractCoordinator.this.rejoinNeeded = false;
                AbstractCoordinator.this.protocol = joinResponse.groupProtocol();
				// 判断自己是不是join leader
                if (joinResponse.isLeader()) {
                    onJoinLeader(joinResponse).chain(future);
                } else {
                    onJoinFollower().chain(future);
                }
            } else if (error == Errors.GROUP_LOAD_IN_PROGRESS) {
				// 重试
                future.raise(error);
            } else if (error == Errors.UNKNOWN_MEMBER_ID) {
                AbstractCoordinator.this.memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID;
                future.raise(Errors.UNKNOWN_MEMBER_ID);
            } else if (error == Errors.GROUP_COORDINATOR_NOT_AVAILABLE
                    || error == Errors.NOT_COORDINATOR_FOR_GROUP) {
                coordinatorDead();
                future.raise(error);
            } else if (error == Errors.INCONSISTENT_GROUP_PROTOCOL
                    || error == Errors.INVALID_SESSION_TIMEOUT
                    || error == Errors.INVALID_GROUP_ID) {
                log.error("Attempt to join group {} failed due to fatal error: {}", groupId, error.message());
                future.raise(error);
            } else if (error == Errors.GROUP_AUTHORIZATION_FAILED) {
                future.raise(new GroupAuthorizationException(groupId));
            } else {
                future.raise(new KafkaException("Unexpected error in join group response: " + error.message()));
            }
        }

		// join leader的逻辑
		private RequestFuture<ByteBuffer> onJoinLeader(JoinGroupResponse joinResponse) {
            // 执行分配
            Map<String, ByteBuffer> groupAssignment = performAssignment(joinResponse.leaderId(), joinResponse.groupProtocol(),
                    joinResponse.members());
			// 发送请求
            SyncGroupRequest request = new SyncGroupRequest(groupId, generation, memberId, groupAssignment);
            return sendSyncGroupRequest(request);
		}
	}
}

public final class Heartbeat {
    private final long timeout; // 过期时间
    private final long interval; // 2次心跳的间隔，缺省3000

    private long lastHeartbeatSend; // 最后发送心跳请求的时间
    private long lastHeartbeatReceive; // 最后收到心跳响应的时间
    private long lastSessionReset; //心跳重置时间

	// 计算下次心跳发送时间
	public long timeToNextHeartbeat(long now) {
        long timeSinceLastHeartbeat = now - Math.max(lastHeartbeatSend, lastSessionReset);

        if (timeSinceLastHeartbeat > interval)
            return 0;
        else
            return interval - timeSinceLastHeartbeat;
    }

	// 判断是否超时
    public boolean sessionTimeoutExpired(long now) {
        return now - Math.max(lastSessionReset, lastHeartbeatReceive) > timeout;
    }
}

// 从服务端拉取消息
public class Fetcher<K, V> {
	private final ConsumerNetworkClient client;
}
