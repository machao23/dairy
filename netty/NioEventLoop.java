public final class NioEventLoop extends SingleThreadEventLoop {
	NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory queueFactory) {
        super(parent, executor, false, newTaskQueue(queueFactory), newTaskQueue(queueFactory),
                rejectedExecutionHandler);
		// 每一个NioEventLoop都与一个Selector绑定
        final SelectorTuple selectorTuple = openSelector();
        this.selector = selectorTuple.selector;
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }
	
	protected void run() {
		for (;;) {
			try {
				switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
					case SelectStrategy.CONTINUE:
						continue;
					case SelectStrategy.SELECT:
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                        nextWakeupNanos.set(curDeadlineNanos);
						if (!hasTasks()) {
							strategy = select(curDeadlineNanos);
						}
				}
				// 处理产生的IO事件
				processSelectedKeys();
				// 处理异步任务队列
				runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
			} catch (Throwable t) {
				handleLoopException(t);
			}
		}
	}
	
	private int select(long deadlineNanos) throws IOException {
        if (deadlineNanos == NONE) {
            return selector.select();
        }
        // Timeout will only be 0 if deadline is within 5 microsecs
		// 在下一个定时任务提前5ms前返回
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
		// 立即返回或者在下一次定时任务执行前返回select
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }
}
