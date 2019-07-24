public class ForkJoinPool extends AbstractExecutorService {
	
	static final ForkJoinPool common;
	static final int MAX_CAP      = 0x7fff;        // max #workers - 1
	// SQMASK是一个常量，值为126 （0x7e）
	// 实际上任何数和126进行“与”运算，其结果只可能是0或者偶数，即0 、 2 、 4 、 6 、 8。
	static final int SQMASK       = 0x007e;        // max 64 (even) slots
	
	static {
		// 静态common线程池是整个应用进程共享的
		common = AccessController.doPrivileged(new PrivilegedAction<ForkJoinPool>() {
                public ForkJoinPool run() { return makeCommonPool(); }});
	}
	
	// 创建common线程池
	private static ForkJoinPool makeCommonPool() {
        int parallelism = -1;
        ForkJoinWorkerThreadFactory factory = null;
        UncaughtExceptionHandler handler = null;
		// 解析jvm启动参数中相关配置
        try {  // ignore exceptions in accessing/parsing properties
            String pp = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.parallelism");
            String fp = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.threadFactory");
            String hp = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.exceptionHandler");
            if (pp != null)
                parallelism = Integer.parseInt(pp);
            if (fp != null)
                factory = ((ForkJoinWorkerThreadFactory)ClassLoader.
                           getSystemClassLoader().loadClass(fp).newInstance());
            if (hp != null)
                handler = ((UncaughtExceptionHandler)ClassLoader.
                           getSystemClassLoader().loadClass(hp).newInstance());
        } catch (Exception ignore) {
        }
        if (factory == null) {
            if (System.getSecurityManager() == null)
                factory = defaultForkJoinWorkerThreadFactory;
            else // use security-managed default
                factory = new InnocuousForkJoinWorkerThreadFactory();
        }
        if (parallelism < 0 && // default 1 less than #cores
            (parallelism = Runtime.getRuntime().availableProcessors() - 1) <= 0)
            parallelism = 1;
        if (parallelism > MAX_CAP)
            parallelism = MAX_CAP;
        return new ForkJoinPool(parallelism, factory, handler, LIFO_QUEUE,
                                "ForkJoinPool.commonPool-worker-");
    }
	
	// 所谓双端队列，就是说队列中的元素（ForkJoinTask任务及其子任务）可以从一端入队出队，还可以从另一端入队出队。
	static final class WorkQueue {
		// 队列状态
		volatile int qlock;        // 1: locked, < 0: terminate; else 0
		// 下一个出队元素的索引位（主要是为线程窃取准备的索引位置）
		volatile int base;         // index of next slot for poll
		// 为下一个入队元素准备的索引位
		int top;                   // index of next slot for push
		// 队列中使用数组存储元素
		ForkJoinTask<?>[] array;   // the elements (initially unallocated)
		// 队列所属的ForkJoinPool（可能为空）
		// 注意，一个ForkJoinPool中会有多个执行线程，还会有比执行线程更多的（或一样多的）队列
		final ForkJoinPool pool;   // the containing pool (may be null)
		// 这个队列所属的归并计算工作线程。注意，工作队列也可能不属于任何工作线程
		final ForkJoinWorkerThread owner; // owning thread or null if shared
		// 记录当前正在进行join等待的其它任务
		volatile ForkJoinTask<?> currentJoin;  // task being joined in awaitJoin
		// 当前正在偷取的任务
		volatile ForkJoinTask<?> currentSteal; // mainly used by helpStealer
		
		// 添加任务到工作队列
		final void push(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a; ForkJoinPool p;
            int b = base, s = top, n;
			int m = a.length - 1;     // fenced write for task visibility
			
			// sun.misc.Unsafe操作类直接基于操作系统控制层在硬件层面上进行原子操作，它是ForkJoinPool高效性能的一大保证
			// putOrderedObject方法在指定的对象a中，指定的内存偏移量的位置，赋予一个新的元素
			// 就是在任务数组a里添加任务task
			U.putOrderedObject(a, ((m & s) << ASHIFT) + ABASE, task);
			// 这里的代码意义是将workQueue对象本身中的top标示的位置 + 1，
			U.putOrderedInt(this, QTOP, s + 1);
			if ((n = s - b) <= 1) {
				if ((p = pool) != null)
					// signalWork方法的意义在于，在当前活动的工作线程过少的情况下，创建新的工作线程
					p.signalWork(p.workQueues, this);
			}
			else if (n >= m)
				// 如果array的剩余空间不够了，则进行增加
				growArray();
        }
			
		// 从当前队列里获取任务
		final ForkJoinTask<?> nextLocalTask() {
			// 如果asyncMode设定为后进先出（LIFO）
			// 则使用pop()从双端队列的前端取出任务
			// 否则就是先进先出模式（LIFO），使用poll()从双端队列的后端取出任务
			return (config & FIFO_QUEUE) == 0 ? pop() : poll();
		}
	}
	
	// 从队列中取出下一个待执行任务
	final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
		for (ForkJoinTask<?> t;;) {
			WorkQueue q; int b;
			// 该方法试图从“w”这个队列获取下一个待处理子任务
			if ((t = w.nextLocalTask()) != null)
				return t;
			// 如果没有获取到，则使用findNonEmptyStealQueue方法
			// 随机得到一个元素非空，并且可以进行任务窃取的存在于ForkJoinPool中的其它队列
			// 这个队列被记为“q”
			if ((q = findNonEmptyStealQueue()) == null)
				return null;
			// 试图从“q”这个队列base位处取出待执行任务
			if ((b = q.base) - q.top < 0 && (t = q.pollAt(b)) != null)
				return t;
		}
	}
	
	// 该方法试图将一个任务提交到一个submission queue中，随机提交
	final void externalPush(ForkJoinTask<?> task) {
		WorkQueue[] ws; WorkQueue q; int m;
		// 取得一个随机探查数，可能为0也可能为其它数
		int r = ThreadLocalRandom.getProbe();
		// 获取当前ForkJoinPool的运行状态
		int rs = runState;
		if ((ws = workQueues) != null && (m = (ws.length - 1)) >= 0 &&
			// 从名为“ws”的WorkQueue数组中，取出的元素只可能是第偶数个队列。
			// 索引位为非奇数的工作队列用于存储从外部提交到ForkJoinPool中的任务，也就是所谓的submissions queue；
			// 索引位为偶数的工作队列用于存储归并计算过程中等待处理的子任务，也就是task queue。
			(q = ws[m & r & SQMASK]) != null && r != 0 && rs > 0 &&
			U.compareAndSwapInt(q, QLOCK, 0, 1)) {

			ForkJoinTask<?>[] a; int am, n, s;
			if ((a = q.array) != null && (am = a.length - 1) > (n = (s = q.top) - q.base)) {
				int j = ((am & s) << ASHIFT) + ABASE;
				// 以下三个原子操作首先是将task放入队列
				U.putOrderedObject(a, j, task);
				// 然后将“q”这个submission queue的top标记+1
				U.putOrderedInt(q, QTOP, s + 1);
				// 最后解除这个submission queue的锁定状态
				U.putIntVolatile(q, QLOCK, 0);

				// 如果条件成立，说明这时处于active的工作线程可能还不够
				// 所以调用signalWork方法
				if (n <= 1)
					signalWork(ws, q);
				return;
			}
		}

		externalSubmit(task);
	}
}

public class ForkJoinWorkerThread extends Thread {
	final ForkJoinPool pool;                // the pool this thread works in
	
	// 这个线程所使用的子任务待执行队列，而且可以被其它工作线程偷取任务。WorkQueue**是一个双端队列。
    final ForkJoinPool.WorkQueue workQueue; // work-stealing mechanics
}