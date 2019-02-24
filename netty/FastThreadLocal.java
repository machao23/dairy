public class FastThreadLocal<V> {
	private final int index;

	// 构造方法
    public FastThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    // 赋值
    public final void set(V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            if (setKnownNotUnset(threadLocalMap, value)) {
                registerCleaner(threadLocalMap);
            }
        } else {
            remove(); // 如果赋值时UNSET，就删除
        }
    }
}

public final class InternalThreadLocalMap extends UnpaddedInternalThreadLocalMap {

	static final AtomicInteger nextIndex = new AtomicInteger();

	public static int nextVariableIndex() {
        int index = nextIndex.getAndIncrement();
        return index;
    }

    // 静态方法，获取InternalThreadLocalMap实例
    public static InternalThreadLocalMap get() {
        Thread thread = Thread.currentThread();
        if (thread instanceof FastThreadLocalThread) {
        	// fastGet逻辑很简单，获取当前线程FastThreadLocalThread 的 InternalThreadLocalMap，如果没有，就创建一个
            return fastGet((FastThreadLocalThread) thread);
        } else {
        	// 为了提高性能，Netty 还是避免使用了JDK 的 threadLocalMap，
        	// 他的方式是曲线救国：在JDK 的 threadLocal 中设置 Netty 的 InternalThreadLocalMap ，
            return slowGet();
        }
    }

    public static final Object UNSET = new Object();

    // 构造方法
    private InternalThreadLocalMap() {
    	// 调用的父类 UnpaddedInternalThreadLocalMap 的构造方法，并传入了一个数组，
    	// 而这个数组默认大小是 32，里面填充32 个空对象的引用。
        super(newIndexedVariableTable());
    }

    private static Object[] newIndexedVariableTable() {
    	// 这个 Map 内部维护的是一个数组，JDK维护的是一个使用线性探测法的 Map，
    	// 他们的读取速度相差很大，特别是当数据量很大的时候，Netty 的数据结构速度依然不变，而 JDK 由于使用线性探测法，速度会相应的下降。
        Object[] array = new Object[32];
        Arrays.fill(array, UNSET);
        return array;
    }

}

// InternalThreadLocalMap的父类
class UnpaddedInternalThreadLocalMap {

    UnpaddedInternalThreadLocalMap(Object[] indexedVariables) {
        this.indexedVariables = indexedVariables;
    }	
}