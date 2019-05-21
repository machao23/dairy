public abstract class ServiceClientBase<DerivedClient extends ServiceClientBase<?>> {
	
	private CloseableHttpAsyncClient _asyncClient;
	
	// 发起异步请求
	private <TResp> ListenableFuture<HttpResponse> executeAsync(HttpPost request, final ExecutionContext<TResp> executionContext, final ClientAsyncCatTransaction clientAsyncCatTransaction,
                                                                final com.ctrip.soa.caravan.hystrix.ExecutionContext chystrixExecutionContext, final ExecutionCommand executionCommand)
		FutureCallback<HttpResponse> callback = new FutureCallback<HttpResponse>() {
			@Override
            public void completed(HttpResponse result) {
				// 待返回的future里赋值Http结果
                future.set(result);
            }
		}
		
		// Apache async http client 里注册该回调Callback
		 _asyncClient.execute(request, callback);
	}
}

public abstract class ServiceRequestHandlerBase implements RequestHandler {
	// 处理异步返回futrue结果
	private void handleInternalAsync(final ServiceHost host, final OperationHandler handler, final HttpRequestWrapper request,
            final HttpResponseWrapper response, final ExecutionContext context, final OperationStats operationStats,
            final StopWatch reqStopWatch, final StopWatch serviceLatencyStopWatch, final long startTime, final CatTransaction serviceCatTransaction) {
	
		final ListenableFuture<?> responseFuture = (ListenableFuture<?>)response.responseObject();
		final AsyncContext asyncContext = request.startAsync();
		response.startAsync(asyncContext);
		asyncContext.addListener(new AsyncListener() {
			...
		}
		
		// 注册future的回调: 拿到http异步的结果后返回给soa的client
		responseFuture.addListener(new Runnable() {
			public void run() {
				Object realResponse = responseFuture.get();
				response.setResponseObject(realResponse);
				writeResponse(host, handler, request, response);
			}
		}, getCallbackExecutor(host.getServiceMetaData().getRefinedServiceName()));
	}
	
// GUAVA实现的可以监听回调的Future
public abstract class AbstractFuture<V> implements ListenableFuture<V> {
	
	public void addListener(Runnable listener, Executor executor) {
		Listener oldHead = listeners;
		if (oldHead != Listener.TOMBSTONE) {
		  // 创建一个listener，挂在listeners链表上，如果future没有完成，后续会被回调
		  Listener newNode = new Listener(listener, executor);
		  do {
			newNode.next = oldHead;
			if (ATOMIC_HELPER.casListeners(this, oldHead, newNode)) {
				// 如果future没有完成，直接返回
				return;
			}
			oldHead = listeners;  // re-read
		  } while (oldHead != Listener.TOMBSTONE);
		}
		// If we get here then the Listener TOMBSTONE was set, which means the future is done, call
		// the listener.
		// 走到这里说明future已经完成，使用指定的executor 指定回调逻辑listener
		executeListener(listener, executor);
	  }
	}
	
	// 调用方拿到结果后set给future，通知future已经完成了
	protected boolean set(@Nullable V value) {
		Object valueToSet = value == null ? NULL : value;
		if (ATOMIC_HELPER.casValue(this, null, valueToSet)) {
			// 执行回调逻辑
			complete();
			return true;
		}
		return false;
	}
	
	/** Unblocks all threads and runs all listeners. */
	  private void complete() {
		Listener currentListener = clearListeners();
		Listener reversedList = null;
		while (currentListener != null) {
		  Listener tmp = currentListener;
		  currentListener = currentListener.next;
		  tmp.next = reversedList;
		  reversedList = tmp;
		}
		// 回调之前addListener方法注册的callback，即listeners
		for (; reversedList != null; reversedList = reversedList.next) {
		  executeListener(reversedList.task, reversedList.executor);
		}
		// We call this after the listeners on the theory that done() will only be used for 'cleanup'
		// oriented tasks (e.g. clearing fields) and so can wait behind listeners which may be executing
		// more important work.  A counter argument would be that done() is trusted code and therefore
		// it would be safe to run before potentially slow or poorly behaved listeners.  Reevaluate this
		// once we have more examples of done() implementations.
		done();
	  }
	  
}