import reactor.util.annotation.Nullable;

// 调用Mono.create (sink -> {...}) 时候会创建一个Mono.create
final class MonoCreate<T> extends Mono<T> implements SourceProducer<T> {

	// subscribe方法是在第一阶段构建流式链表调用的
	@Override
	public void subscribe(CoreSubscriber<? super T> actual) {
		//1. 创建MonoSink实例，供MonoCreate来使用
    	//如变量名字emitter一样，MonoSink的作用其实就是信号的发射器（signal emitter）
		DefaultMonoSink<T> emitter = new DefaultMonoSink<>(actual);

		//2. emitter除了是sink外，也实现了subscription，供Subscriber使用
    	//这一步，调用Subscriber的onSubscribe方法，其内部则会调用subscription的request方法
		actual.onSubscribe(emitter);

		try {
			//3. callback就是在Mono.create时候传入的Mono构造器
        	//此步骤即调用用户自定义的Mono构造器函数（即Mono.create），并将sink传入
			callback.accept(emitter);
		}
		catch (Throwable ex) {
			emitter.error(Operators.onOperatorError(ex, actual.currentContext()));
		}
	}

	static final class DefaultMonoSink<T> extends AtomicBoolean implements MonoSink<T>, InnerProducer<T> {

		volatile int state; //初始默认状态0，即未调用Request且未赋值
		// 调用了request方法则会是HAS_REQUEST
		// 调用了success(或者error)方法则会是HAS_VALUE,success或者error则是由具体使用者定义callback里调用的（一般异步）

		// 以同步的角度思考，通常是先调用request然后再调用success或者error方法，
		// 其中success会对应调用Subscriber的onNext与onComplete方法，error方法则会调用对应的Subscriber#onError方法。
		// 但异步场景没这么简单，request方法与success/error方法是乱序的，很有可能在request的时候，success/error方法已经调用结束了。
		// 所以会存在 NO_REQUEST_HAS_VALUE 这个枚举
		static final int NO_REQUEST_HAS_VALUE  = 1; //未调用Request但已经赋值
		static final int HAS_REQUEST_NO_VALUE  = 2; //调用了Request但还未赋值
		static final int HAS_REQUEST_HAS_VALUE = 3; //调用了Request且已经赋值了

		// 注册取消回调
		@Override
		public MonoSink<T> onCancel(Disposable d) {
			// 把回调方法设置到 SinkDisposable.onCancel
			SinkDisposable sd = new SinkDisposable(null, d);
			// 把 SinkDisposable 赋值给 DefaultMonoSink.disposable
			DISPOSABLE.compareAndSet(this, null, sd);
			return this;
		}

		// request方法调用是由Subscriber#onSubscribe调用的
		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				for (; ; ) {
					int s = state;
					//2.1 已经是完成终态了，直接退出
					if (s == HAS_REQUEST_NO_VALUE || s == HAS_REQUEST_HAS_VALUE) {
						return;
					}
					if (s == NO_REQUEST_HAS_VALUE) {
						// 2.2 double check 是否已经有值
						if (STATE.compareAndSet(this, s, HAS_REQUEST_HAS_VALUE)) {
							// 如果是，执行onNext/onComplete方法，并设置完成状态: HAS_REQUEST_HAS_VALUE
							actual.onNext(value);
							actual.onComplete();
						}
						// 如果不是，double check失败，直接退出，说明有别的线程已经执行了该方法了
						return;
					}
					// 2.3 正常流程，值没有被赋值，设置为HAS_REQUEST_NO_VALUE
					if (STATE.compareAndSet(this, s, HAS_REQUEST_NO_VALUE)) {
						return;
					}
				}
			}
		}

		// 取消时候被回调
		@Override
		public void cancel() {
			if (STATE.getAndSet(this, HAS_REQUEST_HAS_VALUE) != HAS_REQUEST_HAS_VALUE) {
				T old = value;
				value = null;
				// 这里会调用 SinkDisposable.cancel
				disposeResource(true);
			}
		}

		@Override
		public void success(@Nullable T value) {
			for (; ; ) {
				if (s == HAS_REQUEST_NO_VALUE) {
					if (STATE.compareAndSet(this, s, HAS_REQUEST_HAS_VALUE)) {
						try {
							// 回调next和complete(因为是mono，next后立即就complete)
							actual.onNext(value);
							actual.onComplete();
						}
						finally {
							disposeResource(false);
						}
					}
					return;
				}
			}
		}
	}
}

// 很多逻辑是和MonoCreate共用的
final class FluxCreate<T> extends Flux<T> implements SourceProducer<T> {

	// 被sink取消回调的disposable
	static final class SinkDisposable implements Disposable {

		Disposable onCancel;

		Disposable disposable;

		SinkDisposable(@Nullable Disposable disposable, @Nullable Disposable onCancel) {
			this.disposable = disposable;
			this.onCancel = onCancel;
		}

		// sink取消时候被回调
		public void cancel() {
			if (onCancel != null) {
				onCancel.dispose();
			}
		}
	}
}

public abstract class Operators {

	abstract static class MultiSubscriptionSubscriber<I, O> implements InnerOperator<I, O> {
		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;
				drain(); // 会调用 drainLoop
			}
		}
	}

	final void drainLoop() {

		Subscription a = subscription; // 这里的a就是MonoSink
		if (cancelled) {
            if (a != null) {
                a.cancel();
                subscription = null;
            }
        }
	}
}

final class FluxMap<T, R> extends FluxOperator<T, R> {
	static final class MapSubscriber<T, R> implements InnerOperator<T, R> {
		// 类似map这样的操作符是没有超时取消回调的，所以它会透传调用上游，比如sink
		@Override
		public void cancel() {
			s.cancel();
		}
	}
}
