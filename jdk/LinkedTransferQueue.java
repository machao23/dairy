public interface TransferQueue<E> extends BlockingQueue<E> {
    // 如果可能，立即将元素转移给等待的消费者。 
    // 更确切地说，如果存在消费者已经等待接收它（在 take 或 timed poll（long，TimeUnit）poll）中，则立即传送指定的元素，否则返回 false。
    boolean tryTransfer(E e);

    // 将元素转移给消费者，如果需要的话等待。 
    // 更准确地说，如果存在一个消费者已经等待接收它（在 take 或timed poll（long，TimeUnit）poll）中，则立即传送指定的元素，否则等待直到元素由消费者接收。
    void transfer(E e) throws InterruptedException;

    // 上面方法的基础上设置超时时间
    boolean tryTransfer(E e, long timeout, TimeUnit unit) throws InterruptedException;

    // 如果至少有一位消费者在等待，则返回 true
    boolean hasWaitingConsumer();

    // 返回等待消费者人数的估计值
    int getWaitingConsumerCount();
}

public class LinkedTransferQueue<E> extends AbstractQueue<E> implements TransferQueue<E>, java.io.Serializable {
		
	//队列头节点，第一次入列之前为空
	transient volatile Node head;

	//队列尾节点，第一次添加节点之前为空
	private transient volatile Node tail;

	// 累计到一定次数再清除无效node
	// head/tail 节点并不是及时更新的，在并发操作时链表内部可能存在已匹配节点，此时就需要一个阀值来决定何时清除已匹配的内部节点链，这就是sweepVotes和SWEEP_THRESHOLD的作用。
	private transient volatile int sweepVotes;

	//当一个节点是队列中的第一个waiter时，在多处理器上进行自旋的次数(随机穿插调用thread.yield)
	private static final int FRONT_SPINS   = 1 << 7;

	// 当previous节点正在处理，当前节点在阻塞之前的自旋次数，也为FRONT_SPINS的位变化充当增量，也可在自旋时作为yield的平均频率
	private static final int CHAINED_SPINS = FRONT_SPINS >>> 1;

	// sweepVotes的阀值
	// 当解除链接失败次数超过这个阀值时就会对队列进行一次“大扫除”（通过sweep()方法），解除所有已取消的节点链接。
	static final int SWEEP_THRESHOLD = 32;
	/*
	 * Possible values for "how" argument in xfer method.
	 * xfer方法类型
	 */
	private static final int NOW   = 0; // for untimed poll, tryTransfer
	private static final int ASYNC = 1; // for offer, put, add
	private static final int SYNC  = 2; // for transfer, take
	private static final int TIMED = 3; // for timed poll, tryTransfer
		
	/**
	* e 是写入的元素
	* how 执行类型，有立即返回的NOW，有异步的ASYNC，有阻塞的SYNC， 有带超时的 TIMED。
	* nanos 等待超时时长
	**/
	private E xfer(E e, boolean haveData, int how, long nanos) {

		Node s = null;                        // the node to append, if needed

		retry:
		for (;;) {                            // restart on append race
			// 从head开始向后匹配，找到一个节点模式跟本次操作的模式不同的未匹配的节点（生产或消费）进行匹配；
			for (Node h = head, p = h; p != null;) { // find & match first node
				// 节点的类型。（生产节点才是true）
				boolean isData = p.isData;
				// head 的数据（生产节点就是要写的数据，消费节点就是null）
				Object item = p.item;
				
				// 第一个判断条件，item != p 表示是有效节点
				// 第二个匹配条件 (itme != null) == isData 要么表示 p 是一个 put 操作, 要么表示 p 是一个还没匹配成功的 take 操作
				if (item != p && (item != null) == isData) { 
					// 如果当前操作和 head 操作相同，就没有匹配上，结束循环，进入下面的 if 块。
					// （匹配的前提条件是一个是生产节点另一个是消费节点才行，2个节点类型相同肯定是不能匹配的，
					// 但是跳出遍历loop是因为既然队列里第一个节点就是相同类型，没必要再往下找后面还有不同的节点）
					if (isData == haveData)   // can't match
						break;
					// 如果操作不同,匹配节点成功 CAS 修改匹配节点的 item 为给定元素 e
					if (p.casItem(item, e)) { // match
						// 更新 head（找到下一个不是自链接的节点）
						for (Node q = p; q != h;) {
							Node n = q.next;  // update by 2 unless singleton
							if (head == h && casHead(h, n == null ? q : n)) {
								h.forgetNext(); //旧head节点指向自身等待回收
								break;
							}                 // advance and retry
							if ((h = head)   == null ||
								(q = h.next) == null || !q.isMatched())
								break;        // unless slack < 2
						}
						// 匹配成功，唤醒匹配节点 p 的等待线程 waiter，返回匹配的 item。
						// （那消费方请求匹配成功，唤醒的是什么？也就是生产节点的waiter是什么？）
						LockSupport.unpark(p.waiter);
						return LinkedTransferQueue.<E>cast(item);
					}
				}
				// 找下一个
				Node n = p.next;
				p = (p != n) ? n : (h = head); // Use head if p offlist
			}
			// 如果这个操作不是立刻就返回的类型    
			if (how != NOW) {                 // No matches available
				// 且是第一次进入这里
				if (s == null)
					// 创建一个 node
					s = new Node(e, haveData);
				// 尝试将 node 追加对队列尾部，并返回他的上一个节点。
				// 生产线程看看head是不是消费节点，如果是就直接交出数据，如果不是追加队列立即返回
				// 消费线程看看head是不是生产节点，如果是就直接拿走数据，如果不是就追加到tail阻塞
				Node pred = tryAppend(s, haveData);
				// 如果返回的是 null, 表示不能追加到 tail 节点,因为 tail 节点的模式和当前模式相反.
				if (pred == null)
					// 重来
					continue retry;           // lost race vs opposite mode
				// 如果不是异步操作(即立刻返回结果)
				if (how != ASYNC)
					// 阻塞等待匹配值
					return awaitMatch(s, pred, e, (how == TIMED), nanos);
			}
			return e; // not waiting
		}
	}

}
