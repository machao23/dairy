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
public abstract class AbstractScheduledEventExecutor extends AbstractEventExecutor {
	// 用来保存定时任务的队列
	// ScheduledFutureTask继承了Netty自己的PromiseTask，同时实现了JDK定时任务接口ScheduledFuture
	PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue;

	// 添加定时任务
	// 即使是非定时场景，也可以添加一个马上执行的任务
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

// 在定时执行任务AbstractScheduledEventExecutor基础上扩展
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {
	// 自己的可执行任务队列
	private final Queue<Runnable> taskQueue;

	// 默认队列实现是链表队列
	protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    protected Runnable takeTask() {
        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
        	// 从scheduledTaskQueue取一个scheduledTask, 注意方法是peek, task有可能还没有到执行时间
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
            	// 队列里没有任务，就阻塞等待
                task = taskQueue.take();
                if (task == WAKEUP_TASK) {
                	// 跳过WAKEUP_TASK
                    task = null;
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                	// 如果定时任务还没有到时间执行，等待delayNano时间从taskQueue里获取任务
                	// 在阻塞等待期间，如果有新的定时任务加入taskQueue就能拿到
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // 这种情况是因为有新的定时任务加入，且时间小于之前拿到的定时任务，需要提前结束阻塞
                        return null;
                    }
                }
                if (task == null) {
                	// 继续尝试从定时任务队列里获取任务添加到taskQueue里
                    fetchFromScheduledTaskQueue();
                    // 再尝试从taskQueue里获取任务
                    task = taskQueue.poll();
                }

                // 一直无限循环，直到获取到可以执行的任务
                if (task != null) {
                    return task;
                }
            }
        }
    }

    // execute只是将任务加入到taskQueue里，真正的执行不再这里
    @Override
    public void execute(Runnable task) {
        boolean inEventLoop = inEventLoop();
        addTask(task);
        if (!inEventLoop) {
            startThread();
        }
    }
}

public final class NioEventLoop extends SingleThreadEventLoop {

	@Override
    public final void register(EventLoop eventLoop, final ChannelPromise promise) {
    	// 设置 Channel 的 eventLoop 属性，建立关联关系
        AbstractChannel.this.eventLoop = eventLoop;

	    register0(promise);
    }

	@Override
    protected void run() {
		// 事件无限循环
        for (;;) {
			// hasTasks查看taskQueue队列里是否任务,调用非阻塞selector.selectNow迅速拿到就绪IO集合,selector.wakeup唤醒被select阻塞的线程,然后走到default分支，
			// 没有task就返回SelectStrategy.SELECT继续阻塞等待
			switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
				case SelectStrategy.CONTINUE:
					continue;
				case SelectStrategy.SELECT:
					select(wakenUp.getAndSet(false));
					if (wakenUp.get()) {
						selector.wakeup();
					}
					// fall through
				default:
			}

			// ioRatio表示这个thread分配给io和执行task的时间比
			final int ioRatio = this.ioRatio;
			if (ioRatio == 100) {
				try {
					processSelectedKeys();
				} finally {
					runAllTasks();
				}
			} else {
				final long ioStartTime = System.nanoTime();
				try {
					// 实质会走到processSelectedKey
					processSelectedKeys();
				} finally {
					final long ioTime = System.nanoTime() - ioStartTime;
					runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
				}
			}
        }
    }

	// 调用NIO的selecor的非阻塞select
	int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

	// NIO处理selector就绪的流程
	private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();

		int readyOps = k.readyOps();
		// 连接建立
		if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
			int ops = k.interestOps();
			// 需要将 OP_CONNECT 从就绪事件集中清除, 不然会一直有 OP_CONNECT 事件.
			ops &= ~SelectionKey.OP_CONNECT;
			k.interestOps(ops);
			// unsafe.finishConnect() 调用最后会调用到 pipeline().fireChannelActive(), 产生一个 inbound 事件, 通知 pipeline 中的各个 handler TCP 通道已建立
			unsafe.finishConnect();
		}

		// 可写
		if ((readyOps & SelectionKey.OP_WRITE) != 0) {
			ch.unsafe().forceFlush();
		}

		// 可读
		if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
			unsafe.read();
		}
    }
}

protected class NioByteUnsafe extends AbstractNioUnsafe {
	// OP_READ触发读取数据
	@Override
	public final void read() {
		try {
			do {
				byteBuf = allocHandle.allocate(allocator);
				allocHandle.lastBytesRead(doReadBytes(byteBuf));
				if (allocHandle.lastBytesRead() <= 0) {
					// nothing was read. release the buffer.
					byteBuf.release();
					byteBuf = null;
					close = allocHandle.lastBytesRead() < 0;
					break;
				}

				allocHandle.incMessagesRead(1);
				readPending = false;
				// 触发inbound的起点事件
				pipeline.fireChannelRead(byteBuf);
				byteBuf = null;
			} while (allocHandle.continueReading());

			allocHandle.readComplete();
			pipeline.fireChannelReadComplete();

		} finally {
			if (!readPending && !config.isAutoRead()) {
				removeReadOp();
			}
		}
    }
}