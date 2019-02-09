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

        int connectTimeout = connectionID.getServiceURL().getParameter(CONNECT_TIMEOUT_KEY, 4000);
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

    // 默认的地址解析器，解析服务端地址
    private volatile AddressResolverGroup<SocketAddress> resolver =
            (AddressResolverGroup<SocketAddress>) DEFAULT_RESOLVER;

    private ChannelFuture doResolveAndConnect(final SocketAddress remoteAddress, final SocketAddress localAddress) {
        // 初始化channel注册到eventLoop（本质是注册JDK的channel到JDK的selector上）
        final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();
        return doResolveAndConnect0(channel, remoteAddress, localAddress, channel.newPromise());
    }

    // Bootstrap.connect实质是调用doConnect
    // doResolveAndConnect0里会被调用
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

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        // 绑定本地地址
        if (localAddress != null) {
            doBind0(localAddress);
        }

        boolean success = false;
        boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
        if (!connected) {
            // 若未连接完成，则关注连接( OP_CONNECT )事件。
            selectionKey().interestOps(SelectionKey.OP_CONNECT);
        }
        success = true;
        return connected;
    }

    // selector轮询到OP_CONNECT时触发
    // 调用JDK的 SocketChannel#finishConnect() 方法，完成连接。
    @Override
    protected void doFinishConnect() throws Exception {
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    // 读取channel自己本身的数据到byteBuf，
    // 在父类AbstractNioByteChannel.read时候被调用
    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        allocHandle.attemptedBytesRead(byteBuf.writableBytes());
        return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
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

    protected class NioByteUnsafe extends AbstractNioUnsafe {
        @Override
        public final void read() {
            final ChannelConfig config = config();
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            final ByteBufAllocator allocator = config.getAllocator();
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false; // 是否关闭连接
            try {
                do {
                    byteBuf = allocHandle.allocate(allocator);
                    // 读取数据，然后设置最后读取字节数
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    if (allocHandle.lastBytesRead() <= 0) { // 没有读取到数据，释放buffer
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        close = allocHandle.lastBytesRead() < 0; // 小于0说明对端已经关闭
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break;
                    }
                    // 走到这里表示读取到数据了
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    // 触发channelRead事件
                    // 一般情况下，我们会在自己的 Netty 应用程序中，自定义 ChannelHandler 处理读取到的数据。
                    // 最终会被 pipeline 中的尾节点 TailContext 所处理，释放ByteBuf对象
                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;
                } while (allocHandle.continueReading());

                allocHandle.readComplete();
                // 触发读取完成事件
                pipeline.fireChannelReadComplete();

                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
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

    // 写数据被调用，委托给pipeline做
    // DefaultChannelPipeline 会调用TailContext.write 把write事件从尾节点向头节点传播
    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    protected abstract class AbstractUnsafe implements Unsafe {
        // 内存队列，用于缓存写入的数据( 消息 )。
        private volatile ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);

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

        // 在headContext最后处理write事件写数据时被调用
        @Override
        public final void write(Object msg, ChannelPromise promise) {
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            int size;
            // 过滤写入的消息
            msg = filterOutboundMessage(msg);
            // 计算消息长度
            size = pipeline.estimatorHandle().size(msg);
            if (size < 0) {
                size = 0;
            }
            // 写消息到内存队列
            outboundBuffer.addMessage(msg, size, promise);
        }
    }
}

protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {
	@Override
	public final void connect(
			final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
		// 调用java nio的connect
		if (doConnect(remoteAddress, localAddress)) {
            // 触发fireChannelActive事件,发送通道激活消息,Inbound事件的起点
            fulfillConnectPromise(promise, wasActive);
        } else {
            connectPromise = promise;
            requestedRemoteAddress = remoteAddress;

            // Schedule connect timeout.
            int connectTimeoutMillis = config().getConnectTimeoutMillis();
            if (connectTimeoutMillis > 0) {
                 // 使用 EventLoop 发起定时任务，监听连接远程地址超时。若连接超时，则回调通知 connectPromise 超时异常。
                connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                    @Override
                    public void run() {
                        ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                        ConnectTimeoutException cause =
                                new ConnectTimeoutException("connection timed out: " + remoteAddress);
                        if (connectPromise != null && connectPromise.tryFailure(cause)) {
                            close(voidPromise());
                        }
                    }
                }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
            }

            // 添加监听器，监听连接远程地址取消。
            promise.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isCancelled()) {
                        // 取消定时任务
                        if (connectTimeoutFuture != null) {
                            connectTimeoutFuture.cancel(false);
                        }
                        // 置空 connectPromise
                        connectPromise = null;
                        close(voidPromise());
                    }
                }
            });
        }	
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
