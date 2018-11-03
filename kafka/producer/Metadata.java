public final class Metadata {

	// 两次发出更新Cluster的元数据信息的最小时间差
    private final long refreshBackoffMs;
	// 每隔多久更新1次
    private final long metadataExpireMs;
	// 更新一次加1
    private int version;
	// 上一次更新的时间戳
	private long lastRefreshMs;
    private long lastSuccessfulRefreshMs;
	// 集群的元数据
    private Cluster cluster;
	// 是否强制更新Cluster，触发Sender线程更新元数据的条件之一
    private boolean needUpdate;
	// 集群里所有的topic
    private final Set<String> topics;
	// 监听Metadata更新的监听器集合,在更新cluster之前会通知全部listener对象
    private final List<Listener> listeners;
	// 是否需要更新全部topic的元数据，一般只维护producer用到的topic
    private boolean needMetadataForAllTopics

	// 请求更新元数据
	public synchronized int requestUpdate() {
        this.needUpdate = true;
        return this.version;
    }

	// 等待更新完成
	public synchronized void awaitUpdate(final int lastVersion, final long maxWaitMs) throws InterruptedException {
        long begin = System.currentTimeMillis();
        long remainingWaitMs = maxWaitMs;
		// 根据版本号确定是否更新完成
        while (this.version <= lastVersion) {
            if (remainingWaitMs != 0)
				// wait后等待sender线程notify唤醒
                wait(remainingWaitMs);
            long elapsed = System.currentTimeMillis() - begin;
            if (elapsed >= maxWaitMs)
                throw new TimeoutException("Failed to update metadata after " + maxWaitMs + " ms.");
            remainingWaitMs = maxWaitMs - elapsed;
        }
    }
}

// Cluster主要是建立各个映射关系，方便关联查询
public final class Cluster {
	private final List<Node> nodes;
    private final Set<String> unauthorizedTopics;
    private final Map<TopicPartition, PartitionInfo> partitionsByTopicPartition;
	// 一个topic对应多个partition
    private final Map<String, List<PartitionInfo>> partitionsByTopic;
    private final Map<String, List<PartitionInfo>> availablePartitionsByTopic;
	// 一个Node对应多个partition
    private final Map<Integer, List<PartitionInfo>> partitionsByNode;
	// 一个brokerId对应一个Node
    private final Map<Integer, Node> nodesById;
}

// Node表示集群中的一个节点,记录节点的host、ip、port等信息
public class Node {

    private final int id;
    private final String idString;
    private final String host;
    private final int port;
    private final String rack;
}

// 表示某topic下的一个分区
public final class TopicPartition implements Serializable {

	// 此分区在topic中的分区编号id
    private final int partition;
	// topic名称
    private final String topic;
}

// 分区的详细信息
public class PartitionInfo {

    private final String topic;
    private final int partition;
    private final Node leader;
    private final Node[] replicas;
    private final Node[] inSyncReplicas;
}
