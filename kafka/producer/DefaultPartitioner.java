// KafkaProducer缺省的分区器
public class DefaultPartitioner implements Partitioner {
	// producer级别的计数器，RoundRobin用
	private final AtomicInteger counter = new AtomicInteger(new Random().nextInt());

	// 负责在消息中没有明确指定分区编号时，为producer选择合适的分区
	public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
        List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
        int numPartitions = partitions.size();
        if (keyBytes == null) {
            int nextValue = counter.getAndIncrement();
            List<PartitionInfo> availablePartitions = cluster.availablePartitionsForTopic(topic);
			// 取模后决定使用哪个分区
			int part = DefaultPartitioner.toPositive(nextValue) % availablePartitions.size();
			return availablePartitions.get(part).partition();
        } else {
            // 消息有key就做hash后再取模
            return DefaultPartitioner.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
        }
    }
}
