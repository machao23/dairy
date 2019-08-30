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

final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {

	// 能进来说明任务到执行时间了
	@Override
    public void run() {
        if (periodNanos == 0) { // 执行周期为“只执行一次”的定时任务
        	// 设置任务不可取消
            if (setUncancellableInternal()) {
            	// 执行任务
                V result = task.call();
                // 通知任务执行成功
                setSuccessInternal(result);
            }
        } else { // 执行周期为“固定周期”的定时任务
            // check if is done as it may was cancelled
            // 判断任务有没有被取消
            if (!isCancelled()) {
            	// 执行任务
                task.call();
                // 判断 EventExecutor没有关闭
                if (!executor().isShutdown()) {
                	// 计算下个周期执行赶时间
                    long p = periodNanos;
                    if (p > 0) {
                        deadlineNanos += p;
                    } else {
                        deadlineNanos = nanoTime() - p;
                    }
                    if (!isCancelled()) {
                        // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                        Queue<ScheduledFutureTask<?>> scheduledTaskQueue =
                                ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                        // 重新添加到定时任务队列里，等待下次执行
                        scheduledTaskQueue.add(this);
                    }
                }
            }
        }
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

	// 添加任务后，任务是否会自动导致线程唤醒
	private final boolean addTaskWakesUp;


	// 默认队列实现是链表队列
	protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    // NioEventLoop执行任务时被调用
    protected boolean runAllTasks() {
        boolean fetchedAll;
        boolean ranAtLeastOne = false;

        do {
        	// 拉取已到执行时间的定时任务，转到taskQueue
            fetchedAll = fetchFromScheduledTaskQueue();
            // 执行任务
            if (runAllTasksFrom(taskQueue)) {
                ranAtLeastOne = true;
            }
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        // 执行完的处理
        afterRunningAllTasks();
        return ranAtLeastOne;
    }

    // 遍历taskQueue队列，执行task
    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) {
    	// 获得队头任务
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        for (;;) {
        	// 执行任务
            safeExecute(task);
            task = pollTaskFrom(taskQueue);
            if (task == null) {
                return true;
            }
        }
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
                	// 继续尝试从定时任务队列里获取已经到执行时间的任务吗，添加到taskQueue里
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
        	// 创建线程
            startThread();
        }

        // 唤醒线程
        // 对于 Nio 使用的 NioEventLoop ，它的线程执行任务是基于 Selector 监听感兴趣的事件，
        // 所以当任务添加到 taskQueue 队列中时，线程是无感知的，所以需要调用 #wakeup(boolean inEventLoop) 方法，进行主动的唤醒。
     	if (!addTaskWakesUp && wakesUpForTask(task)) {
        	wakeup(inEventLoop);
     	}
    }

    private void doStartThread() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                thread = Thread.currentThread();
                boolean success = false;
                updateLastExecutionTime();
                try {
                	// 执行任务
                    SingleThreadEventExecutor.this.run();
                    success = true;
                }finally {
                   // 优雅关闭
                	...
                }
            }
        });
    }
}

public abstract class SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop {
	private final Queue<Runnable> tailTasks;

	// NioEventLoop执行完所有任务后被调用
	@Override
    protected void afterRunningAllTasks() {
        runAllTasksFrom(tailTasks);
    }
}

// NioEventLoop实际上就是工作线程，可以直接理解为一个线程。
// NioEventLoopGroup是一个线程池，线程池中的线程就是NioEventLoop。
public final class NioEventLoop extends SingleThreadEventLoop {

	// 每个NioEventLoop有它自己的selector
	// 一个NioEventLoop的selector可以被多个Channel注册，也就是说多个Channel共享一个EventLoop
	private Selector selector;
	// 用来创建selector
	private final SelectorProvider provider;

	@Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
    	// 创建mpsc队列
        // mpsc 是对多线程生产任务，单线程消费任务的消费，恰好符合 NioEventLoop 的情况。why？
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

	@Override
    public final void register(EventLoop eventLoop, final ChannelPromise promise) {
    	// 设置 Channel 的 eventLoop 属性，建立关联关系
        AbstractChannel.this.eventLoop = eventLoop;

	    register0(promise);
    }

    // 简单来说，run的执行顺序是一个循环：即 channel.select -> process selected keys -> run tasks -> channel.select ...
	@Override
    protected void run() {
		// 事件无限循环
        for (;;) {
			// hasTasks查看taskQueue队列里是否任务,调用非阻塞selector.selectNow迅速拿到就绪IO集合,selector.wakeup唤醒被select阻塞的线程,然后走到default分支，
			// 没有task就返回SelectStrategy.SELECT继续阻塞等待
			switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
				case SelectStrategy.CONTINUE:
					continue; // 默认不会走到
				case SelectStrategy.SELECT: // 没有就绪IO就走到这里，使用阻塞select策略
					select(wakenUp.getAndSet(false));
					if (wakenUp.get()) {
						selector.wakeup();
					}
					// fall through
				default: // selectStrategy.calculateStrategy返回就绪IO，就会走到这里
			}

			// ioRatio表示这个thread分配给io和执行task的时间比
			final int ioRatio = this.ioRatio;
			if (ioRatio == 100) {
				try {
					// 处理 Channel 感兴趣的就绪 IO 事件。
					processSelectedKeys();
				} finally {
					// 运行所有普通任务和定时任务，不限制时间
					runAllTasks();
				}
			} else {
				final long ioStartTime = System.nanoTime();
				try {
					// 实质会走到processSelectedKey
					processSelectedKeys();
				} finally {
					// 这里会分配计算限制任务执行时间
					final long ioTime = System.nanoTime() - ioStartTime;
					runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
				}
			}
        }
    }

    // loop循环第一步，selectNow获取就绪IO
    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

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

    private void select(boolean oldWakenUp) throws IOException {
    	// 本次loop期间select的次数
        int selectCnt = 0;
        long currentTimeNanos = System.nanoTime();
        // 从定时任务队列里peek最近的任务到期时间，计算出selector阻塞的时长，没有任务缺省1秒
        long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);

        for (;;) { //无限循环实现阻塞select
        	// 本次select的超时时长
            long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
            // 超时时长小于0就结束select
            if (timeoutMillis <= 0) {
                if (selectCnt == 0) {
                    selector.selectNow();
                    selectCnt = 1;
                }
                break;
            }

            // 检查如果有新的任务进来就推出select
            if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                selector.selectNow();
                selectCnt = 1;
                break;
            }

            // 开始阻塞select
            int selectedKeys = selector.select(timeoutMillis);
            selectCnt ++;

            if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                // - Selected something, 有就绪IO返回
                // - waken up by user, or 该selector被其他线程唤醒
                // - the task queue has a pending task. 有新任务
                // - a scheduled task is ready for processing 或有新的定时任务
                break;
            }

            long time = System.nanoTime();
            if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                // timeoutMillis elapsed without anything selected.
                selectCnt = 1;
            } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                    selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            	// 不符合 select 超时的提交，若 select 次数到达重建 Selector 对象的上限，进行重建, 解决NIO空轮询的bug
                // The selector returned prematurely many times in a row.
                // Rebuild the selector to work around the problem.
                logger.warn(
                        "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                        selectCnt, selector);

                rebuildSelector();
                selector = this.selector;

                // Select again to populate selectedKeys.
                selector.selectNow();
                selectCnt = 1;
                break;
            }

            currentTimeNanos = time;
        }
    }

    // 处理Channel新增就绪的IO事件
    private void processSelectedKeys() {
    	// 当 selectedKeys 非空，意味着使用优化的 SelectedSelectionKeySetSelector 
	    if (selectedKeys != null) {
	        processSelectedKeysOptimized(); // 一般都走到这里
	    } else {
	        processSelectedKeysPlain(selector.selectedKeys());
	    }
	}

	private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // 置空允许GC回收
            selectedKeys.keys[i] = null;

            // 可以看到是通过Channel关联异步流程的？
            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
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

		// 调用 Unsafe#forceFlush() 方法，向 Channel 写入数据。在完成写入数据后，会移除对 OP_WRITE 的感兴趣。
		if ((readyOps & SelectionKey.OP_WRITE) != 0) {
			ch.unsafe().forceFlush();
		}

		// readyOps == 0 是对 JDK Bug 的处理，防止空的死循环
		// 如果对 OP_READ 或 OP_ACCEPT 事件就绪：调用 Unsafe#read() 方法，处理读或者者接受客户端连接的事件。
		if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
			unsafe.read();
		}
    }
}

final class DefaultSelectStrategy implements SelectStrategy {
	// 获取selector返回的就绪任务数
    @Override
    public int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception {
        return hasTasks ? selectSupplier.get() : SelectStrategy.SELECT;
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