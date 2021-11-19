// subscribe执行后的流程
// 从下往上，每个subscriber调用自己的publisher.subscribe
// 然后从上往下, 每个publisher调用自己的subscriber.onSubscribe, 让各 Subscriber 知悉，准备开始处理数据
// 然后从下往上，每个subscriber调用自己的publisher.request 通知请求数据
// 然后从上往下, 每个publisher遍历集合元素调用自己的subscriber.onNext
final class FluxMapFuseable<T, R> extends FluxOperator<T, R> implements Fuseable {
	@Override
	public void subscribe(CoreSubscriber<? super R> actual) {
		// 这里的source就是上游publisher
		// 强调一下 Publisher 接口中的 subscribe 方法语有些奇特，
		// 它表示的不是订阅关系，而是被订阅关系。
		// 即 aPublisher.subscribe(aSubscriber) 表示的是 aPublisher 被 aSubscriber 订阅。
		// 通过调用自己上游的publisher.subscribe，递归传递到最上游publisher的subscribe方法
		// 比如最上游Flux.just就是 FluxArray.subscribe
		source.subscribe(new MapFuseableSubscriber<>(actual, mapper));
	}
}

final class FluxArray<T> extends Flux<T> implements Fuseable, SourceProducer<T> {
	// subscribe方法入参s就是这个publisher的下游订阅方
	// 因为FluxArray是最上游了，这里就开始从上往下逐个调用订阅方s的onSubscribe回调了
	public static <T> void subscribe(CoreSubscriber<? super T> s, T[] array) {
		if (s instanceof ConditionalSubscriber) {
			s.onSubscribe(new ArrayConditionalSubscription<>((ConditionalSubscriber<? super T>) s, array));
		}
		else {
			s.onSubscribe(new ArraySubscription<>(s, array));
		}
	}
	
	static final class ArrayConditionalSubscription<T>
			implements InnerProducer<T>, SynchronousSubscription<T> {
		// 最后从下到上逐个调用request，来到这里
		@Override
		public void request(long n) {
			fastPath();
		}
		
		void fastPath() {
			final T[] a = array;
			final int len = a.length;
			final Subscriber<? super T> s = actual;
			// 循环Flux里所有元素，调用subscriber.onNext
			for (int i = index; i != len; i++) {
				T t = a[i];
				s.onNext(t);
			}
			s.onComplete();
		}
	}
}

// 最下游的subscriber，即 subscribe(lambada表达式)
final class LambdaSubscriber<T> implements InnerConsumer<T>, Disposable {
	@Override
	public final void onSubscribe(Subscription s) {
		s.request(Long.MAX_VALUE);
	}
	
	@Override
	public final void onNext(T x) {
		// 这个comsumer 就是Lambda表达式
		consumer.accept(x);
	}
}