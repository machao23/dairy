// 只在我们自己的NettyWebClinet创建时候才会创建一个HttpClient
public class HttpClient implements NettyConnector<HttpClientResponse, HttpClientRequest> {

	// 每个HttpClient都有一个对应的TcpClient，TcpBridgeClient是TcpClient的父类
	final TcpBridgeClient client;

	// 构造方法
	private HttpClient(HttpClient.Builder builder) {
		HttpClientOptions.Builder clientOptionsBuilder = HttpClientOptions.builder();
		builder.options.accept(clientOptionsBuilder);
		// 首次调用HttpResources.get初始化连接池，后续复用连接池
		// 这里也可以看到调用的静态方法，涉及到全局共享，在TF里就是SR、CR和OTHER这3个HttpClient实例共享HttpResources
		// 后续每次请求都会从poolResources里获取复用实例
		clientOptionsBuilder.loopResources(HttpResources.get());
		clientOptionsBuilder.poolResources(HttpResources.get());
		this.options = clientOptionsBuilder.build();
		this.client = new TcpBridgeClient(options);
	}

	// 发送Http请求
    public Mono<HttpClientResponse> request(HttpMethod method, String url,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {

    	// 类似reactor-core，先实例化一个mono类，后续通过subscribe或者block来触发实际请求
		return new MonoHttpClientResponse(this, url, method, handler(handler, options));
	}

	// HttpClient和TcpClient中间的bridge
	final class TcpBridgeClient extends TcpClient implements BiConsumer<ChannelPipeline, ContextHandler<Channel>> {
	}
}

// 所有HttpClient实例复用HttpResources
public final class HttpResources extends TcpResources {
	// 本质上就是一个工厂方法，创建一个单例HttpResources并复用
	public static HttpResources get() {
		// 实质是调用父类 TcpResources.getOrCreate
		return getOrCreate(httpResources, null, null, ON_HTTP_NEW, "http");
	}

	// httpResources因为是static变量，所以是全局变量
	// 表示httpResources是被所有httpClient共享的
	static final AtomicReference<HttpResources>	httpResources;
	static final BiFunction<LoopResources, PoolResources, HttpResources> ON_HTTP_NEW;
	static {
		ON_HTTP_NEW = HttpResources::new; // HttpResources的构造方法
		httpResources = new AtomicReference<>();
	}
}

public class TcpResources implements PoolResources, LoopResources {

	// 全局变量 HttpResources 里获取 HttpResources实例变量，没有就创建 HttpResources 实例，也是static方法
	protected static <T extends TcpResources> T getOrCreate(AtomicReference<T> ref, LoopResources loops, PoolResources pools,
			BiFunction<LoopResources, PoolResources, T> onNew, String name) {
		for (; ; ) {
			// 静态变量 AtomicReference<HttpResources> 没有就创建，之后可以复用
			T resources = ref.get();
			if (resources == null || loops != null || pools != null) {
				T update = create(resources, loops, pools, name, onNew);
				if (ref.compareAndSet(resources, update)) {
					return update;
				}
			}
			else {
				return resources;
			}
		}
	}

	// 创建一个HttpResources的实例变量，初始化属性PoolResources和LoopResources，并返回
	static <T extends TcpResources> T create(T previous,
			LoopResources loops,
			PoolResources pools,
			String name,
			BiFunction<LoopResources, PoolResources, T> onNew) {
		loops = loops == null ? LoopResources.create("reactor-" + name) : loops;
		pools = pools == null ? PoolResources.elastic(name) : pools;
		return onNew.apply(loops, pools);
	}
}
// 一个HttpClient包含一个TcpClient的子类TcpBridgeClient
public class TcpClient implements NettyConnector<NettyInbound, NettyOutbound> {

	// 每次请求都会由MonoHttpClientResponse调用newHandler
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
			// 这里的映射检索，其实就是根据请求地址
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
