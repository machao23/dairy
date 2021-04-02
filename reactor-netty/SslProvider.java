public final class SslProvider {
	@Override
	// 在TCP连接建立后，SSL握手收到OP_READ应答后调用该方法
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
		SslHandshakeCompletionEvent handshake = (SslHandshakeCompletionEvent) evt;
		if (handshake.isSuccess()) {
			if (recorder != null) {
				recorder.recordTlsHandshakeTime(
						ctx.channel().remoteAddress(),
						Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
						SUCCESS);
			}
			// SSL握手成功，该Channel置为有效
			ctx.fireChannelActive();
		}
	}
}