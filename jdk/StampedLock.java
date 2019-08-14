public class StampedLock implements java.io.Serializable {
	
	 /** 线程入队列前自旋次数 */
    private static final int SPINS = (NCPU > 1) ? 1 << 6 : 0;

    /** 队列头结点自旋获取锁最大失败次数后再次进入队列 */
    private static final int HEAD_SPINS = (NCPU > 1) ? 1 << 10 : 0;

    /** 重新阻塞前的最大重试次数 */
    private static final int MAX_HEAD_SPINS = (NCPU > 1) ? 1 << 16 : 0;

    /** The period for yielding when waiting for overflow spinlock */
    private static final int OVERFLOW_YIELD_RATE = 7; // must be power 2 - 1

    /** 溢出之前用于阅读器计数的位数 */
    private static final int LG_READERS = 7;

    // 锁定状态和stamp操作的值
    private static final long RUNIT = 1L;
	// 1000 0000（即-128）
    private static final long WBIT  = 1L << LG_READERS;
    private static final long RBITS = WBIT - 1L;
    private static final long RFULL = RBITS - 1L;
    private static final long ABITS = RBITS | WBIT;   //前8位都为1
    private static final long SBITS = ~RBITS; // 1 1000 0000

    //锁state初始值，第9位为1，避免算术时和0冲突
    private static final long ORIGIN = WBIT << 1;

    // 来自取消获取方法的特殊值，因此调用者可以抛出IE
    private static final long INTERRUPTED = 1L;

    // WNode节点的status值
    private static final int WAITING   = -1;
    private static final int CANCELLED =  1;

    // WNode节点的读写模式
    private static final int RMODE = 0;
    private static final int WMODE = 1;

    /** Wait nodes */
    static final class WNode {
        volatile WNode prev;
        volatile WNode next;
        volatile WNode cowait;    // 读模式使用该节点形成栈
        volatile Thread thread;   // 每一个WNode标识一个等待线程
        volatile int status;      // 锁的状态 0, WAITING, or CANCELLED
        final int mode;           // RMODE or WMODE
        WNode(int m, WNode p) { mode = m; prev = p; }
    }

    /** CLH队头节点 */
    private transient volatile WNode whead;
    /** CLH队尾节点 */
    private transient volatile WNode wtail;

    // views
    transient ReadLockView readLockView;
    transient WriteLockView writeLockView;
    transient ReadWriteLockView readWriteLockView;

    /** 锁队列状态， 当处于写模式时第8位为1，读模式时前7为为1-126（附加的readerOverflow用于当读者超过126时） */
    private transient volatile long state;
    /** 将state超过 RFULL=126的值放到readerOverflow字段中 */
    private transient int readerOverflow;
}