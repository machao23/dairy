public class MapConfig extends AbstractConfiguration<Map<String, String>> {
	
	// 创建MapConfig实例，调用Loader.load时候传入gen，被loader回调用
	private static final DataLoader.Generator gen = new DataLoader.Generator<Map<String, String>>() {
        @Override
        public AbstractConfiguration<Map<String, String>> create(Feature feature, String fileName) {
            return new MapConfig(feature, fileName);
        }
    };
	
	// 私有构造方法，会添加监听配置文件变更的listener
	private final PropertiesChangedManager propertiesChangedManager = new PropertiesChangedManager();
	
	private MapConfig(Feature feature, String fileName) {
        super(feature, fileName);
        addListener(propertiesChangedManager);
    }
	
	// 入口，获取对应fileName的配置map
	public static MapConfig get(String groupName, String fileName, Feature feature) {
        return (MapConfig) loader.load(groupName, fileName, feature, gen);
    }

	// 拿到配置文件对应的map实例
	public Map<String, String> asMap() {
        waitFistLoad();
        return ref;
    }
}

// MapConfig的父类
public abstract class AbstractConfiguration<T> implements Configuration<T> {
	
	private final InitFuture future = new InitFuture();
	
	// 获取初始化的异步结果Future
	@Override
    public ListenableFuture<Boolean> initFuture() {
        return future;
    }
	
	// 等待配置文件首次加载完毕
	protected void waitFistLoad() {
        if (current.get() != null) return;
        initFuture().get();
    }
	
	// FileStore在加载完配置文件后，会调用setData，通知Future结束
	boolean setData(T data, boolean trigger) {
        synchronized (current) {

            current.set(data);
            onChanged();

            if (!future.isDone()) {
                future.set(true);
            }

            return triggers(data, trigger);
        }
    }
	
	// 触发回调listener
	private boolean triggers(T data, boolean trigger) {
        boolean result = true;
        for (ConfigListener<T> listener : listeners) {
            if (!trigger(listener, data)) result = false;
        }
        return result;
    }
}

abstract class AbstractDataLoader implements DataLoader {
	
	// 配置文件的本地缓存
	private static final ConcurrentMap<String, FileStore> USED_CONFIGS = new ConcurrentHashMap<String, FileStore>();
	
	
	// 加载配置文件
	public <T> Configuration<T> load(String groupName, String fileName, Feature feature, Generator<T> generator) {
		// 尝试命中本地缓存
		FileStore store = USED_CONFIGS.get(key);
        if (store != null) return store.getConfig();
		
		// 创建FileStore实例，放入本地缓存 USED_CONFIGS
		AbstractConfiguration<T> conf = generator.create(feature, fileName);
        FileStore newer = new FileStore(new Meta(groupName, fileName), conf, feature, CONFIG_LOGGER);
        store = USED_CONFIGS.putIfAbsent(key, newer);
		
		// 拿到本地缓存的配置文件版本
		Version ver = VERSIONS.get(store.getMeta());
		final Version nVer = ver;
		// 监听配置文件版本变更
		nVer.addListener(new Runnable() {
			@Override
			public void run() {
				ListenableFuture<VersionProfile> future = temp.initLoad(nVer.updated.get(), CLIENT, EXECUTOR);
				nVer.setUpdated(future.get());
			}
		}, EXECUTOR);
		// 返回MapConfig实例
		return store.getConfig();
	}
	
	private static void updateFile(final Meta meta, final VersionProfile newVersion, final CountDownLatch latch) {
		final FileStore fileStore = USED_CONFIGS.get(meta.getKey());
        final ListenableFuture<Snapshot<String>> future = CLIENT.loadData(meta, newVersion, fileStore == null ? Feature.DEFAULT : fileStore.getFeature());
        future.addListener(new Runnable() {
            public void run() {
                try {
                    Snapshot<String> snapshot = future.get();
					// 缓存配置到本地快照文件
                    FileStore.storeData(meta, newVersion, snapshot);
                    updateVersion(meta, newVersion, latch, snapshot);
                } finally {
                    setLoaded(meta);
                }
            }
        }, EXECUTOR);
	}
}

class FileStore<T> {
	
	// 初始化加载配置文件
	synchronized ListenableFuture<VersionProfile> initLoad(
			final VersionProfile version, QConfigServerClient client, Executor executor) {
		setVersion(version, null);
		result.set(version);
		return result;
	}
	
	synchronized void setVersion(VersionProfile version, Snapshot<String> snapshot) throws Exception {
        String data;
		// 读取本地快照文件的数据（快照文件的数据从哪来的？）
        if (snapshot != null) {
            data = snapshot.getContent();
        } else {
            data = loadSnapshot(version);
        }
		
		//触发配置变更逻辑
         boolean success = conf.setData(t);
	}
}

class LongPoller implements Runnable {
	private Optional<CountDownLatch> reLoading() throws Exception {
		// 长轮询配置文件变更， 虽然是异步返回，但是这里用了get阻塞等待
		TypedCheckResult remote = CLIENT.longPollingCheckUpdate(map).get();
        return this.callback.onChanged(map, remote);
	}
}

class QConfigHttpServerClient implements QConfigServerClient {
	private abstract static class RetryFuture<T> extends AbstractFuture<T> {
		// 发起异步长轮询请求
        final com.ning.http.client.ListenableFuture<Response> future = HttpClientHolder.INSTANCE.executeRequest(request);
		// 注册回调
		future.addListener(new Runnable() {
			@Override
			public void run() {
				Response response;
				response = future.get();
				if (!process(response)) {
					failOver();
					request();
                }
			}
		}
	}
	
	// RetryFuture的子类，实在处理配置文件变更升级的逻辑
	private static class CheckUpdateFuture extends RetryFuture<TypedCheckResult> {
	}
}