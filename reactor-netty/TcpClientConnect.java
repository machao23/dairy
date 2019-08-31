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

	@Override
	public void run() {
		Channel c = f.getNow();
		pool.activeConnections.incrementAndGet();
		pool.inactiveConnections.decrementAndGet();


		ConnectionObserver current = c.attr(OWNER)
		                              .getAndSet(this);

		if (current instanceof PendingConnectionObserver) {
			PendingConnectionObserver pending = (PendingConnectionObserver)current;
			PendingConnectionObserver.Pending p;
			current = null;
			registerClose(c, pool);

			while((p = pending.pendingQueue.poll()) != null) {
				if (p.error != null) {
					onUncaughtException(p.connection, p.error);
				}
				else if (p.state != null) {
					onStateChange(p.connection, p.state);
				}
			}
		}
		else if (current == null) {
			registerClose(c, pool);
		}


		if (current != null) {
			Connection conn = Connection.from(c);
			if (log.isDebugEnabled()) {
				log.debug(format(c, "Channel acquired, now {} active connections and {} inactive connections"),
						pool.activeConnections, pool.inactiveConnections);
			}
			obs.onStateChange(conn, State.ACQUIRED);

			PooledConnection con = conn.as(PooledConnection.class);
			if (con != null) {
				ChannelOperations<?, ?> ops = pool.opsFactory.create(con, con, null);
				if (ops != null) {
					ops.bind();
					obs.onStateChange(ops, State.CONFIGURED);
					sink.success(ops);
				}
				else {
					//already configured, just forward the connection
					sink.success(con);
				}
			}
			else {
				//already bound, just forward the connection
				sink.success(conn);
			}
			return;
		}
		//Connected, leave onStateChange forward the event if factory

		if (log.isDebugEnabled()) {
			log.debug(format(c, "Channel connected, now {} active " +
							"connections and {} inactive connections"),
					pool.activeConnections, pool.inactiveConnections);
		}
		if (pool.opsFactory == ChannelOperations.OnSetup.empty()) {
			sink.success(Connection.from(c));
		}
	}
}