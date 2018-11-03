public class ProducerInterceptors<K, V> implements Closeable {
	// 拦截器集合
	private final List<ProducerInterceptor<K, V>> interceptors;

	// 发送消息时被调用
	public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        ProducerRecord<K, V> interceptRecord = record;
        for (ProducerInterceptor<K, V> interceptor : this.interceptors) {
			// 循环调用每个拦截器的onSend方法
            interceptRecord = interceptor.onSend(interceptRecord);
        }
        return interceptRecord;
    }

	// 收到ACK时被调用
	public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        for (ProducerInterceptor<K, V> interceptor : this.interceptors) {
            interceptor.onAcknowledgement(metadata, exception);
        }
    }
}
