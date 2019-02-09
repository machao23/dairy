abstract class AbstractChannelHandlerContext extends DefaultAttributeMap implements ChannelHandlerContext, ResourceLeakHint {

	// pipeline写数据时调用tailContext时被调用
	@Override
    public ChannelFuture write(Object msg) {
    	// 默认创建DefaultChannelPromise
        return write(msg, newPromise());
    }

    private void write(Object msg, boolean flush, ChannelPromise promise) {
    	// 获得下一个Outbound节点
        AbstractChannelHandlerContext next = findContextOutbound();
        // 记录 record 记录
        final Object m = pipeline.touch(msg, next);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            if (flush) {
            	 // 执行 writeAndFlush 事件到下一个节点
                next.invokeWriteAndFlush(m, promise);
            } else {
            	 // 执行 write 事件到下一个节点
                next.invokeWrite(m, promise);
            }
        } else {
            AbstractWriteTask task;
            // 创建 writeAndFlush 任务
            if (flush) {
                task = WriteAndFlushTask.newInstance(next, m, promise);
            }  else {
            	// 创建 write 任务
                task = WriteTask.newInstance(next, m, promise);
            }
            // 提交到 EventLoop 的线程中，执行该任务
            safeExecute(executor, task, promise, m);
        }
    }

    // 写任务的抽象父类
    abstract static class AbstractWriteTask implements Runnable {
    	@Override
        public final void run() {
            try {
                // Check for null as it may be set to null if the channel is closed already
                if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                	// 在channel.outboundBuffer预留出空间
                    ctx.pipeline.decrementPendingOutboundBytes(size);
                }
                // 执行 write 事件到下一个节点
                write(ctx, msg, promise);
            } finally {
            	// 置空GC回收
                // Set to null so the GC can collect them directly
                ctx = null;
                msg = null;
                promise = null;
                handle.recycle(this);
            }
        }

        // 子类WriteTask在写数据时候直接重用父类方法
        protected void write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ctx.invokeWrite(msg, promise);
        }
    }

    @UnstableApi
    protected void decrementPendingOutboundBytes(long size) {
        ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
        if (buffer != null) {
            buffer.decrementPendingOutboundBytes(size);
        }
    }

    static final class WriteAndFlushTask extends AbstractWriteTask {

        @Override
        public void write(AbstractChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        	// 重用父类AbstractWriteTask的write
            super.write(ctx, msg, promise);
            // 执行 flush 事件到下一个节点
            ctx.invokeFlush();
        }
    }
}

public class DefaultChannelPipeline implements ChannelPipeline {
	final class HeadContext extends AbstractChannelHandlerContext implements ChannelOutboundHandler, ChannelInboundHandler {
		// 在 pipeline 中，write 事件最终会到达 HeadContext 节点。
		@Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        	// 将数据写到内存队列中
            unsafe.write(msg, promise);
        }	
	}
}

