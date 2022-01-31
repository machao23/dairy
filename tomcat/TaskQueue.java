// 基础AQS类
public abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer implements java.io.Serializable {
	
	// 阻塞等待condition满足条件
	public final void await() throws InterruptedException {
		// 将当前线程包装成Node实例，追加到等待队列里
		Node node = addConditionWaiter();
		// 释放当前线程持有的lock
		// condition是与lock绑定的等待/通知组件：线程获取锁 -> 等待释放锁 -> 再次获取锁（依赖其他线程signal唤醒）才能从等待中返回
		// 释放过程中会唤醒同步队列里下一个节点 (因为同步队列里的节点阻塞等待获取锁？要看看AQS.release源码)
		int savedState = fullyRelease(node);
		int interruptMode = 0;
		// isOnSyncQueue表示当前线程被移动到了同步队列里（即另外线程调用的condition.signal）
		while (!isOnSyncQueue(node)) {
			// 当前线程进入到等待队列
			LockSupport.park(this);
		}
		// 线程自选不断尝试获取同步状态（即获取到condition引用的lock） 
		// 为什么还要引入一个同步队列，是要适配signalAll唤醒多个线程的场景吗？
		if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
			interruptMode = REINTERRUPT;
    }
	
	private Node addConditionWaiter() {
		Node t = lastWaiter;
		// 将当前线程包装成Node实例
		Node node = new Node(Thread.currentThread(), Node.CONDITION);
		// 追加到等待队列里
		if (t == null)
			firstWaiter = node;
		else
			t.nextWaiter = node;
		lastWaiter = node;
		return node;
	}
	
	final int fullyRelease(Node node) {
		int savedState = getState();
		// AQS.release方法释放AQS的同步状态并且唤醒在同步队列中头结点的后继节点引用的线程
		// 什么叫释放同步状态？
		if (release(savedState)) {
			// 成功释放同步状态
			return savedState;
		} else {
			throw new IllegalMonitorStateException();
		}
    }
}