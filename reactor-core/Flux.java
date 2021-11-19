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

final class FluxPeekFuseable<T> extends FluxOperator<T, T> implements Fuseable, SignalPeek<T> {
	@Override
	public void onNext(T t) {
		// doOnNext的定义执行逻辑
		nextHook.accept(t);
		// 执行完doOnNext后，向下游传递
		actual.onNext(t);
	}
}

final class FluxCreate<T> extends Flux<T> implements SourceProducer<T> {
	// source就是sink，生产记录
	final Consumer<? super FluxSink<T>> source;
	// 背压策略
	final OverflowStrategy backpressure;
	
	@Override
	public void subscribe(CoreSubscriber<? super T> actual) {
		// 这里也能看到Reactor的懒加载设计，就是在subscribe调用的时候才做一些加载工作
		// 创建sink，默认是BufferAsyncSink
		BaseSink<T> sink = createSink(actual, backpressure);
		actual.onSubscribe(sink);
		// source就是 Flux.create里面的Lambda表达式
		// 所以这里就是执行这个Lambda表达式
		source.accept(
				createMode == CreateMode.PUSH_PULL ? new SerializedSink<>(sink) :
						sink);
	}
	
	@Override
	public final void request(long n) {
		onRequestedFromDownstream();
	}
	
	@Override
	void onRequestedFromDownstream() {
		drain();
	}
	
	// Flux.create最上游收到订阅者request拉取数据请求后，最后会走到drain
	// drain主要是从一个队列中poll元素，向下游传递消费
	// 第一次request调用drain还没出发sink.next，就直接return了
	void drain() {
		// a是下游订阅方
		final Subscriber<? super T> a = actual;
		final Queue<T> q = queue;

		for (; ; ) {
			long r = requested;
			long e = 0L;

			while (e != r) {
				boolean d = done;
				// 在sink.next也会调用drain，通过q队列里能拿到next传递的成员o
				T o = q.poll();
				
				// empty表示队列为空，全部消费完了
				boolean empty = o == null;
				if (d && empty) {
					super.complete();
					return;
				}
				// 把成员传递给下游订阅方a
				a.onNext(o);
				e++;
			}

			if (e == r) {
				// empty表示队列为空，全部消费完了
				boolean d = done;
				boolean empty = q.isEmpty();
				if (d && empty) {
					super.complete();
				}
			}

			if (e != 0) {
				Operators.produced(REQUESTED, this, e);
			}

			if (WIP.decrementAndGet(this) == 0) {
				break;
			}
		}
	}
	
	// 默认的Flux.create里定义的sink
	static final class BufferAsyncSink<T> extends BaseSink<T> {
		@Override
		public FluxSink<T> next(T t) {
			// 这里并没有直接把t传递给下游a，而是放到drain要消费的队列里
			queue.offer(t);
			drain();
			return this;
		}
	}
	
	// Flux.create 使用了 SerializedSink，在BufferAsyncSink封装了一层
	static final class SerializedSink<T> implements FluxSink<T>, Scannable {
		@Override
		public FluxSink<T> next(T t) {
			// AtomicIntegerFieldUpdater的WIP实例控制多线程publisher并发
			// 类似写锁，仅有一个publisher发送给sink处理
			if (WIP.get(this) == 0 && WIP.compareAndSet(this, 0, 1)) {
				try {
					sink.next(t);
				}
				catch (Throwable ex) {
					Operators.onOperatorError(sink, ex, t, sink.currentContext());
				}
				if (WIP.decrementAndGet(this) == 0) {
					return this;
				}
			}
			else {
				// 当有一个线程拿到WIP生产数据给sink时，其他publisher把数据都房贷mpscQueue队列里
				this.mpscQueue.offer(t);
				if (WIP.getAndIncrement(this) != 0) {
					// 通过WIP控制并发，任一时间仅一个publisher调用下面的drainLoop方法，传递数据给下游
					return this;
				}
			}
			// 从mpsc队列里消费
			drainLoop();
			return this;
		}
	}
}

// publishOn 异步线程
final class FluxPublishOn<T> extends FluxOperator<T, T> implements Fuseable {
	static final class PublishOnSubscriber<T>
			implements QueueSubscription<T>, Runnable, InnerOperator<T, T> {
		
		// 这个队列就是多个线程共享的集合
		Queue<T> queue;
		
		@Override
		public void onNext(T t) {
			// 将上游的元素放入队列
			queue.offer(t);
			// 调度新线程消费队列里的元素
			trySchedule(this, null, t);
		}
		
		void trySchedule(
					@Nullable Subscription subscription,
					@Nullable Throwable suppressed,
					@Nullable Object dataSignal) {
			// worker就是线程池，新起线程
			// PublishOnSubscriber实现了Runnable接口，担任线程任务的角色
			worker.schedule(this);
		}
		
		// PublishOnSubscriber实现了Runnable接口
		@Override
		public void run() {
			// 类似drain，消费队列queue里的元素
			runAsync();
		}
	}
}