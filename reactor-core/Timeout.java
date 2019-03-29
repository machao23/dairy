public abstract class Mono<T> implements Publisher<T> {

	// 超时方法
	public final Mono<T> timeout(Duration timeout, @Nullable Mono<? extends T> fallback, Scheduler timer) {
		// 本质是通过Mono.delay来实现计时，
		// 但为什么会触发onErrorReturn呢？
		final Mono<Long> _timer = Mono.delay(timeout, timer).onErrorReturn(0L);
		// 创建MonoTimeout实例
		return onAssembly(new MonoTimeout<>(this, _timer, timeout.toMillis() + "ms"));
	}
}

final class MonoTimeout<T, U, V> extends MonoOperator<T, T> {
	// 即Mono.delay.onErrorReturn返回的Mono对象
	// 实际类型是 MonoOnErrorResume
	final Publisher<U> firstTimeout;

	public void subscribe(CoreSubscriber<? super T> actual) {

		CoreSubscriber<T> serial = Operators.serialize(actual);

		// 创建 TimeoutMainSubscriber 时，传入serial即主流程subscriber，建立超时和主流程的关系
		FluxTimeout.TimeoutMainSubscriber<T, V> main =
				new FluxTimeout.TimeoutMainSubscriber<>(serial, NEVER, other,
						addNameToTimeoutDescription(source, timeoutDescription));

		// 每个中间publisher具有上游身份，TimeoutMainSubscriber 实现了subscription，
		// 提供给下游的subscrier做request请求数据处理
		serial.onSubscribe(main);
		
		// 创建ts订阅者的时候，传入main主流程的订阅者，建立2个subscriber的关系
		FluxTimeout.TimeoutTimeoutSubscriber ts =
				new FluxTimeout.TimeoutTimeoutSubscriber(main, 0L);

		main.setTimeout(ts);
		// ts是订阅超时事件的subscriber
		firstTimeout.subscribe(ts);

		// 每个中间publisher，都有下游身份，所以在subscribe的时候需要创建一个自己的subscriber来订阅上游souce
		source.subscribe(main);
	}
}

// 就是Mono.delay.onErrorReturn返回的Mono类型
final class MonoOnErrorResume<T> extends MonoOperator<T, T> {

	@Override
	public void subscribe(CoreSubscriber<? super T> actual) {
		// source就是上游 Mono.delay
		source.subscribe(new FluxOnErrorResume.ResumeSubscriber<>(actual, nextFactory));
	}
}

final class MonoDelay extends Mono<Long> implements Scannable,  SourceProducer<Long>  {
	@Override
	public void subscribe(CoreSubscriber<? super Long> actual) {
		// actaul就是ts，超时事件的专门订阅者
		MonoDelayRunnable r = new MonoDelayRunnable(actual);
		actual.onSubscribe(r);
		// 通过scheduler定时任务实现delay
		r.setCancel(timedScheduler.schedule(r, delay, unit));
	}

	static final class MonoDelayRunnable implements Runnable, InnerProducer<Long> {
		// 即 MonoDelayRunnable.CACNEL就是 定时任务的dispose对象
		static final AtomicReferenceFieldUpdater<MonoDelayRunnable, Disposable> CANCEL =
				AtomicReferenceFieldUpdater.newUpdater(MonoDelayRunnable.class,
						Disposable.class,
						"cancel");

		// 如果 CANCEL是null，就设置成scheduler定时任务返回的dispose，设置失败就取消定时任务
		public void setCancel(Disposable cancel) {
			if (!CANCEL.compareAndSet(this, null, cancel)) {
				cancel.dispose();
			}
		}
	}

	// 到了delay时间后执行的逻辑
	@Override
	public void run() {
		actual.onNext(0L);
		actual.onComplete();
	}
}

final class FluxTimeout<T, U, V> extends FluxOperator<T, T> {

	final TimeoutMainSubscriber<?, ?> main;
	static final class TimeoutTimeoutSubscriber implements Subscriber<Object>, IndexedCancellable {
		@Override
		public void onNext(Object t) {
			// 调用主流程处理超时
			main.doTimeout(index);
		}
	}

	static final class TimeoutMainSubscriber<T, V> extends Operators.MultiSubscriptionSubscriber<T, T> {
		void handleTimeout() {
			// 调用当前subscription的cancel
			super.cancel();
			// 触发主流程的subscriber的onError，结束主流程
			actual.onError(new TimeoutException("Did not observe any item or terminal signal within "
					+ timeoutDescription + " (and no fallback has been configured)"));
		}
	}
}

final class SerializedSubscriber<T> implements InnerOperator<T, T> {
	@Override
	public void onError(Throwable t) {

		synchronized (this) {
			// done决定了主流程结束，后续主流程其他callback被回调都会判断如果done了就不处理
			done = true;
			error = t;
		}

		actual.onError(t);
	}
}