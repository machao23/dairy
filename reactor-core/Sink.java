import reactor.util.annotation.Nullable;

final class MonoCreate<T> extends Mono<T> implements SourceProducer<T> {

	static final class DefaultMonoSink<T> extends AtomicBoolean implements MonoSink<T>, InnerProducer<T> {
		// 注册取消回调
		@Override
		public MonoSink<T> onCancel(Disposable d) {
			// 把回调方法设置到 SinkDisposable.onCancel
			SinkDisposable sd = new SinkDisposable(null, d);
			// 把 SinkDisposable 赋值给 DefaultMonoSink.disposable
			DISPOSABLE.compareAndSet(this, null, sd);
			return this;
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
