public class ThreadLocal<T> {

	public T get() {
        Thread t = Thread.currentThread();
        // 获取当前线程的 ThreadLocalMap
        // 每个线程对象中都有一个 ThreadLocalMap 属性
        ThreadLocalMap map = getMap(t);
        if (map != null) {
        	// 将 ThreadlLocal 对象作为 key 获取对应的值
            ThreadLocalMap.Entry e = map.getEntry(this);
            if (e != null) {
                T result = (T)e.value;
                return result;
            }
        }
        // 如果上面的逻辑没有取到值，则从 initialValue  方法中取值
        return setInitialValue();
    }

    private void remove(ThreadLocal<?> key) {
            Entry[] tab = table;
            int len = tab.length;
            int i = key.threadLocalHashCode & (len-1);
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                if (e.get() == key) {
                	// 调用 clear 方法，将 ThreadLocal 设置为null
                    e.clear();
                    // 调用 expungeStaleEntry 方法，该方法顺便会清理所有的 key 为 null 的 entry。
                    expungeStaleEntry(i);
                    return;
                }
            }
        }

    static class ThreadLocalMap {
    	// 这里弱引用，保证thread结束后即使没有remove这个entry设置null，由于没有强引用，也能被GC回收掉
    	// 但是再线程池场景下，还是不会被回收掉的
    	static class Entry extends WeakReference<ThreadLocal<?>> {
            /** The value associated with this ThreadLocal. */
            Object value;

            Entry(ThreadLocal<?> k, Object v) {
                super(k);
                value = v;
            }
        }
    }
}

public class Thread implements Runnable {
	// 线程结束时会调用该方法，会将线程相关的所有属性变量全部清除。包括 threadLocals
	private void exit() {
        if (group != null) {
            group.threadTerminated(this);
            group = null;
        }
        /* Aggressively null out all reference fields: see bug 4006245 */
        target = null;
        /* Speed the release of some of these resources */
        threadLocals = null;
        inheritableThreadLocals = null;
        inheritedAccessControlContext = null;
        blocker = null;
        uncaughtExceptionHandler = null;
    }
}