class RedisReentrantLock implements AsyncDLock {
	
	// 异步锁
	public void tryLockAsync(LockCallback lockCallback, ExecutorService callbackExecutor, 
							long timeoutInMillis, boolean isAutoUnlockEnabled) {
	
		AsyncLockManager lockManager = AsyncLockManager.getInstance();
		lockManager.registerLock(this, lockCallback, requestInfo);
	}
	
	// 异步锁本质上还是调用同步锁方法
	public boolean tryLock(boolean reentrant) throws DistlockRejectedException {
		Long ttl = tryAcquire(this.lockValue, reentrant);
	}
	
	private Long tryAcquire(String lockValue, boolean reentrant) {
		// redis的Lua脚本实现加锁，这个是同步操作吗？
		return getRedisCommandExecutor().eval(LongConvertor.instance(), this.namespace, this.lockKey,
                LuaScriptType.LOCK.getLuaScript(),
                scriptKeys, scriptValues);
	}
}

class CRedisEvalExecutor implements RedisEvalExecutor {
	// 执行redis脚本命令
	public <T> T eval(Convertor<T> replyConvertor, final String namespace, String key,
                      final LuaScript luaScript, final List<String> scriptKeys, final List<String> scriptArgs) {
						  
		// getGroupByKey：通过key获取对应的redis服务器，key就是namespace
		ret = providerHelper.writeProForCat(new CacheProcExecutor<Object>() {
                @Override
                public Object exec(final RedisServer server) {
                    CommandExecutor<Object> cmd = new CommandExecutor<Object>() {
                        @Override
                        public Object exec(Jedis jedis) {
                            return jedis.evalsha(sha1, scriptKeys, scriptArgs);
                        }
                    };

                    return server.getHelper().execute(cmd, CRedisCommand.EXEC);
                }
            }, this.cacheProvider.getGroupByKey(key), key, "distlock-eval");
	}
}

public class ProviderHelper {
	private  <T> T writeProForCat(CacheProcExecutor<T> procExecutor, RedisGroup group, String key, String method) {
		// 执行匿名CacheProcExecutor的exec回调方法
		T ret = procExecutor.exec(svr);
	}
}

class AsyncLockManager {
	
	private ExecutorService bizExecutor;
	
	public void registerLock(AsyncDLock lock, LockCallback callback, LockRequestInfo requestInfo) {
        LockHolder lockHolder = new LockHolder();
        lockHolder.setCallback(callback);
		// 线程池异步处理lockHolder
        bizExecutor.execute(new BizTask(lockHolder));
    }
	
	class BizTask implements Runnable {
		private final LockHolder lockHolder;

        public BizTask(LockHolder lockHolder) {
            this.lockHolder = lockHolder;
        }
		
		public void run() {
			handle(lockHolder);
		}
	}
	
	private void handle(final LockHolder lockHolder) {
		boolean locked = false;
		locked = ((RedisReentrantLock) lockHolder.getLock()).tryLock(false);
		if (locked) {
			// 获取锁成功的逻辑
		} else {
			// 获取锁失败
			lockHolderManager.add(lockHolder);
		}
	}
}

class LockHolderManager {
	
	private final ConcurrentMap<String, Set<LockHolder>> locks = Maps.newConcurrentMap();
	
	public void add(LockHolder lockHolder) {
        String key = lockHolder.getLock().getLockKey();
        synchronized (StringInternerPool.intern(key)) {
            Set<LockHolder> lockHolders = locks.get(key);
            if (lockHolders == null) {
                lockHolders = Sets.newHashSet();
				// 获取锁失败后，加到等待通知的locks map里
                locks.put(key, lockHolders);
            }

            lockHolders.add(lockHolder);
        }
    }
}