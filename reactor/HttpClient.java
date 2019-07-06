// 只在我们自己的NettyWebClinet创建时候才会创建一个HttpClient
public class HttpClient implements NettyConnector<HttpClientResponse, HttpClientRequest> {

	// 每个HttpClient都有一个对应的TcpClient，TcpBridgeClient是TcpClient的父类
	final TcpBridgeClient client;

	// 构造方法
	private HttpClient(HttpClient.Builder builder) {
		HttpClientOptions.Builder clientOptionsBuilder = HttpClientOptions.builder();
		if (Objects.nonNull(builder.options)) {
			builder.options.accept(clientOptionsBuilder);
		}
		if (!clientOptionsBuilder.isLoopAvailable()) {
			// 首次调用HttpResources.get初始化连接池，后续复用连接池
			// 这里也可以看到调用的静态方法，涉及到全局共享
			clientOptionsBuilder.loopResources(HttpResources.get());
		}
		if (!clientOptionsBuilder.isPoolAvailable() && !clientOptionsBuilder.isPoolDisabled()) {
			clientOptionsBuilder.poolResources(HttpResources.get());
		}
		this.options = clientOptionsBuilder.build();
		this.client = new TcpBridgeClient(options);
	}

    public Mono<HttpClientResponse> request(HttpMethod method, String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {

    	// 类似reactor-core，先实例化一个mono类，后续通过subscribe或者block来触发实际请求
		return new MonoHttpClientResponse(this, url, method, handler(handler, options));
	}

	// HttpClient和TcpClient中间的bridge
	final class TcpBridgeClient extends TcpClient implements
	                                              BiConsumer<ChannelPipeline, ContextHandler<Channel>> {
	}
}

// 一个HttpClient包含一个TcpClient的子类TcpBridgeClient
public class TcpClient implements NettyConnector<NettyInbound, NettyOutbound> {

	protected Mono<? extends NettyContext> newHandler(BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler,
			InetSocketAddress address,
			boolean secure,
			Consumer<? super Channel> onSetup) {
		return Mono.create(sink -> {
			SocketAddress remote = address != null ? address : options.getAddress();
			// 创建连接池
			PoolResources poolResources = options.getPoolResources();
			// TcpResources.selectOrCreate 调用的是 DefaultPoolResources.selectOrCreate
			ChannelPool pool = poolResources.selectOrCreate(remote, options,
					doHandler(null, sink, secure, remote, null, null),
					options.getLoopResources().onClient(options.preferNative()));
		}
	});
}

final class DefaultPoolResources implements PoolResources {
	@Override
	public ChannelPool selectOrCreate(SocketAddress remote,
			Supplier<? extends Bootstrap> bootstrap,
			Consumer<? super Channel> onChannelCreate,
			EventLoopGroup group) {
		SocketAddressHolder holder = new SocketAddressHolder(remote);
		for (; ; ) {
			Pool pool = channelPools.get(holder);
			if (pool != null) {
				return pool;
			}
			pool = new Pool(bootstrap.get().remoteAddress(remote), provider, onChannelCreate, group);
			if (channelPools.putIfAbsent(holder, pool) == null) {
				return pool;
			}
		}
	}
}

// 每次发送http请求都需要新建MonoHttpClientResponse实例
final class MonoHttpClientResponse extends Mono<HttpClientResponse> {

	// http的客户端实例
	final HttpClient                                                     parent;
	// 服务端地址
	final URI                                                            startURI;
	// GET或PUT
	final HttpMethod                                                     method;
	final Function<? super HttpClientRequest, ? extends Publisher<Void>> handler;

	// 发送请求时触发
	public void subscribe(final CoreSubscriber<? super HttpClientResponse> subscriber) {
		ReconnectableBridge bridge = new ReconnectableBridge();
		bridge.activeURI = startURI;

		// HttpClient.TcpBridgeClient.newHandler 本质时调用父类 TcpClient.newHandler
		// 每次都会new一个HttpClientHandler，用来发送http请求
		Mono.defer(() -> parent.client.newHandler(new HttpClientHandler(this, bridge),
				parent.options.getRemoteAddress(bridge.activeURI),
				HttpClientOptions.isSecure(bridge.activeURI),
				bridge))
		    .retry(bridge)
		    .cast(HttpClientResponse.class)
		    .subscribe(subscriber);
	}
}

public final class HttpResources extends TcpResources {
	public static HttpResources get() {
		// 实质是调用父类 TcpResources.getOrCreate
		return getOrCreate(httpResources, null, null, ON_HTTP_NEW, "http");
	}

	// httpResources因为是static变量，所以是全局变量
	// 表示httpResources是被所有httpClient共享的
	static final AtomicReference<HttpResources>	httpResources;
	static {
		httpResources = new AtomicReference<>();
	}
}

public class TcpResources implements PoolResources, LoopResources {
	protected static <T extends TcpResources> T getOrCreate(AtomicReference<T> ref,
			LoopResources loops,
			PoolResources pools,
			BiFunction<LoopResources, PoolResources, T> onNew,
			String name) {
		T update;
		for (; ; ) {
			T resources = ref.get();
			if (resources == null || loops != null || pools != null) {
		}
	}
}