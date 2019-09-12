// 默认的pipeline
public class DefaultChannelPipeline implements ChannelPipeline {
	// pipeline 中的节点的数据结构是 ChannelHandlerContext 类。
	// 每个 ChannelHandlerContext 包含一个 ChannelHandler、它的上下节点( 从而形成 ChannelHandler 链 )、以及其他上下文。
	final AbstractChannelHandlerContext head;
    final AbstractChannelHandlerContext tail;
	
	protected DefaultChannelPipeline(Channel channel) {
		// head 节点向下指向 tail 节点，tail 节点向上指向 head 节点，从而形成相互的指向
		// 默认情况下，pipeline 有 head 和 tail 节点，形成默认的 ChannelHandler 链。
		// 而我们可以在它们之间，加入自定义的 ChannelHandler 节点。
        tail = new TailContext(this);
        head = new HeadContext(this);

        head.next = tail;
        tail.prev = head;
    }
	
	final class HeadContext extends AbstractChannelHandlerContext
            implements ChannelOutboundHandler, ChannelInboundHandler {

        private final Unsafe unsafe;

        HeadContext(DefaultChannelPipeline pipeline) {
			// 调用父 AbstractChannelHandlerContext 的构造方法，设置 inbound = false、outbound = true 
            super(pipeline, null, HEAD_NAME, true, true);
			// 使用 Channel 的 Unsafe 作为 unsafe 属性。
			// HeadContext 实现 ChannelOutboundHandler 接口的方法，都会调用 Unsafe 对应的方法。
            unsafe = pipeline.channel().unsafe();
            setAddComplete();
        }
		
		// 返回自己作为 Context 的 ChannelHandler 。
		// 因为 HeadContext ，实现 ChannelOutboundHandler、ChannelInboundHandler 接口，而它们本身就是 ChannelHandler 。
		@Override
		public ChannelHandler handler() {
			return this;
		}
	}
}

final class DefaultChannelHandlerContext extends AbstractChannelHandlerContext {
	// 不同于 HeadContext、TailContext，它们自身就是一个 Context 的同时，也是一个 ChannelHandler 。
	// 而 DefaultChannelHandlerContext 是内嵌 一个 ChannelHandler
    private final ChannelHandler handler;
}

abstract class AbstractChannelHandlerContext extends DefaultAttributeMap implements ChannelHandlerContext, ResourceLeakHint {
	// 下一个context节点
	volatile AbstractChannelHandlerContext next;
	// 上一个context节点
    volatile AbstractChannelHandlerContext prev;
	/**
	 * 所属 pipeline
	 */
	private final DefaultChannelPipeline pipeline;
	
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

