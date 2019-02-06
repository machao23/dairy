// 继承ScheduledExecutorService，提供定时执行任务的能力
public interface EventExecutorGroup extends ScheduledExecutorService, Iterable<EventExecutor> {	
	// 返回EventExecutorGroup管理的一个EventExecutor
	// EventExecutorGroup管理了多个EventExecutor
	EventExecutor next();

	// 类似ExecutorService.submit
	@Override
    Future<?> submit(Runnable task);
}

// 这里的设计很奇怪，EventExecutorGroup管理多个EventExecutor，但是EventExecutor却继承EventExecutorGroup
public interface EventExecutor extends EventExecutorGroup {
	// 当前线程是否在该EventExecutor中
	boolean inEventLoop();
}

// EventLoopGroup是一个特殊的允许注册Channel的EventExecutorGroup子类
public interface EventLoopGroup extends EventExecutorGroup {

	// 返回EventExecutor的子类EventLoop
	@Override
    EventLoop next();

    /**
     * 将Channel注册到EventLoopGroup中的一个EventLoop.
     * 当注册完成时,返回的ChannelFuture会得到通知.
     */
    ChannelFuture register(Channel channel);

    /**
     * 将Channel注册到EventLoopGroup中的一个EventLoop,
     * 当注册完成时ChannelPromise会得到通知, 而返回的ChannelFuture就是传入的ChannelPromise.
     */
    ChannelFuture register(Channel channel, ChannelPromise promise);
}

// JDK类AbstractExecutorService，实现JDK的ExecutorService接口
public abstract class AbstractExecutorService implements ExecutorService {

	public <T> Future<T> submit(Callable<T> task) {
        // 将Callable类型包装成RunnableFuture
        RunnableFuture<T> ftask = newTaskFor(task);
        // 代理给Executor.execute
        execute(ftask);
        // 返回执行结果，提供了Future的支持
        return ftask;
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
    	// 将Callable类型的task包装为FutureTask, 然后向上溯型为接口RunnableFuture
 		return new FutureTask<T>(callable);
 	}
}

// RunnableFuture的实现
public class FutureTask<V> implements RunnableFuture<V> {
	// 核心代码就是调用Callable.call()，然后将得到的result保存起来
	public void run() {
        Callable<V> c = callable;
        result = c.call();
        set(result);
    }
}

// 主要是把JDK的类型转换成Netty自定义的类型
public abstract class AbstractEventExecutor extends AbstractExecutorService implements EventExecutor {
	// 返回自身
	@Override
    public EventExecutor next() {
        return this;
    }

    // 重写JDK的submit只为了一件事，强转成Netty定义的Future类型
    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return (Future<T>) super.submit(task);
    }

    // 重写从JDK的FutureTask转换成Netty定义的PromiseTask
    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new PromiseTask<T>(this, callable);
    }
}

// 在AbstractEventExecutor的基础上提供了对定时任务的支持
// 主要是定时任务场景，因为没有需求所以先不看了
public abstract class AbstractScheduledEventExecutor extends AbstractEventExecutor {
	// 用来保存定时任务的队列
	// ScheduledFutureTask继承了Netty自己的PromiseTask，同时实现了JDK定时任务接口ScheduledFuture
	PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue;

	// 添加定时任务
	<V> ScheduledFuture<V> schedule(final ScheduledFutureTask<V> task) {
        if (inEventLoop()) {
            scheduledTaskQueue().add(task);
        } else {
        	// 当前调用的线程如果是外部线程，交给eventLoop的工作线程处理
            execute(new Runnable() {
                @Override
                public void run() {
                    scheduledTaskQueue().add(task);
                }
            });
        }

        return task;
    }
}