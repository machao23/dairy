public abstract class AbstractFuture<V> implements Future<V> {

	// 阻塞获取，失败抛出异常
	@Override
    public V get() throws InterruptedException, ExecutionException {
        await();

        Throwable cause = cause();
        if (cause == null) {
        	// 成功调用子类返回结果
            return getNow();
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }
}

// 表示异步操作已经完成，所以在这里你可以定义完成callback了
public abstract class CompleteFuture<V> extends AbstractFuture<V> {
	// 用来执行listener中的任务
	private final EventExecutor executor;

	@Override
    public Future<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
    	// DefaultPromise会调用静态方法notifyListener（），来执行listener中的操作。
        DefaultPromise.notifyListener(executor(), this, listener);
        return this;
    }
}

final class DefaultPoolResources implements PoolResources {
	// new一个DefaultPromise同时返回
	@Override
	public Future<Channel> acquire() {
		return acquire(defaultGroup.next().newPromise());
	}
}

public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
	// 原子保存异步操作结果
	private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result")

    //异步操作结果
	private volatile Object result;

	// 监听者，可能是1个或多个，
	// 当所有listeners被通知触发，就会清空成null
	private Object listeners;

	@Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        // 加锁后添加listener
        synchronized (this) {
            addListener0(listener);
        }

        // 添加完成后如果promise的状态已经完成，就立即通知listeners
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

	// 异步操作结束时调用
	public Promise<V> setSuccess(V result) {
	   if (setSuccess0(result)) {
	   		//触发listener中的operationcomplete()方法
	    	notifyListeners();
	    	return this;
	   }
	   throw new IllegalStateException("complete already: " + this);
	}

	private boolean setSuccess0(V result) {
		// 如果异步操作结果是null，设置成常量SUCCESS
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setValue0(Object objResult) {
    	// CAS方式保存结果到RESULT_UPDATER里
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
        	//保存结果成功后，通过notifyAll通知所有同步等待该DefaultPromise实例的异步结果的线程
            checkNotifyWaiters();
            return true;
        }
        return false;
    }

    private void notifyListeners() {
        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            notifyListenersNow();
            return;
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListenersNow();
            }
        });
    }

    private void notifyListenersNow() {
        Object listeners;
        synchronized (this) {
        	// 取出listeners，将其清空null
            notifyingListeners = true;
            listeners = this.listeners;
            this.listeners = null;
        }
        for (;;) {
            if (listeners instanceof DefaultFutureListeners) {
            	// 调用GenericFutureListener.operationComplete
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                notifyListener0(this, (GenericFutureListener<?>) listeners);
            }
            synchronized (this) {
            	// 在执行listeners期间可能有新的listener加入，也需要通知它们然后清空null
                if (this.listeners == null) {
                    notifyingListeners = false;
                    return;
                }
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    // 阻塞等待
    @Override
    public Promise<V> await() throws InterruptedException {
        if (isDone()) {
            return this;
        }

        checkDeadLock();

        // 线程在promise对象this上等待notify
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                } finally {
                    decWaiters();
                }
            }
        }
        return this;
    }
}