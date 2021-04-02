final class PooledConnectionProvider implements ConnectionProvider {
	// 实现了ChannelFutureListener接口，在selector收到connect结果后，DefaultPromise.notifyListener0 会通知到这个Listener
	final class PooledConnectionInitializer implements ChannelHandler, ChannelFutureListener {
		@Override
		public void operationComplete(ChannelFuture future) {
			if (future.isSuccess()) {
				sink.success(pooledConnection);
			} else {
				sink.error(future.cause());
			}
		}
	}
}