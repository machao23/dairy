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
	
}