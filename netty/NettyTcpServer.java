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

public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

	// 一个channel一个selectionKey？
	volatile SelectionKey selectionKey;

	// 构造方法
	protected AbstractChannel(Channel parent) {
		// parent 属性，父 Channel 对象。对于 NioServerSocketChannel 的 parent 为空。
	    this.parent = parent;
	    // 创建 ChannelId 对象
	    id = newId();
	    // 创建 Unsafe 对象
	    // 之所以叫Unsafe是因为 Unsafe 操作不允许被用户代码使用。这些函数是真正用于数据传输操作，必须被IO线程调用。
	    unsafe = newUnsafe();
	    // 创建 DefaultChannelPipeline 对象
	    // 可以看到每个channel有自己的pipeline
	    pipeline = newChannelPipeline();
	}

	protected abstract class AbstractUnsafe implements Unsafe {
		@Override
	    public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
	    	// 记录channel是否被激活
	        boolean wasActive = isActive();
	        // 绑定channel的端口
	        doBind(localAddress);

	        // 若 Channel 是新激活的，
	        if (!wasActive && isActive()) {
	            invokeLater(new Runnable() {
	                @Override
	                public void run() {
	                	// 触发通知 Channel 已激活的事件。
	                    pipeline.fireChannelActive();
	                }
	            });
	        }

	        // 回调通知 promise 执行成功
	        safeSetSuccess(promise);
	    }

	    // 注册channel到eventLoop时被调用
	    @Override
	    protected void doRegister() throws Exception {
	        boolean selected = false;
	        for (;;) {
	        	// #unwrappedSelector() 方法，返回 Java 原生 NIO Selector 对象；每个 NioEventLoop 对象上，都独有一个 Selector 对象。
	        	// 调用 #javaChannel() 方法，获得 Java 原生 NIO 的 Channel 对象。
	        	// 注册 Java 原生 NIO 的 Channel 对象到 Selector 对象上
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
                return;
	        }
	    }
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

	// 收到客户端连接请求
	@Override
    protected int doReadMessages(List<Object> buf) throws Exception {
        SocketChannel ch = SocketUtils.accept(javaChannel());
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
}
