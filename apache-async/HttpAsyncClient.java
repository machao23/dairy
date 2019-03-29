// 这个类直接被Adapter引用
public abstract class CloseableHttpAsyncClient implements HttpAsyncClient, Closeable {

	// 引用的入口方法
	public Future<HttpResponse> execute(final HttpUriRequest request,
										final FutureCallback<HttpResponse> callback) {
        return execute(request, HttpClientContext.create(), callback);
    }
}

// 介于入口父类和Internal默认子类中间一层的Base类
abstract class CloseableHttpAsyncClientBase extends CloseableHttpPipeliningClient {

}

// CloseableHttpAsyncClient的默认子类
class InternalHttpAsyncClient extends CloseableHttpAsyncClientBase {
	@Override
    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext context,
            final FutureCallback<T> callback) {
    	// 会检查父类status是不是等于ACTIVE，现在发现会有STOP的情况？
        ensureRunning();
        final BasicFuture<T> future = new BasicFuture<T>(callback);
        final HttpClientContext localcontext = HttpClientContext.adapt(
            context != null ? context : new BasicHttpContext());
        setupContext(localcontext);

        @SuppressWarnings("resource")
        final DefaultClientExchangeHandlerImpl<T> handler = new DefaultClientExchangeHandlerImpl<T>(
            this.log,
            requestProducer,
            responseConsumer,
            localcontext,
            future,
            this.connmgr,
            this.connReuseStrategy,
            this.keepaliveStrategy,
            this.exec);
        try {
            handler.start();
        } catch (final Exception ex) {
            handler.failed(ex);
        }
        return future;
    }
}