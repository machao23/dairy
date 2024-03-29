import java.util.Objects;
import java.util.function.Consumer;

public abstract class Mono<T> implements Publisher<T> {
    public final reactor.core.publisher.Mono<T> doOnSuccess(Consumer<? super T> onSuccess) {
        // 调用doOnSuccess会创建一个 MonoPeekTerminal 实例
        return onAssembly(new MonoPeekTerminal<>(this, onSuccess, null, null));
    }
	
	//map方法，首先判断mono本身支持融合使用MonoMapFuseable，不支持融合MonoMap
	public final <R> Mono<R> map(Function<? super T, ? extends R> mapper) {
		if (this instanceof Fuseable) {
			return onAssembly(new MonoMapFuseable<>(this, mapper));
		}
		return onAssembly(new MonoMap<>(this, mapper));
	}
}

final class MonoMapFuseable<T, R> extends InternalMonoOperator<T, R> implements Fuseable {
	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(CoreSubscriber<? super R> actual) {
		if (actual instanceof ConditionalSubscriber) {
			ConditionalSubscriber<? super R> cs = (ConditionalSubscriber<? super R>) actual;
			source.subscribe(new FluxMapFuseable.MapFuseableConditionalSubscriber<>(cs, mapper));
			return;
		}
		source.subscribe(new FluxMapFuseable.MapFuseableSubscriber<>(actual, mapper));
	}
}

static final class MapFuseableSubscriber<T, R> implements InnerOperator<T, R>, QueueSubscription<R> {

	final CoreSubscriber<? super R>        actual; //下游Subscriber
	final Function<? super T, ? extends R> mapper; //map变换，就是我们在map方法里定义的逻辑

	boolean done;  //是否处理完

	QueueSubscription<T> s;  //上游subscription

	int sourceMode; //当前Subscriber的融合模式


	//当订阅上游时，上游最终会触发下游的onSubscribe方法
	//第1步
	public void onSubscribe(Subscription s) {
		if (Operators.validate(this.s, s)) {
			//直接转成QueueSubscription，有了前面Fuseable接口，这里转化是安全的
			this.s = (QueueSubscription<T>) s;  

			//把自身传给下游，因为也实现了QueueSubscription
			actual.onSubscribe(this);

			//然后下游会调用request或requestFusion
		}
	}
	
	// 下游支持融合，下游会调用这个requestFusion方法，逐个从下往上走
	public int requestFusion(int requestedMode) {
		int m;
		//下游请求跨线程融合，
		//因为这里全部实现都是未保证线程安全的，所以不支持跨线程融合
		if ((requestedMode & Fuseable.THREAD_BARRIER) != 0) {
			return Fuseable.NONE;
		}
		else {
			//融合往上游传递。例如MonoJust只支持同步融合，那么sourceMode == SYNC
			m = s.requestFusion(requestedMode);
		}
		sourceMode = m;
		return m; //融合状态结果往下游传递
	}

	//或者下游不支持融合的话，会调用到这里的request方法
	//或者下游异步融合模式，也会调用request方法
	public void request(long n) {
		//请求拉取数据，直接往上游传，上游会发射数据到onNext方法
		s.request(n);
	}

	//第3步：当向上游调用request方法时，上游会发射数据到onNext方法
	//不支持融合模式或者异步融合都会调用到onNext方法（因为非融合不支持批量发射数据，所以只能逐个onNext发送了）
	public void onNext(T t) {
		//说明下游请求异步融合了，那么通过onNext方法告知下游数据准备好了
		if (sourceMode == ASYNC) {
			actual.onNext(null);
		}
		else { //正常发射数据
			if (done) { //已完成，return
				Operators.onNextDropped(t, actual.currentContext());
				return;
			}
			R v;
			try {
				//执行变换
				//可以看出整个流程中数据是不允许为null的
				v = Objects.requireNonNull(mapper.apply(t),
						"The mapper returned a null value.");
			}
			catch (Throwable e) {
				//hook方法执行
				Throwable e_ = Operators.onNextError(t, e, actual.currentContext(), s);

				//hook方法没能消化掉error
				if (e_ != null) {
					onError(e_); //执行下游actual#onError方法，通知处理失败
				}
				else {
					//hook方法消化掉error，并再拉取一条数据
					//很明显，MonoJust会忽略掉，因为MonoJust只有一条数据
					s.request(1);
				}
				return;
			}
			//数据往下游流动
			actual.onNext(v);
		}
	}

	//当下游支持融合时，下游会调用poll方法主动拉取上游的数据
	//第4步：异步融合收到onNext方法的通知时，才会调用poll （所以异步融合和同步相比，多了一次onNext，最后融合都会走到poll？ ）
	//同步融合时，再调用完requestFusion方法后得到上游支持同步融合时便可直接poll数据
	public R poll() {
	  for(;;) {
		  T v = s.poll(); //从上游拉取数据。
		  if (v != null) {
			  try {
				  //变换，返回。
				  return Objects.requireNonNull(mapper.apply(v));
			  }
			  catch (Throwable t) {
				  RuntimeException e_ = Operators.onNextPollError(v, t, currentContext());
				  if (e_ != null) {
					  throw e_;
				  }
				  else {
					  continue;
				  }
			  }
		  }
		  //如果是同步融合，返回null，代表数据已经发射完了，
		  //下游需要自己调用onComplete方法，而不是由上游触发

		  //如果是异步融合，代表本轮请求的数据已经处理完了
		  //继续等待onNext方法收到通知再poll数据，或者收到onComplete|onError 方法通知代表上游数据已经处理完了
		  return null;
	  }
	}

	public void onError(Throwable t) {
		if (done) { //已经处理完成了，忽略掉
			Operators.onErrorDropped(t, actual.currentContext());
			return;
		}
		done = true; //处理完的标志位设为true
		//异常往下游流动
		actual.onError(t);
	}
}

public abstract class Mono<T> implements CorePublisher<T> {
	public final <R> Mono<R> flatMap(Function<? super T, ? extends Mono<? extends R>>
			transformer) {
		return onAssembly(new MonoFlatMap<>(this, transformer));
	}
}

//很明显MonoFlatMap也是支持融合的
final class MonoFlatMap<T, R> extends InternalMonoOperator<T, R> implements Fuseable {

	public CoreSubscriber<? super T> subscribe(CoreSubscriber<? super R> actual) {
		FlatMapMain<T, R> manager = new FlatMapMain<>(actual, mapper);
		
		//先把QueueSubscription往下游传，可以request请求到FlatMapMain
		//然后source#onSubscribe传下来Subscription，已经明确需要拉取的数据量大小了，也算是一个优化点
		actual.onSubscribe(manager);

		//返回Subscriber继续while循环订阅，直到数据源头
		return manager;
	}

	//FlatMapMain既是QueueSubscription 需要处理下游请求的request，cancel方法，
	//又是Subscriber 需要处理上游传下来的onSubscribe，onNext方法等
	static final class FlatMapMain<T, R> extends Operators.MonoSubscriber<T, R> {

		final Function<? super T, ? extends Mono<? extends R>> mapper;
		final FlatMapInner<R> second; //新数据源的subscriber（下游？）
		boolean done;
		volatile Subscription s; //（上游？)

		//第1步：
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(S, this, s)) {
				//很明显flatMap没有向上传请求融合
				//所以数据会从onNext方法从上游传下来
				s.request(Long.MAX_VALUE);
			}
		}

		//第2步：上游发射数据到onNext方法，
		//调用mapper方法产生新数据源并订阅
		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t, actual.currentContext());
				return;
			}
			//因为是Mono只发送1个元素，所以onNext处理完，上游就结束了。
			//至于下游的onComplete方法会在flatMap产生新数据源中触发
			done = true;

			Mono<? extends R> m;

			try {
				//flatMap创建一个新的Publisher，这里返回的还只是Mono.create，还没到异步返回结果回调阶段
				m = Objects.requireNonNull(mapper.apply(t),
						"The mapper returned a null Mono");
			}
			catch (Throwable ex) {
				actual.onError(Operators.onOperatorError(s, ex, t,
						actual.currentContext()));
				return;
			}

			try {
				//原来下游seconde订阅flatMap逻辑定义返回的新publisher，即触发了创建回调函数future.addListener
				m.subscribe(second);
			}
			catch (Throwable e) {
				actual.onError(Operators.onOperatorError(this, e, t,
						actual.currentContext()));
			}
		}

		//--------------------------
		//下游在onSubscribe方法收到上游的QueueSubscription时，可能执行融合操作

		//第3步：下游请求融合，返回支持异步融合
		@Override
		public int requestFusion(int mode) {
			//因为新数据源何时能发射是未知的，所以是异步融合
			if ((mode & ASYNC) != 0) {
				STATE.lazySet(this, FUSED_EMPTY); //设置融合标志位
				return ASYNC;
			}
			//only async
			return NONE;
		}

		//第4步：不管是异步融合还是不支持融合时，都需要调用request方法
		public void request(long n) {
		    if (validate(n)) {
			    for (; ; ) {
			       int s = state;
  			    
			        //标记位已经被处理过，直接return
			        //处理过定义：已经请求该request方法，融合过，cancel过，
			        if ((s & ~NO_REQUEST_HAS_VALUE) != 0) {
				        return;
			        }
  			    
			        //上游已经发射值了，但未request
			        //所以直接往下游发射数据即可
			        if (s == NO_REQUEST_HAS_VALUE && STATE.compareAndSet(this, NO_REQUEST_HAS_VALUE, HAS_REQUEST_HAS_VALUE)) {
				        O v = value;
				        if (v != null) {
					        value = null;
					        Subscriber<? super O> a = actual;
					        a.onNext(v);
					        a.onComplete();
				        }
				        return;
			        }
  			    
			        //设置请求过的标志位
			        if (STATE.compareAndSet(this, NO_REQUEST_NO_VALUE, HAS_REQUEST_NO_VALUE)) {
				      return;
				    }
			    }//for end
  		    } //if end
		}

		//第5步：新数据源发射数据时，最终会调用该方法
		public final void complete(O v) {
		    for (; ; ) {
			  int state = this.state;
			  if (state == FUSED_EMPTY) {  //下游调用了requestFusion方法，进行异步融合
				  setValue(v);
				  
				  //只标志位为融合准备完成,调用下游的onNext方法通知，
				  //并调用onComplete方法通知下游数据已发射完
				  if (STATE.compareAndSet(this, FUSED_EMPTY, FUSED_READY)) {
					  Subscriber<? super O> a = actual;
					  a.onNext(v);
					  a.onComplete();
					  return;
				  }
				  //refresh state if race occurred so we test if cancelled in the next comparison
				  state = this.state;
			  }
			  
			  //下面是对未进行融合的处理    
			  //已经设置过了，执行丢弃value hook，并return
			  if ((state & ~HAS_REQUEST_NO_VALUE) != 0) {
				  discard(v);
				  return;
			  }
			  
			  //下游已执行过request，那么直接往下游继续发射数据
			  if (state == HAS_REQUEST_NO_VALUE && STATE.compareAndSet(this, HAS_REQUEST_NO_VALUE, HAS_REQUEST_HAS_VALUE)) {
				  this.value = null;
				  Subscriber<? super O> a = actual;
				  a.onNext(v);
				  a.onComplete();
				  return;
			  }
			  
			  //未执行过request，保存value并设置有值标志位
			  setValue(v);
			  if (state == NO_REQUEST_NO_VALUE && STATE.compareAndSet(this, NO_REQUEST_NO_VALUE, NO_REQUEST_HAS_VALUE)) {
				  return;
			  }
		    }
		}

		//第6步：下游请求异步融合时，收到上面complete方法（第一个if分支）中发射发出的通知。
		//注意此时下游的onNext不再是数据了，而是通知。下游调用上游的QueueSubscription#poll方法拉取数据
		public final O poll() {
			if (STATE.compareAndSet(this, FUSED_READY, FUSED_CONSUMED)) { //异步融合消费完成
				O v = value;
				value = null;
				return v;
			}
			return null;
		}

		//省略若干代码
	}

	//还有一个分支，对新数据源的订阅处理
	//即上面onNext方法内 m.subscribe(second)，这个second

	static final class FlatMapInner<R> implements InnerConsumer<R> {

		final FlatMapMain<?, R> parent;

		volatile Subscription s;

		//这个subscriber也不会做请求融合处理
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(S, this, s)) {
				//直接拉满
				s.request(Long.MAX_VALUE);
			}
		}

		//最终还是调到上面分析的FlatMapMain#complete方法
		@Override
		public void onNext(R t) {
			if (done) {
				Operators.onNextDropped(t, parent.currentContext());
				return;
			}
			done = true;
			//接连上面的第5步
			this.parent.complete(t);
		}

		//省略若干代码
	}
}


final class MonoJust<T> extends Mono<T> implements Fuseable.ScalarCallable<T>, Fuseable, SourceProducer<T>  {
	//下游调用MonoJust的subscribe时候，MonoJust会创建一个 ScalarSubscription 实例，传递给下游
	@Override
	public void subscribe(CoreSubscriber<? super T> actual) {
		actual.onSubscribe(Operators.scalarSubscription(actual, value));
	}
}

// ScalarSubscription 实现了 Fuseable.SynchronousSubscription 接口
static final class ScalarSubscription<T> implements Fuseable.SynchronousSubscription<T>, InnerProducer<T> {
	final CoreSubscriber<? super T> actual; //下游Subscriber
	final T value; // 只有一个数据的队列，所以没有额外的队列数据结构，仅一个value成员
	//0.未消费 1. 已消费， 2.已取消
	volatile int once;

	@Override
	public void cancel() {
		if (once == 0) { //这里代表数据未消费就直接丢掉，如果有hook方法就执行hook方法。
			//前面提到Context说到，会从Context取一些hook方法来执行，取不到就忽略。
			Operators.onDiscard(value, actual.currentContext());  
		}
		ONCE.lazySet(this, 2); //取消
	}

	//Queue接口方法，清除数据
	@Override
	public void clear() {
		if (once == 0) {
			Operators.onDiscard(value, actual.currentContext());
		}
		ONCE.lazySet(this, 1); //置消费位，代表已清空队列
	}

	@Override
	public boolean isEmpty() {
		return once != 0;
	}

	//拉取数据，并设置已消费。
	@Override
	@Nullable
	public T poll() {
		if (once == 0) {
			ONCE.lazySet(this, 1);
			return value;
		}
		return null;
	}

	@Override
	public void request(long n) {
		if (validate(n)) { //校验n > 0
			if (ONCE.compareAndSet(this, 0, 1)) { //竟态条件，CAS。
				Subscriber<? super T> a = actual; //下游
				a.onNext(value); //调用通知下游
				if(once != 2) { //没有取消的话，调用下游的onComplete方法
					a.onComplete(); 
				}
			}
		}
	}

	@Override
	public int requestFusion(int requestedMode) {
		if ((requestedMode & Fuseable.SYNC) != 0) { //请求包含同步融合，那么就支持同步融合。
			return Fuseable.SYNC;
		}
		return 0; //NONE,不支持融合
	}

	@Override
	public int size() {
		return isEmpty() ? 0 : 1;
	}

	// 节约内存，使用 AtomicIntegerFieldUpdater 替换 AtomicInteger
	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<ScalarSubscription> ONCE =
			AtomicIntegerFieldUpdater.newUpdater(ScalarSubscription.class, "once");
}

// doOnSuccess时候会创建这个实例
final class MonoPeekTerminal<T> extends MonoOperator<T, T> implements Fuseable {

    // 内部订阅类
    static final class MonoTerminalPeekSubscriber<T>
            implements Fuseable.ConditionalSubscriber<T>, InnerOperator<T, T>,
            Fuseable.QueueSubscription<T> {

        // parent就是外部类的实例
        final MonoPeekTerminal<T> parent;

        @Override
        public void onNext(T t) {
            if (parent.onSuccessCall != null) {
                // 如果流里声明了doOnSuccess，就会回调
                parent.onSuccessCall.accept(t);
            }
        }
    }
}

// CoreSubscriber继承自Reactive Stream中的Subscriber
public interface CoreSubscriber<T> extends Subscriber<T> {
	// 主要是提供了currentContext方法用于获取Context。 Context跟Map差不多，直接简单理解成Map都没问题。 
	// Context主要保存一些用户自定义可选的的行为。例如数据在操作符管道中处理发生异常，如果在Context保存了对异常处理的hook方法，那么就调用hook方法处理。
	// 在内部实现中，都是使用CoreSubscriber、CorePublisher，而不是直接使用Subscriber、Publisher。 例如Mono|Flux继承CorePublisher。
	default Context currentContext(){
		return Context.empty();
	}
}

// 该接口只是个标记型接口，用于描述Mono|Flux是否可融合的。
// 如果不支持融合的Mono|Flux，那么上游发射的数据都是通过onNext方法一个一个往下游传的。支持融合的话，下游可以一次拉取N个数据，一起处理,有效提升性能。 
public interface Fuseable {
	//融合请求是下游向上游请求的
    
    //不支持融合，用于下游请求上游融合时，上游不支持下游声明的融合模式
    int NONE = 0;
    
    //同步融合。下游请求上游融合时声明同步融合模式，
    //如果上游支持同步融合模式，那么返回该值代表支持同步融合，否则返回NONE。
	//同步融合的上游的数据一定是全部准备好的了，可直接拉取Queue#poll。如果poll方法返回null，则上游数据处理完了
    int SYNC = 1;
	
    //异步融合。下游请求上游融合时声明异步融合模式，
    //如果上游支持异步融合模式，那么返回该值代表支持异步融合，否则返回NONE。	//异步融合的上游数据不一定是准备好的了。当上游数据准备好，通过onNext方法通知下游数据已经准备好了，下游直接拉取Queue#poll。如果poll方法返回null，上游并不一定处理完了，可能只是暂时没数据了，
	//上游处理完了一定会通过onComplete方法通知下游(因为下游可能不知道上游是还在准备数据继续等待，还是上游全部发完了不需要等待了?)这点跟同步融合区别很大。
    int ASYNC = 2;
	
    //同步或异步融合。用于下游请求上游融合时声明ANY融合模式。
    //如果上游支持同步融合模式，那么返回SYNC。
    //如果上游支持异步融合模式，那么返回ASYNC。
    //如果上游不支持融合，则返回NONE。
    //切记：ANY只能用于下游请求上游融合的入参，不能作为返回参数。
    int ANY = 3;
	
    //代表能否支持跨线程融合。跨线程指下游要跨线程，例如下游操作符为publishOn。该参数配合SYNC|ASYNC|ANY任一使用才有用。
    int THREAD_BARRIER = 4;
	
	// 实现了Fuseable接口的Mono|Flux，执行下游的onSubscribe方法时，入参的Subscription一定是QueueSubscription类型的。因为这个入参的Subscription是上游传下来的
	// 下游调用上游的subscribe,上游会回调下游的onSubscribe，把自己的subscription传给下游，后续下游用上游的subscription请求数据流
	// QueueScription提供了操作Queue队列的方法
	interface QueueSubscription<T> extends Queue<T>, Subscription {
		
		// 对上游请求融合
		// 入参requestedMode就是SYNC|ASYNC|ANY之一或THREAD_BARRIER
		int requestFusion(int requestedMode);
	}
	
	// 支持同步融合的QueueScription
	interface SynchronousSubscription<T> extends QueueSubscription<T> {
		@Override
		default int requestFusion(int requestedMode) {
			if ((requestedMode & Fuseable.SYNC) != 0) {
				return Fuseable.SYNC;
			}
			return NONE;
		}

	}
}
