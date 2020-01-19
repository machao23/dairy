final class TcpClientConnect extends TcpClient {
	@Override
	public Mono<? extends Connection> connect(Bootstrap b) {
		// 从连接池里获取连接
		return provider.acquire(b);
	}
}

public class SimpleChannelPool implements ChannelPool {
	private final Deque<Channel> deque = new ConcurrentLinkedDeque<C>();
	
	private Future<Channel> acquireHealthyFromPoolOrNew(final Promise<Channel> promise) {
        try {
            final Channel ch = pollChannel();
            if (ch == null) {
                // No Channel left in the pool bootstrap a new Channel
				// 连接池里没有连接，创建一个新的Channel
                Bootstrap bs = bootstrap.clone();
                bs.attr(POOL_KEY, this);
                // 本质是调用Netty的Bootstrap.connect
                ChannelFuture f = connectChannel(bs);
                if (f.isDone()) {
                    notifyConnect(f, promise);
                } else {
                    f.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            notifyConnect(future, promise);
                        }
                    });
                }
                return promise;
            }
            EventLoop loop = ch.eventLoop();
            if (loop.inEventLoop()) {
                doHealthCheck(ch, promise);
            } else {
                loop.execute(new Runnable() {
                    @Override
                    public void run() {
                        doHealthCheck(ch, promise);
                    }
                });
            }
        } catch (Throwable cause) {
            promise.tryFailure(cause);
        }
        return promise;
    }
	
	protected Channel pollChannel() {
        return lastRecentUsed ? deque.pollLast() : deque.pollFirst();
    }

    protected ChannelFuture connectChannel(Bootstrap bs) {
        return bs.connect();
    }

    // 归还连接到连接池
    @Override
    public Future<Void> release(final Channel channel, final Promise<Void> promise) {
        EventLoop loop = channel.eventLoop();
        if (loop.inEventLoop()) {
            doReleaseChannel(channel, promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    doReleaseChannel(channel, promise);
                }
            });
        }
        return promise;
    }

    // 入队列的方式归还连接到连接池
    protected boolean offerChannel(Channel channel) {
        return deque.offer(channel);
    }
	
	// 内部类，一个简单的连接池实现
	public class SimpleChannelPool implements ChannelPool {
		
		// 构造方法
		public SimpleChannelPool(Bootstrap bootstrap, final ChannelPoolHandler handler, ChannelHealthChecker healthCheck,
                             boolean releaseHealthCheck, boolean lastRecentUsed) {
			// Clone the original Bootstrap as we want to set our own handler
			this.bootstrap = checkNotNull(bootstrap, "bootstrap").clone();
			this.bootstrap.handler(new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(Channel ch) throws Exception {
					// 创建连接后回调, handler就是PooledConnectionProvider的内部类Pool
					handler.channelCreated(ch);
				}
			});
		}
	}
}

final class PooledConnectionProvider implements ConnectionProvider {

	// 获取连接池里的连接成功后的回调
	@Override
	public final void operationComplete(Future<Channel> f) throws Exception {
		Channel c = f.get();

		if (c.eventLoop().inEventLoop()) {
			run();
		}
		else {
			c.eventLoop().execute(this);
		}
	}

	// 注册连接关闭后的回调函数
	void registerClose(Channel c, Pool pool) {
		c.closeFuture()
		 .addListener(ff -> {
		     pool.release(c);
		     pool.inactiveConnections.decrementAndGet();
		 });
	}
	
	final static class Pool extends AtomicBoolean
			implements ChannelPoolHandler, ChannelPool, ChannelHealthChecker {
				
		// 连接创建后回调这个方法
		@Override
		public void channelCreated(Channel ch) {

			inactiveConnections.incrementAndGet();

			PooledConnection pooledConnection = new PooledConnection(ch, this);

			pooledConnection.bind();

			Bootstrap bootstrap = this.bootstrap.clone();

			BootstrapHandlers.finalizeHandler(bootstrap, opsFactory, pooledConnection);
			
			// 这里就是往netty的channel的pipeline里添加内置的handler
			// 最终会调用到 BootstrapInitializerHandler.initChannel, 添加内置的handler
			ch.pipeline()
			  .addFirst(bootstrap.config()
			                     .handler());
		}
	}
}

public abstract class BootstrapHandlers {
	@ChannelHandler.Sharable
	static final class BootstrapInitializerHandler extends ChannelInitializer<Channel> {
		// 初始化连接，添加内置的handler到pipeline
		@Override
		protected void initChannel(Channel ch) {
			if (pipeline != null) {
				// pipeline默认是有3个pipelineConfiguration
				// proxyHandler, HttpClientConnect.Http1Initializer, sslHandler
				for (PipelineConfiguration pipelineConfiguration : pipeline) {
					pipelineConfiguration.consumer.accept(listener, ch);
				}
			}

			ChannelOperations.addReactiveBridge(ch, opsFactory, listener);
		}
	}
}

final class HttpClientConnect extends HttpClient {
	
	static final class MonoHttpConnect extends Mono<Connection> {
		@Override
		public void subscribe(CoreSubscriber<? super Connection> actual) {
			Mono.<Connection>create(sink -> {
				Bootstrap finalBootstrap;
				//append secure handler if needed
				// 如果是HTTPS还需要添加https的处理handler
				if (handler.activeURI.isSecure()) {
					if (sslProvider == null) {
						//should not need to handle H2 case already checked outside of
						// this callback
						finalBootstrap = SslProvider.setBootstrap(b.clone(),
								HttpClientSecure.DEFAULT_HTTP_SSL_PROVIDER);
					}
					else {
						finalBootstrap = b.clone();
					}
				}

				tcpClient.connect(finalBootstrap)
				         .subscribe(new TcpClientSubscriber(sink));

			}).retry(handler).subscribe(actual);
		}
	}
	
	static final class Http1Initializer
			implements BiConsumer<ConnectionObserver, Channel>  {
		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			channel.pipeline()
			       .addLast(NettyPipeline.HttpCodec,
			                new HttpClientCodec(decoder.maxInitialLineLength(),
			                                    decoder.maxHeaderSize(),
			                                    decoder.maxChunkSize(),
			                                    decoder.failOnMissingResponse,
			                                    decoder.validateHeaders(),
			                                    decoder.initialBufferSize(),
			                                    decoder.parseHttpAfterConnectRequest));

			if (compress) {
				// 如果要处理的返回报文是gzip的，就要添加 HttpContentDecompressor
				channel.pipeline()
				       .addAfter(NettyPipeline.HttpCodec,
						       NettyPipeline.HttpDecompressor,
						       new HttpContentDecompressor());
			}
		}
	}
}