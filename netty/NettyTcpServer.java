public class NettyTcpServer extends AbstractServer {

	// 待执行task的队列
	private final Queue<Runnable> taskQueue;

	 @Override
    public void doBind(String hostName, int port) {
        ServerBootstrap serverBootstrap = new ServerBootstrap();

        // bossGroup, 用于处理客户端的连接请求; 另一个是 workerGroup, 用于处理与各个客户端连接的 IO 操作.
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.ALLOCATOR, Holder.byteBufAllocator)
				// 服务端处理客户端连接请求是顺序处理的，所以同一时间只能处理一个客户端连接，
				// 多个客户端来的时候，服务端将不能处理的客户端连接请求放在队列中等待处理，backlog参数指定了队列的大小
				// 默认是2048
                .option(ChannelOption.SO_BACKLOG, config.getInt(Server.HSF_BACKLOG_KEY))
                .childOption(ChannelOption.ALLOCATOR, Holder.byteBufAllocator)
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                .childOption(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                .childOption(ChannelOption.AUTO_CLOSE, Boolean.TRUE)
                .childOption(ChannelOption.ALLOW_HALF_CLOSURE, Boolean.FALSE)
                .handler(new ChannelInitializer<ServerSocketChannel>() {
                    @Override
                    protected void initChannel(ServerSocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast("serverBindHandler",
                                        new NettyBindHandler(NettyTcpServer.this,
                                                serverStreamLifecycleListeners));
                    }
                })
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast("protocolHandler", new NettyProtocolHandler())
                                .addLast("serverIdleHandler",
                                        new IdleStateHandler(0, 0, serverIdleTimeInSeconds))
                                .addLast("serverHandler",
                                        new NettyServerStreamHandler(NettyTcpServer.this, false,
                                                serverStreamLifecycleListeners, serverStreamMessageListeners));
                    }
                });

        if (isWaterMarkEnabled()) {
            serverBootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                    new WriteBufferWaterMark(lowWaterMark, highWaterMark));
        }

        ChannelFuture future = serverBootstrap.bind(new InetSocketAddress(hostName, port));
        future.syncUninterruptibly();
    }
}

public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {
	// 客户端连接进来会调用channelRead
	@Override
	@SuppressWarnings("unchecked")
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		// child就是客户端的channel
		final Channel child = (Channel) msg;

		// childHandler就是ServerBootstrap在build的时候指定处理客户端请求的handler
		child.pipeline().addLast(childHandler);

		// childGroup即workerGroup
		childGroup.register(child).addListener(new ChannelFutureListener() {
			...
		});
	}

	@Override
    void init(Channel channel) throws Exception {
        ChannelPipeline p = channel.pipeline();

        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    pipeline.addLast(handler);
                }

                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }
}

public class NioServerSocketChannel extends AbstractNioMessageChannel
							implements io.netty.channel.socket.ServerSocketChannel {
	// 构造方法，通知 selector 对客户端的连接请求感兴趣.
	public NioServerSocketChannel(ServerSocketChannel channel) {
        super(null, channel, SelectionKey.OP_ACCEPT);
        config = new NioServerSocketChannelConfig(this, javaChannel().socket());
    }

	// 收到客户端连接请求
	@Override
    protected int doReadMessages(List<Object> buf) throws Exception {
        SocketChannel ch = SocketUtils.accept(javaChannel());
		buf.add(new NioSocketChannel(this, ch));
		return 1;
    }

}

// Netty 中对本地线程的抽象,SingleThreadEventExecutor的父类
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {
	// 本质是这个thread
	private volatile Thread thread;

	// 定时task的队列在父类里实现
	Queue<ScheduledFutureTask<?>> scheduledTaskQueue;

	@Override
    public void execute(Runnable task) {
        boolean inEventLoop = inEventLoop();
        if (inEventLoop) {
            addTask(task);
        } else {
			// 主线程进来的还得先启动eventLoop线程
            startThread();
            addTask(task);
        }
    }
}

public final class NioEventLoop extends SingleThreadEventLoop {
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
