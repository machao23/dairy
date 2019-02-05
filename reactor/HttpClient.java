public class HttpClient implements NettyConnector<HttpClientResponse, HttpClientRequest> {
    public Mono<HttpClientResponse> request(HttpMethod method, String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {

    	// 类似reactor-core，先实例化一个mono类，后续通过subscribe或者block来触发实际请求
		return new MonoHttpClientResponse(this, url, method, handler(handler, options));
	}
}

final class MonoHttpClientResponse extends Mono<HttpClientResponse> {

	// http的客户端实例
	final HttpClient                                                     parent;
	// 服务端地址
	final URI                                                            startURI;
	// GET或PUT
	final HttpMethod                                                     method;
	final Function<? super HttpClientRequest, ? extends Publisher<Void>> handler;


}