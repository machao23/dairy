public interface Collection<E> extends Iterable<E> {
	// Collection接口的默认stream实现
	default Stream<E> stream() {
		// spliterator是每个子类实现的
        return StreamSupport.stream(spliterator(), false);
    }
}

// 泛型的第二个参数必须是自己的子类，用来返回指定的子类实现流
public interface BaseStream<T, S extends BaseStream<T, S>> extends AutoCloseable  {
	// Spliterator可以将元素分割成多份，分别交于不于的线程去遍历
	Spliterator<T> spliterator();

	S sequential();
	S parallel();
}

public class ArrayList<E> {
	final class ArrayListSpliterator implements Spliterator<E> {
		/**
         *  对此 Spliterator 进行拆分，一分为二
         */
        @Override
        public ArrayListSpliterator trySplit() {
            final int hi = getFence(), lo = index, mid = lo + hi >>> 1;
        	// 将范围分成两半，直到无法分割为止【高低索引相邻】
        	return lo >= mid ? null : // divide range in half unless too small
            	new ArrayListSpliterator(lo, index = mid, expectedModCount);
        }
	}
}

public final class StreamSupport {
	// 初始化一个ReferencePileline的Head内部类对象
	public static <T> Stream<T> stream(Spliterator<T> spliterator, boolean parallel) {
        return new ReferencePipeline.Head<>(spliterator,
                                            StreamOpFlag.fromCharacteristics(spliterator),
                                            parallel);
    }
}

abstract class ReferencePipeline<P_IN, P_OUT>
        extends AbstractPipeline<P_IN, P_OUT, Stream<P_OUT>> implements Stream<P_OUT>  {

    @Override
    public final Stream<P_OUT> filter(Predicate<? super P_OUT> predicate) {
    	// filter是无状态的中间操作，StatelessOp的父类是ReferencePipeline
    	// 传入this，应该就是当前的stage链表状态吧？ 然后和自己这个StatelessOp实例串起来
        return new StatelessOp<P_OUT, P_OUT>(this, StreamShape.REFERENCE, StreamOpFlag.NOT_SIZED) {
            
            // 回调函数，具体的筛选filter操作在回调里定义
            @Override
            Sink<P_OUT> opWrapSink(int flags, Sink<P_OUT> sink) {
            	// onWrapSink回调方法返回的是一个sink实例，还没有开始执行逻辑
                return new Sink.ChainedReference<P_OUT, P_OUT>(sink) {
                    @Override
                    public void begin(long size) {
                        downstream.begin(-1);
                    }

                    // 熟悉的consumer函数接口
                    @Override
                    public void accept(P_OUT u) {
                        if (predicate.test(u))
                            downstream.accept(u);
                    }
                };
            }
        };
    }

    abstract static class StatelessOp<E_IN, E_OUT> extends ReferencePipeline<E_IN, E_OUT> {
    	StatelessOp(AbstractPipeline<?, E_IN, ?> upstream,StreamShape inputShape,int opFlags) {
            // 调用父类的父类 AbstractPipeline 构造方法
            super(upstream, opFlags);
        }
    }
}

// ReferencePipeline的父类，也是StatelessOp的父类
abstract class AbstractPipeline<E_IN, E_OUT, S extends BaseStream<E_OUT, S>>
        extends PipelineHelper<E_OUT> implements BaseStream<E_OUT, S> {

    // 将前后的stage联系起来，生成一个stage链表
    AbstractPipeline(AbstractPipeline<?, E_IN, ?> previousStage, int opFlags) {
        previousStage.nextStage = this; // 把自己加入到stage链表里？

        this.previousStage = previousStage; // 保存当前链表的状态
        this.sourceOrOpFlags = opFlags & StreamOpFlag.OP_MASK;
        this.combinedFlags = StreamOpFlag.combineOpFlags(opFlags, previousStage.combinedFlags);
        this.sourceStage = previousStage.sourceStage;
        if (opIsStateful())
            sourceStage.sourceAnyStateful = true;
        this.depth = previousStage.depth + 1;
    }

    @Override
    final <P_IN, S extends Sink<E_OUT>> S wrapAndCopyInto(S sink, Spliterator<P_IN> spliterator) {
    	// 执行通过回调生成的sink链表
        copyInto(wrapSink(Objects.requireNonNull(sink)), spliterator);
        return sink;
    }

    // 执行整个链表上每个stage的回调函数onWrapSink，生成一个sink链表，每个sink封装了一个操作的具体实现
    @Override
    final <P_IN> Sink<P_IN> wrapSink(Sink<E_OUT> sink) {
        for ( @SuppressWarnings("rawtypes") AbstractPipeline p=AbstractPipeline.this; p.depth > 0; p=p.previousStage) {
            sink = p.opWrapSink(p.previousStage.combinedFlags, sink);
        }
        return (Sink<P_IN>) sink;
    }

    // 通过spliterator迭代集合，执行sink链表的具体操作
    @Override
    final <P_IN> void copyInto(Sink<P_IN> wrappedSink, Spliterator<P_IN> spliterator) {
        if (!StreamOpFlag.SHORT_CIRCUIT.isKnown(getStreamAndOpFlags())) {
            wrappedSink.begin(spliterator.getExactSizeIfKnown());
            spliterator.forEachRemaining(wrappedSink);
            wrappedSink.end();
        }
        else {
            copyIntoWithCancel(wrappedSink, spliterator);
        }
    }

    // 终止操作符会调用该方法触发整个流
    // 对于设置了并发标志的操作流，会使用Fork/Join来并发执行操作任务，而对于没有打开并发标志的操作流，则串行执行操作。
    final <R> R evaluate(TerminalOp<E_OUT, R> terminalOp) {
        return isParallel()
               ? terminalOp.evaluateParallel(this, sourceSpliterator(terminalOp.getOpFlags()))
               : terminalOp.evaluateSequential(this, sourceSpliterator(terminalOp.getOpFlags()));
    }
}

// 终止操作符forEach
final class ForEachOps {
	@Override
    public <S> Void evaluateParallel(PipelineHelper<T> helper, Spliterator<S> spliterator) {
        new ForEachTask<>(helper, spliterator, helper.wrapSink(this)).invoke();
    }

    // 并行fork/join的任务
    static final class ForEachTask<S, T> extends CountedCompleter<Void> {
    	// 实现fork/join的compute方法
    	public void compute() {
            Spliterator<S> rightSplit = spliterator, leftSplit;
            long sizeEstimate = rightSplit.estimateSize(), sizeThreshold;
            if ((sizeThreshold = targetSize) == 0L)
                targetSize = sizeThreshold = AbstractTask.suggestTargetSize(sizeEstimate);
            boolean isShortCircuit = StreamOpFlag.SHORT_CIRCUIT.isKnown(helper.getStreamAndOpFlags());
            boolean forkRight = false;
            Sink<S> taskSink = sink;
            ForEachTask<S, T> task = this;
            while (!isShortCircuit || !taskSink.cancellationRequested()) {
                if (sizeEstimate <= sizeThreshold ||
                    (leftSplit = rightSplit.trySplit()) == null) {
                    task.helper.copyInto(taskSink, rightSplit);
                    break;
                }
                ForEachTask<S, T> leftTask = new ForEachTask<>(task, leftSplit);
                task.addToPendingCount(1);
                ForEachTask<S, T> taskToFork;
                if (forkRight) {
                    forkRight = false;
                    rightSplit = leftSplit;
                    taskToFork = task;
                    task = leftTask;
                }
                else {
                    forkRight = true;
                    taskToFork = leftTask;
                }
                taskToFork.fork();
                sizeEstimate = rightSplit.estimateSize();
            }
            task.spliterator = null;
            task.propagateCompletion();
        }
    }
}