public abstract class AbstractEventExecutorGroup implements EventExecutorGroup {

	// 在 EventExecutor 中执行多个普通任务。
	@Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return next().invokeAll(tasks);
    }

    // 在 EventExecutor 中执行多个普通任务，有一个执行完成即可。
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return next().invokeAny(tasks);
    }
}

public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

	private final EventExecutor[] children;
	private final EventExecutorChooserFactory.EventExecutorChooser chooser;

	protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
		// 默认的执行器选择器是 DefaultEventExecutorChooserFactory 
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }

	protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) {

        if (executor == null) {
        	// 每个任务单独一个线程的执行器，内部有一个ThreadFactory创建线程执行任务
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
        }

        children = new EventExecutor[nThreads];

        for (int i = 0; i < nThreads; i ++) {
            boolean success = false;
            children[i] = newChild(executor, args);
            success = true;
        }

        chooser = chooserFactory.newChooser(children);

        // 创建监听器，用于 EventExecutor 终止时的监听
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
            	// 全部关闭
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };

        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }

        // 创建不可变( 只读 )的 EventExecutor 数组
        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        Collections.addAll(childrenSet, children);
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }
}

public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {
	@Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
    	// 判断 EventExecutor 数组的大小是否为 2 的幂次方。
        if (isPowerOfTwo(executors.length)) {
        	// 也是RoundRobin，只是用了位操作，优化性能
            return new PowerOfTwoEventExecutorChooser(executors);
        } else {
        	// 内部有个idx实现RoundRobin分配
            return new GenericEventExecutorChooser(executors);
        }
    }
}

public class NioEventLoopGroup extends MultithreadEventLoopGroup {
	// 创建一个新的EventLoop
	@Override
    protected EventLoop newChild(Executor executor, Object... args) throws Exception {
        return new NioEventLoop(this, executor, (SelectorProvider) args[0],
            ((SelectStrategyFactory) args[1]).newSelectStrategy(), (RejectedExecutionHandler) args[2]);
    }
}