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
}