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
                	// 设置连入服务端的 Client 的 SocketChannel 的处理器
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

        // 绑定端口，并同步等待成功，即启动服务端
        ChannelFuture future = serverBootstrap.bind(new InetSocketAddress(hostName, port));
        future.syncUninterruptibly();
    }
}

// 它声明了 B 、C 两个泛型：
// B ：继承 AbstractBootstrap 类，用于表示自身的类型。
// C ：继承 Channel 类，表示表示创建的 Channel 类型。
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {

    volatile EventLoopGroup group;
    /**
     * Channel 工厂，用于创建 Channel 对象。(应该是服务端的channel？) 
     * 创建完channel后会注册到group上
     */
    private volatile ChannelFactory<? extends C> channelFactory;
    /**
     * 本地地址
     */
    private volatile SocketAddress localAddress;

    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object> attrs = new LinkedHashMap<AttributeKey<?>, Object>();
    private volatile ChannelHandler handler;

    // 返回自己
    private B self() {
    	return (B) this;
	}

	// 设置要被实例化的 Channel 的类
	public B channel(Class<? extends C> channelClass) {
		// 反射调用默认构造方法，创建 Channel 对象
	    return channelFactory(new ReflectiveChannelFactory<C>(channelClass));
	}

	// 绑定服务端端口
	private ChannelFuture doBind(final SocketAddress localAddress) {
		// initAndRegister：初始化并注册一个Channel对象，异步返回ChannelFuture
        final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();

        ChannelPromise promise = channel.newPromise();
        doBind0(regFuture, channel, localAddress, promise);
        return promise; 
    }

    final ChannelFuture initAndRegister() {
        Channel channel = null;
        channel = channelFactory.newChannel();
        // 初始化channel配置
        init(channel);

        // 首先获得 EventLoopGroup 对象，后调用 EventLoopGroup#register(Channel) 方法，注册 Channel 到 EventLoopGroup 中。
        // 实际在方法内部，EventLoopGroup 会分配一个 EventLoop 对象，将 Channel 注册到其上。
        ChannelFuture regFuture = config().group().register(channel);
        return regFuture;
    }

    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {

        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (regFuture.isSuccess()) {
                	// channel注册eventLoop成功，就绑定端口
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }
}

public abstract class AbstractNioChannel extends AbstractChannel {
	// 在Bootstrap成功bind后调用
	@Override
    protected void doBeginRead() throws Exception {
        final SelectionKey selectionKey = this.selectionKey;
        // 设置的 readInterestOp = SelectionKey.OP_ACCEPT 添加为感兴趣的事件。
        // 也就说，服务端可以开始处理客户端的连接事件。
        final int interestOps = selectionKey.interestOps();
        if ((interestOps & readInterestOp) == 0) {
            selectionKey.interestOps(interestOps | readInterestOp);
        }
    }
}

public abstract class AbstractNioMessageChannel extends AbstractNioChannel {

	// 内部Unsafe类
	private final class NioMessageUnsafe extends AbstractNioUnsafe {

		// 存放待读取客户端的连接channel
        private final List<Object> readBuf = new ArrayList<Object>();

        // 服务端在处理accept和read事件时被调用
        @Override
        public void read() {
            assert eventLoop().inEventLoop();
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            allocHandle.reset(config);

            Throwable exception = null;
            try {
                try {
                    do {
                        int localRead = doReadMessages(readBuf);
                        // 没有读取的客户端连接
                        if (localRead == 0) {
                            break;
                        }
                        // 读取消息数量 + localRead
                        allocHandle.incMessagesRead(localRead);
                    } while (allocHandle.continueReading()); // 判断是否继续读取
                } catch (Throwable t) {
                    exception = t;
                }

                int size = readBuf.size();
                // 循环 readBuf 数组，触发 Channel read 事件到 pipeline 中。
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                readBuf.clear();
                allocHandle.readComplete();
                // 触发 Channel readComplete 事件到 pipeline 中。
                pipeline.fireChannelReadComplete();
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }
}

// Netty服务端的channel类
public class NioServerSocketChannel extends AbstractNioMessageChannel
							implements io.netty.channel.socket.ServerSocketChannel {

	// 默认的 SelectorProvider 实现类。
	private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

	public NioServerSocketChannel(SelectorProvider provider) {
		// 通过provider.openServerSocketChannel();创建一个新的ServerSocketChannel
		// 看方法名是socket，我们可以把 Netty Channel 和 Java 原生 Socket 对应，而 Netty NIO Channel 和 Java 原生 NIO SocketChannel 对象。
        this(newSocket(provider));
    }

	// 传入现成的ServerSocketChannel的构造方法，通知 selector 对客户端的连接请求感兴趣.
	public NioServerSocketChannel(ServerSocketChannel channel) {
        super(null, channel, SelectionKey.OP_ACCEPT);
        config = new NioServerSocketChannelConfig(this, javaChannel().socket());
    }

	// 收到客户端连接请求，把客户端的channel加到buf里
	// 是accpet阶段不是字面上的read阶段，所以添加的是channel，不是报文
	// NioServerSocketChannel 读取新的连接。
	// 在NioMessageUnsafe.doRead被调用，
	@Override
    protected int doReadMessages(List<Object> buf) throws Exception {
    	// 接受客户端连接
        SocketChannel ch = SocketUtils.accept(javaChannel());
        // 创建NioSocketChannel,新的数据交给NioSocketChannel读取，这样的设计也保证客户端和服务端能够复用。
		buf.add(new NioSocketChannel(this, ch));
		return 1;
    }

}

public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

	// 在 Server 接受一个 Client 的连接后，会创建一个对应的 Channel 对象。因此，带child前缀的表示接入客户端的集合相关字段
	private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object> childAttrs = new LinkedHashMap<AttributeKey<?>, Object>();
    private volatile EventLoopGroup childGroup;
	// childHandler就是ServerBootstrap在build的时候指定处理客户端请求的handler
	private final ChannelHandler childHandler;

	// 客户端连接进来会调用channelRead，是accpet阶段不是字面上的read阶段，所以添加的是channel，不是报文
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
    	// 一堆初始化字段
    	...

        ChannelPipeline p = channel.pipeline();

        // 添加 ChannelInitializer 对象到 pipeline 中，用于后续初始化 ChannelHandler 到 pipeline 中。
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
                // 添加配置的 ChannelHandler 到 pipeline 中。
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    pipeline.addLast(handler);
                }

                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                    	// 添加 ServerBootstrapAcceptor 到 pipeline 中。
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    // 内部handler类，在ServerBootstrap.init时候会被添加到pipeline
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

    	private final EventLoopGroup childGroup;

    	// 将接受的客户端的 NioSocketChannel 注册到 EventLoop 中。
    	@Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            final Channel child = (Channel) msg;

            child.pipeline().addLast(childHandler);

            setChannelOptions(child, childOptions, logger);

            for (Entry<AttributeKey<?>, Object> e: childAttrs) {
                child.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
            }

            // 注册
            // 在注册完成之后，该 worker EventLoop 就会开始轮询该客户端是否有数据写入。
            childGroup.register(child).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {

                }
            });
        }
    }
}

public final class SocketUtils {

	// 调用JDK的ServerSocketChannelImpl.accept
	public static SocketChannel accept(final ServerSocketChannel serverSocketChannel) throws IOException {
        return AccessController.doPrivileged(new PrivilegedExceptionAction<SocketChannel>() {
            @Override
            public SocketChannel run() throws IOException {
                return serverSocketChannel.accept();
            }
        });
    }
}