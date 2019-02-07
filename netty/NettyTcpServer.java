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

	// childHandler就是ServerBootstrap在build的时候指定处理客户端请求的handler
	private final ChannelHandler childHandler;

	// 客户端连接进来会调用channelRead
	@Override
	@SuppressWarnings("unchecked")
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		// child就是客户端的channel，来自通用的msg入参
		final Channel child = (Channel) msg;

		// 添加handler
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