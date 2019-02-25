public abstract class Recycler<T> {

	// 回收
	public final boolean recycle(T o, Handle handle) {
	    DefaultHandle h = (DefaultHandle) handle;
	    h.recycle();
	    return true;
	}

	// 申请获取
	public final T get() {
	    Stack<T> stack = threadLocal.get();
	    DefaultHandle handle = stack.pop();
	    if (handle == null) {
	    	// new 一个 DefaultHandle
	        handle = stack.newHandle();
	        // 每个具体调用类，会自己实现newObject抽象方法，返回自己要的类型，
	        // 其实是为了拿到handle，再释放的时候调用handle.recycle 回收
	        handle.value = newObject(handle);
	    }
	    return (T) handle.value;
	}

	static final class DefaultHandle<T> implements Handle<T> {
		public void recycle() {
			// 回收1个对象（DefaultHandle）就是把该对象push到stack中。
		    stack.push(this);
		 }
	}

	/** 
    * Recycler有1个stack->WeakOrderQueue映射，每个stack会映射到1个WeakOrderQueue，
    * 这个WeakOrderQueue是该stack关联的其它线程WeakOrderQueue链表的head WeakOrderQueue。
    * 当其它线程回收对象到该stack时会创建1个WeakOrderQueue中并加到stack的WeakOrderQueue链表中。 
    */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // 存储其它线程回收到本线程stack的对象，当某个线程从Stack中获取不到对象时会从WeakOrderQueue中获取对象。
    // 每个线程的Stack拥有1个WeakOrderQueue链表，链表每个节点对应1个其它线程的WeakOrderQueue，
    // 其它线程回收到该Stack的对象就存储在这个WeakOrderQueue里。
    private static final class WeakOrderQueue {

    	static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
	        // We allocated a Link so reserve the space
	        /**
	         * 如果该stack的可用共享空间还能再容下1个WeakOrderQueue，那么创建1个WeakOrderQueue，否则返回null
	         */
	        return reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
	                ? new WeakOrderQueue(stack, thread) : null;
	    }
    }

    // 存储本线程回收的对象，
    // 每个用到Recycler的线程都会拥有1个Stack，在该线程中获取对象都是在该线程的Stack中pop出一个可用对象。
	static final class Stack<T> {
		void push(DefaultHandle item) {
	        Thread currentThread = Thread.currentThread();
	        if (thread == currentThread) {
	            // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
	            /**
	             * 如果该stack就是本线程的stack，那么直接把DefaultHandle放到该stack的数组里
	             */
	            pushNow(item);
	        } else {
	            // The current Thread is not the one that belongs to the Stack, we need to signal that the push
	            // happens later.
	            /**
	             * 如果该stack不是本线程的stack，那么把该DefaultHandle放到该stack的WeakOrderQueue中
	             */
	            pushLater(item, currentThread);
	        }
	    }


	    private void pushLater(DefaultHandle item, Thread thread) {
	       
	        Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
	        // 找到本stack关联的WeakOrderQueue
	        WeakOrderQueue queue = delayedRecycled.get(this);
	        if (queue == null) {
	            /**
	             * 如果delayedRecycled满了那么将1个伪造的WeakOrderQueue（DUMMY）放到delayedRecycled中，并丢弃该对象（DefaultHandle）
	             */
	            if (delayedRecycled.size() >= maxDelayedQueues) {
	                // Add a dummy queue so we know we should drop the object
	                delayedRecycled.put(this, WeakOrderQueue.DUMMY);
	                return;
	            }
	            // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
	            /**
	             * 创建1个WeakOrderQueue
	             */
	            if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
	                // drop object
	                return;
	            }
	            delayedRecycled.put(this, queue);
	        } 

	        /**
	         * 将对象放入到该stack对应的WeakOrderQueue中
	         */
	        queue.add(item);
	    }
	}
}