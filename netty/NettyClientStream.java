public class NettyClientStream extends AbstractClientStream {
	// 发送请求报文
	@Override
    public void send(Object packet) {
        channel.writeAndFlush(packet);
    }
}

// consumer的client端
public class NettyClient extends AbstractClient {
	@Override
    public ClientStream connect(final ConnectionID connectionID) {
        Bootstrap bootstrap = new Bootstrap();
		// EventLoopGroup其实就是管理线程
        bootstrap.group(Holder.WORKER_POOL)//
				// 禁用了Nagle算法，允许小包的发送。对于延时敏感型，同时数据传输量比较小的应用，开启TCP_NODELAY选项无疑是一个正确的选择。
                .option(ChannelOption.TCP_NODELAY, true)//
				// 如果其绑定的ip和port和一个处于TIME_WAIT状态的socket冲突时，内核将忽略这种冲突
                .option(ChannelOption.SO_REUSEADDR, true)//
				// 默认使用对象池
                .option(ChannelOption.ALLOCATOR, Holder.byteBufAllocator)//
                .option(ChannelOption.AUTO_CLOSE, Boolean.TRUE)
				// 一个连接的远端关闭时本地端是否关闭，默认值为False。值为False时，连接自动关闭；
                .option(ChannelOption.ALLOW_HALF_CLOSURE, Boolean.FALSE)
                .channel(NioSocketChannel.class)//
                .handler(new ChannelInitializer<NioSocketChannel>() {

                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast("protocol", new NettyProtocolHandler())
                                .addLast("clientIdleHandler", new IdleStateHandler(getHbSentInterval(), 0, 0))
                                .addLast("clientHandler",
                                        new NettyClientStreamHandler(NettyClient.this, connectionID,
                                                clientStreamLifecycleListeners, clientStreamMessageListeners));
                    }
                });

        //int connectTimeout = connectionID.getParameter(TRConstants.CONNECT_TIMEOUT_KEY, 4000);
        int connectTimeout = connectionID.getServiceURL().getParameter(CONNECT_TIMEOUT_KEY, 4000);
        if (connectTimeout < 1000) {
            connectTimeout = 4000;
        }
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout);

        if (isWaterMarkEnabled()) {
            bootstrap.option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                    new WriteBufferWaterMark(getLowWaterMark(), getHighWaterMark()));
        }

        String targetIP = connectionID.getServiceURL().getHost();
        int targetPort = connectionID.getServiceURL().getPort();
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(targetIP, targetPort));
        future.awaitUninterruptibly();

        ClientStream result = null;
        if (future.isSuccess()) {
            if (StreamUtils.streamOfChannel(future.channel()) == null) {
                NettyClientStream clientStream = new NettyClientStream(connectionID, future.channel());
                clientStream.setClient(this);
                StreamUtils.bindChannel(future.channel(), clientStream);
            }
            result = (ClientStream) StreamUtils.streamOfChannel(future.channel());
        }

        return result;
    }
}

public class Bootstrap extends AbstractBootstrap<Bootstrap, Channel> {
	// Bootstrap.connect实质是调用doConnect
	private static void doConnect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise connectPromise) {

        final Channel channel = connectPromise.channel();
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                channel.connect(remoteAddress, connectPromise);
                connectPromise.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        });
    }

}

// 客户端的启动类父类,使用自限定范式
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {

	final ChannelFuture initAndRegister() {
		// 这里通过Class的反射newInstance创建channel实例
        Channel channel = channelFactory.newChannel();
		// init的实现是在子类: 客户端Bootstrap和服务端ServerBootstrap
		init(channel);
		// 将初始化好的 Channel 注册到 EventGroup 中
        ChannelFuture regFuture = config().group().register(channel);
        return regFuture;
    }
}

// 客户端NIO的Channel实现
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {
	// 构造方法，DEFAULT_SELECTOR_PROVIDER和操作系统有关，OSX上是KQueueSelectorProvider
	public NioSocketChannel() {
        this(DEFAULT_SELECTOR_PROVIDER);
    }
}

public abstract class AbstractNioByteChannel extends AbstractNioChannel {
	// 父类的构造函数,parent是null
	protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
		super(parent, ch, SelectionKey.OP_READ);
	}

	// 上一个super的实现
	protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent);
        this.ch = ch;
        this.readInterestOp = readInterestOp;
		// NIO默认设置成非阻塞
        ch.configureBlocking(false);
    }

	// 上一个super的实现
	protected AbstractChannel(Channel parent) {
        this.parent = parent;
        id = newId();
		// NioSocketChannelUnsafe实例
        unsafe = newUnsafe();
		// 每个channel有它自己的管道
        pipeline = newChannelPipeline();
    }

	// AbstractBootstrap.initAndRegister -> group().register(channel) -> MultithreadEventLoopGroup.register 
	//  -> 通过 next() 获取一个可用的 SingleThreadEventLoop ->  SingleThreadEventLoop.register -> AbstractUnsafe.register
	//  -> AbstractUnsafe.register0
	private void register0(ChannelPromise promise) {
		// 调用具体channel子类的方法，NIO是把channel注册到eventLoop的selector上
		doRegister();

		pipeline.fireChannelRegistered();
	}

	@Override
    protected void doRegister() throws Exception {
		// 将底层的socketChannel注册到eventLoop的selector上
		selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
		return;
    }
}

public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {
	@Override
	public final void register(EventLoop eventLoop, final ChannelPromise promise) {
		// channel和eventloop产生引用关联
		AbstractChannel.this.eventLoop = eventLoop;

		if (eventLoop.inEventLoop()) {
			register0(promise);
		} else {
			try {
				eventLoop.execute(new Runnable() {
					@Override
					public void run() {
						register0(promise);
					}
				});
			} catch (Throwable t) {
				logger.warn(
						"Force-closing a channel whose registration task was not accepted by an event loop: {}",
						AbstractChannel.this, t);
				closeForcibly();
				closeFuture.setClosed();
				safeSetFailure(promise, t);
			}
		}
	}

	@Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }
}

protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {
	@Override
	public final void connect(
			final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
		// 调用java nio的connect
		doConnect(remoteAddress, localAddress);
		// 触发fireChannelActive事件,发送通道激活消息,Inbound事件的起点
		fulfillConnectPromise(promise, wasActive);
	}
}

protected abstract class AbstractUnsafe implements Unsafe {
	 @Override
	public final void register(EventLoop eventLoop, final ChannelPromise promise) {

		AbstractChannel.this.eventLoop = eventLoop;

		// 判断是在eventLoop子线程，还是在主线程
		if (eventLoop.inEventLoop()) {
			register0(promise);
		} else {
			// 执行子线程注册
			eventLoop.execute(new Runnable() {
				@Override
				public void run() {
					register0(promise);
				}
			});
		}
	}
}

public class DefaultChannelPipeline implements ChannelPipeline {

	protected DefaultChannelPipeline(Channel channel) {
        this.channel = ObjectUtil.checkNotNull(channel, "channel");

		// 管道机制的关键：双向链表结构
        tail = new TailContext(this); // tail是ChannelInboundHandler接口
        head = new HeadContext(this); // head是ChannelOutboundHandler

        head.next = tail;
        tail.prev = head;
    }

	// Bootstrap的connect实质是channel的connect -> pipeline.connect
	// 从链表的tail节点开始往前找，默认一直找到head节点的connect
	@Override
    public final ChannelFuture connect(SocketAddress remoteAddress) {
        return tail.connect(remoteAddress);
    }
}

final class HeadContext extends AbstractChannelHandlerContext
            implements ChannelOutboundHandler, ChannelInboundHandler {

	private final Unsafe unsafe;

	HeadContext(DefaultChannelPipeline pipeline) {
		// head调用父类构造是false+true, tail相反是true+false
		super(pipeline, null, HEAD_NAME, false, true);
		unsafe = pipeline.channel().unsafe();
		setAddComplete();
	}

	// 默认会一直传递到最后一个OutBoundChannelHandler
	@Override
	public void connect(ChannelHandlerContext ctx,
			SocketAddress remoteAddress, SocketAddress localAddress,
			ChannelPromise promise) throws Exception {
		unsafe.connect(remoteAddress, localAddress, promise);
	}
}

abstract class AbstractChannelHandlerContext extends DefaultAttributeMap
        implements ChannelHandlerContext, ResourceLeakHint {
	@Override
    public ChannelFuture connect(
        final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {

		// 从 DefaultChannelPipeline 内的双向链表的 tail 开始, 不断向前寻找第一个 outbound 为 true 的 AbstractChannelHandlerContext
        final AbstractChannelHandlerContext next = findContextOutbound();
		// 调用outbound是true的context的 invokeConnect 方法
        next.invokeConnect(remoteAddress, localAddress, promise);
        return promise;
    }

	// 注册channel时候触发
	@Override
    public ChannelHandlerContext fireChannelRegistered() {
		// findContextInbound 获取pipeline双向链表里第1个inbound的ChannelHandlerContext, 也就是ChannelInitializer的ChannelHandlerContext
		// ChannelInitializer就是Bootstrap.handler(new ChannelInitializer(...))里添加的自定义ChannelHandler
        invokeChannelRegistered(findContextInbound());
        return this;
    }

	private void invokeChannelRegistered() {
		// handler返回的就是context下的ChannelHandler，执行注册
		// 如果是ChannelInitializer，就会触发在Bootstrap里自定义的initChannel方法,remove自己替换真正的ChannelHandler
        ((ChannelInboundHandler) handler()).channelRegistered(this);
    }
}
	

public abstract class ChannelInitializer<C extends Channel> extends ChannelInboundHandlerAdapter {
	public final void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        if (initChannel(ctx)) {
            ctx.pipeline().fireChannelRegistered();
        } else {
            ctx.fireChannelRegistered();
        }
    }
}
