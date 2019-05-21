public class AsyncExecutionInterceptor extends AsyncExecutionAspectSupport implements MethodInterceptor, Ordered {
	
	// 异步执行代理方法，立即返回给调用方
	@Override
	public Object invoke(final MethodInvocation invocation) throws Throwable {
		Class<?> targetClass = (invocation.getThis() != null ? AopUtils.getTargetClass(invocation.getThis()) : null);
		Method specificMethod = ClassUtils.getMostSpecificMethod(invocation.getMethod(), targetClass);
		final Method userDeclaredMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);

		AsyncTaskExecutor executor = determineAsyncExecutor(userDeclaredMethod);
		Callable<Object> task = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				// 封装在Callable对象的call方法里执行
				Object result = invocation.proceed();
				if (result instanceof Future) {
					// 允许异步方法返回Future
					return ((Future<?>) result).get();
				}
			
				return null;
			}
		};

		return doSubmit(task, executor, invocation.getMethod().getReturnType());
	}
}

// AsyncExecutionInterceptor 拦截器的父类，辅助用
public abstract class AsyncExecutionAspectSupport implements BeanFactoryAware {
	
	protected Object doSubmit(Callable<Object> task, AsyncTaskExecutor executor, Class<?> returnType) {
		if (completableFuturePresent) {
			Future<Object> result = CompletableFutureDelegate.processCompletableFuture(returnType, task, executor);
			if (result != null) {
				return result;
			}
		}
		
		executor.submit(task);
		return null;
	}
	
	private static class CompletableFutureDelegate {

		public static <T> Future<T> processCompletableFuture(Class<?> returnType, final Callable<T> task, Executor executor) {
			if (!CompletableFuture.class.isAssignableFrom(returnType)) {
				return null;
			}
			return CompletableFuture.supplyAsync(new Supplier<T>() {
				@Override
				public T get() {
					try {
						return task.call();
					}
					catch (Throwable ex) {
						throw new CompletionException(ex);
					}
				}
			}, executor);
		}
	}
	
	@UsesJava8
	private static class CompletableFutureDelegate {

		public static <T> Future<T> processCompletableFuture(Class<?> returnType, final Callable<T> task, Executor executor) {
			// 外部定义的异步方法返回的不是 CompletableFuture 类型，就跳过
			if (!CompletableFuture.class.isAssignableFrom(returnType)) {
				return null;
			}
			// CompletableFuture 异步执行指定方法
			return CompletableFuture.supplyAsync(new Supplier<T>() {
				@Override
				public T get() {
					return task.call();
				}
			}, executor);
		}
	}
}