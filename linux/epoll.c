
/*
 * This structure is stored inside the "private_data" member of the file
 * structure and represents the main data structure for the eventpoll
 * interface.
 */
struct eventpoll {

    /* Wait queue used by sys_epoll_wait() */
    //这个队列里存放的是执行epoll_wait从而等待的进程队列
    wait_queue_head_t wq;

    /* Wait queue used by file->poll() */
    //这个队列里存放的是该eventloop作为poll对象的一个实例，加入到等待的队列
    //这是因为eventpoll本身也是一个file, 所以也会有poll操作
    wait_queue_head_t poll_wait;

    /* List of ready file descriptors */
    //这里存放的是事件就绪的fd列表，链表的每个元素是下面的epitem
    struct list_head rdllist;

    /* RB tree root used to store monitored fd structs */
    //这是用来快速查找fd的红黑树
	//每当调用 epoll_ctl 增加一个 fd 时，内核就会为我们创建出一个 epitem 实例，
	//并且把这个实例作为红黑树的一个子节点，增加到 eventpoll 结构体中的红黑树中，对应的字段是 rbr。
	//之后查找每一个 fd 上是否有事件发生都是通过红黑树上的 epitem 来操作。
    struct rb_root_cached rbr;

    //这是eventloop对应的匿名文件，充分体现了Linux下一切皆文件的思想
    struct file *file;
};


struct epitem {
    union {
        /* RB tree node links this structure to the eventpoll RB tree */
        struct rb_node rbn;
        /* Used to free the struct epitem */
        struct rcu_head rcu;
    };

    /* List header used to link this structure to the eventpoll ready list */
    //将这个epitem连接到eventpoll 里面的rdllist的list指针
    struct list_head rdllink;

    /*
     * Works together "struct eventpoll"->ovflist in keeping the
     * single linked chain of items.
     */
    struct epitem *next;

    /* The file descriptor information this item refers to */
    //epoll监听的fd
    struct epoll_filefd ffd;

    /* Number of active wait queue attached to poll operations */
    //一个文件可以被多个epoll实例所监听，这里就记录了当前文件被监听的次数
    int nwait;

    /* The "container" of this item */
    //当前epollitem所属的eventpoll
    struct eventpoll *ep;

    /* List header used to link this item to the "struct file" items list */
    struct list_head fllink;

    /* wakeup_source used when EPOLLWAKEUP is set */
    struct wakeup_source __rcu *ws;

    /* The structure that describe the interested events and the source fd */
    struct epoll_event event;
};

// epoll_create用于创建一个epoll的句柄，其在内核的系统实现如下
SYSCALL_DEFINE1(epoll_create, int, size)
{
	// 传入的size参数，仅仅是用来判断是否小于等于0，之后再也没有其他用处。
    if (size <= 0)
        return -EINVAL;

    return sys_epoll_create1(0);
}

// 真正的工作还是放在sys_epoll_create1函数中
// 在内核中分配一个eventpoll结构和代表epoll文件的file结构，并且将这两个结构关联在一块
//应用程序操作epoll时，需要传入一个epoll文件描述符fd，内核根据这个fd，找到epoll的file结构，
// 然后通过file，获取之前epoll_create申请eventpoll结构变量，epoll相关的重要信息都存储在这个结构里面
SYSCALL_DEFINE1(epoll_create1, int, flags)
{
    int error, fd;
    struct eventpoll *ep = NULL;
    struct file *file;

    /*
     * Create the internal data structure ("struct eventpoll").
	 * 申请一个eventpoll结构，并且初始化该结构的成员,赋值给入参ep指针
     */
    error = ep_alloc(&ep);

    /*
     * Creates all the items needed to setup an eventpoll file. That is,
     * a file structure and a free file descriptor.
	 * 在本进程中申请一个未使用的fd文件描述符
     */
    fd = get_unused_fd_flags(O_RDWR | (flags & O_CLOEXEC));

	// 调用anon_inode_getfile，创建一个file结构
    file = anon_inode_getfile("[eventpoll]", &eventpoll_fops, ep, O_RDWR | (flags & O_CLOEXEC));
    ep->file = file;
	// 将fd与file交给关联在一起，之后，内核可以通过应用传入的fd参数访问file结构
    fd_install(fd, file);
    return fd;
}

// 在本进程中申请一个未使用的fd文件描述符
int get_unused_fd_flags(unsigned flags)
{
	// linux内核中，current是个宏，返回的是一个task_struct结构（我们称之为进程描述符）的变量，表示的是当前进程
	// 进程打开的文件资源保存在进程描述符的files成员里面，所以current->files返回的当前进程打开的文件资源
    // rlimit(RLIMIT_NOFILE) 函数获取的是当前进程可以打开的最大文件描述符数，这个值可以设置，默认是1024。
	// __alloc_fd的工作是为进程在[start,end)之间(备注：这里start为0， end为进程可以打开的最大文件描述符数)分配一个可用的文件描述符
    return __alloc_fd(current->files, 0, rlimit(RLIMIT_NOFILE), flags);
}

struct file *anon_inode_getfile(const char *name,
                const struct file_operations *fops,
                void *priv, int flags)
{
    struct qstr this;
    struct path path;
    struct file *file;

    /*
     * Link the inode to a directory entry by creating a unique name
     * using the inode sequence number.
	 * 首先会alloc一个file结构和一个dentry结构，然后将该file结构与一个匿名inode节点anon_inode_inode挂钩在一起
     */
    file = ERR_PTR(-ENOMEM);
    this.name = name;
    this.len = strlen(name);
    this.hash = 0;
	// dentry结构（称之为“目录项”）记录着文件的各种属性，比如文件名、访问权限等，每个文件都只有一个dentry结构
    path.dentry = d_alloc_pseudo(anon_inode_mnt->mnt_sb, &this);

    path.mnt = mntget(anon_inode_mnt);
    /*
     * We know the anon_inode inode count is always greater than zero,
     * so ihold() is safe.
     */
    ihold(anon_inode_inode);

    d_instantiate(path.dentry, anon_inode_inode);

    file = alloc_file(&path, OPEN_FMODE(flags), fops);
    file->f_mapping = anon_inode_inode->i_mapping;

    file->f_flags = flags & (O_ACCMODE | O_NONBLOCK);
    file->private_data = priv;

    return file;
}

---------------------------------------------------------------------------------
// epoll_ctl接口的作用是添加/修改/删除文件的监听事件
// epoll_ctl 函数根据监听的事件，为目标文件申请一个监听项，并将该监听项挂人到eventpoll结构的红黑树里面。
// op是对epoll操作的动作（添加/修改/删除事件）
SYSCALL_DEFINE4(epoll_ctl, int, epfd, int, op, int, fd,
        struct epoll_event __user *, event)
{
    int error;
    int full_check = 0;
    struct fd f, tf;
    struct eventpoll *ep;
    struct epitem *epi;
    struct epoll_event epds;
    struct eventpoll *tep = NULL;

    error = -EFAULT;
	// ep_op_has_event(op)判断是否不是删除操作
    if (ep_op_has_event(op) &&
		// 不是删除操作需要调用copy_from_user函数将用户空间传过来的event事件拷贝到内核的epds变量中。
		// 因为，只有删除操作，内核不需要使用进程传入的event事件。
        copy_from_user(&epds, event, sizeof(struct epoll_event)))
        goto error_return;

    error = -EBADF;
	// 获取epoll文件
    f = fdget(epfd);

    /* Get the "struct file *" for the target file */
	// 获取被监听的目标文件
    tf = fdget(fd);

    /*
     * At this point it is safe to assume that the "private_data" contains
     * our own data structure.
     */
    ep = f.file->private_data;
	
    /*
     * Try to lookup the file inside our RB tree, Since we grabbed "mtx"
     * above, we can be sure to be able to use the item looked up by
     * ep_find() till we release the mutex.
     */
	// 调用ep_find函数从ep的红黑树里面查找目标文件表示的监听项
    epi = ep_find(ep, tf.file, fd);

    error = -EINVAL;
    switch (op) {
    case EPOLL_CTL_ADD:
		// 首先要保证当前ep里面还没有对该目标文件进行监听
        if (!epi) {
            epds.events |= POLLERR | POLLHUP;
			// 将对目标文件的监听事件插入到ep维护的红黑树里面
            error = ep_insert(ep, &epds, tf.file, fd, full_check);
        } else
            error = -EEXIST;
        if (full_check)
            clear_tfile_check_list();
        break;
    case EPOLL_CTL_DEL:
        if (epi)
            error = ep_remove(ep, epi);
        else
            error = -ENOENT;
        break;
    case EPOLL_CTL_MOD:
        if (epi) {
            if (!(epi->event.events & EPOLLEXCLUSIVE)) {
                epds.events |= POLLERR | POLLHUP;
                error = ep_modify(ep, epi, &epds);
            }
        } else
            error = -ENOENT;
        break;
    }
    if (tep != NULL)
        mutex_unlock(&tep->mtx);
    mutex_unlock(&ep->mtx);

    return error;
}

static int ep_insert(struct eventpoll *ep, struct epoll_event *event,
             struct file *tfile, int fd, int full_check)
{
    int error, revents, pwake = 0;
    unsigned long flags;
    long user_watches;
    struct epitem *epi;
    struct ep_pqueue epq;

    // 首先调用kmem_cache_alloc函数，从slab分配器里面分配一个epitem结构监听项，
    if (!(epi = kmem_cache_alloc(epi_cache, GFP_KERNEL)))
        return -ENOMEM;
	
	// 然后对该结构进行初始化
    /* Item initialization follow here ... */
    INIT_LIST_HEAD(&epi->rdllink);
    INIT_LIST_HEAD(&epi->fllink);
    INIT_LIST_HEAD(&epi->pwqlist);
    epi->ep = ep;
    ep_set_ffd(&epi->ffd, tfile, fd);
    epi->event = *event;
    epi->nwait = 0;
    epi->next = EP_UNACTIVE_PTR;
    if (epi->event.events & EPOLLWAKEUP) {
        error = ep_create_wakeup_source(epi);
        if (error)
            goto error_create_wakeup_source;
    } else {
        RCU_INIT_POINTER(epi->ws, NULL);
    }

    /* Initialize the poll table using the queue callback */
	// 对目标文件的监听项注册一个ep_ptable_queue_proc回调函数，
	// ep_ptable_queue_proc回调函数将进程添加到目标文件的wakeup链表里面，并且注册ep_poll_callbak回调，
	// 当目标文件产生事件时，ep_poll_callbak回调就去唤醒等待队列里面的进程。
    epq.epi = epi;
    init_poll_funcptr(&epq.pt, ep_ptable_queue_proc);

    /*
     * Attach the item to the poll hooks and get current event bits.
     * We can safely use the file* here because its usage count has
     * been increased by the caller of this function. Note that after
     * this operation completes, the poll callback can start hitting
     * the new item.
     */
	// 调用目标文件的poll函数
    revents = ep_item_poll(epi, &epq.pt);

    /* Add the current item to the list of active epoll hook for this file */
	// 将当前监听项添加到目标文件的f_ep_links链表里面，该链表是目标文件的epoll钩子链表，
	// 所有对该目标文件进行监听的监听项都会加入到该链表里面。
    spin_lock(&tfile->f_lock);
    list_add_tail_rcu(&epi->fllink, &tfile->f_ep_links);
    spin_unlock(&tfile->f_lock);

    /*
     * Add the current item to the RB tree. All RB tree operations are
     * protected by "mtx", and ep_insert() is called with "mtx" held.
     */
	// 调用ep_rbtree_insert，将epi监听项添加到ep维护的红黑树里面
    ep_rbtree_insert(ep, epi);

    /* If the file is already "ready" we drop it inside the ready list */
	// 调用ep_item_poll去获取目标文件产生的事件位，如果有监听的事件产生
	// 并且目标文件相关的监听项没有链接到ep的准备链表rdlist里面的话
    if ((revents & event->events) && !ep_is_linked(&epi->rdllink)) {
		// 将该监听项添加到ep的rdlist准备链表里面
		// rdlist链接的是该epoll描述符监听的所有已经就绪的目标文件的监听项
        list_add_tail(&epi->rdllink, &ep->rdllist);
        ep_pm_stay_awake(epi);

        /* Notify waiting tasks that events are available */
		// 如果有任务在等待产生事件时，就调用wake_up_locked函数唤醒所有正在等待的任务，处理相应的事件。
		// 当进程调用epoll_wait时，该进程就出现在ep的wq等待队列里面。
        if (waitqueue_active(&ep->wq))
            wake_up_locked(&ep->wq);
        if (waitqueue_active(&ep->poll_wait))
            pwake++;
    }

    spin_unlock_irqrestore(&ep->lock, flags);

    atomic_long_inc(&ep->user->epoll_watches);

    /* We have to call this outside the lock */
    if (pwake)
        ep_poll_safewake(&ep->poll_wait);

    return 0;
}

// sys_epoll_ctl -> ep_insert -> ep_item_poll:
// 这个函数针对不同的目标文件而指向不同的函数，
// 如果目标文件为套接字的话，这个poll就指向sock_poll，而如果目标文件为tcp套接字来说，这个poll就是tcp_poll函数。
// 虽然poll指向的函数可能会不同，但是其作用都是一样的，就是获取目标文件当前产生的事件位，
// 并且将监听项绑定到目标文件的poll钩子里面（最重要的是注册ep_ptable_queue_proc这个poll callback回调函数）
// 这步操作完成后，以后目标文件产生事件就会调用ep_ptable_queue_proc回调函数。
static inline unsigned int ep_item_poll(struct epitem *epi, poll_table *pt)
{
    pt->_key = epi->event.events;

    return epi->ffd.file->f_op->poll(epi->ffd.file, pt) & epi->event.events;
}

-----------------------------------------------------------
SYSCALL_DEFINE4(epoll_wait, int, epfd, struct epoll_event __user *, events,
        int, maxevents, int, timeout)
{
    int error;
    struct fd f;
    struct eventpoll *ep;

    /*
     * At this point it is safe to assume that the "private_data" contains
     * our own data structure.
     */
    ep = f.file->private_data;

    /* Time to fish for events ... */
	// 参数全部检查合格后，接下来就调用ep_poll函数进行真正的处理
    error = ep_poll(ep, events, maxevents, timeout);
}

// sys_epoll_wait -> ep_poll
static int ep_poll(struct eventpoll *ep, struct epoll_event __user *events,
           int maxevents, long timeout)
{
    int res = 0, eavail, timed_out = 0;
    unsigned long flags;
    u64 slack = 0;
    wait_queue_t wait;
    ktime_t expires, *to = NULL;
	
	// timeout大于0，说明等待timeout时间后超时
    if (timeout > 0) {
        struct timespec64 end_time = ep_set_mstimeout(timeout);

        slack = select_estimate_accuracy(&end_time);
        to = &expires;
        *to = timespec64_to_ktime(end_time);
    } else if (timeout == 0) {
        /*
         * Avoid the unnecessary trip to the wait queue loop, if the
         * caller specified a non blocking operation.
		 * 如果timeout等于0，函数不阻塞，直接返回
         */
        timed_out = 1;
        spin_lock_irqsave(&ep->lock, flags);
        goto check_events;
    }
	// else 小于0的情况，是永久阻塞，直到有事件产生才返回。

fetch_events:
	// 当没有事件产生时
    if (!ep_events_available(ep))
        ep_busy_loop(ep, timed_out);

    spin_lock_irqsave(&ep->lock, flags);

    if (!ep_events_available(ep)) {

        /*
         * We don't have any available event to return to the caller.
         * We need to sleep here, and we will be wake up by
         * ep_poll_callback() when events will become available.
         */
        init_waitqueue_entry(&wait, current);
		// 将当前进程加入到ep->wq等待队列里面
        __add_wait_queue_exclusive(&ep->wq, &wait);

        for (;;) {
            /*
             * We don't want to sleep if the ep_poll_callback() sends us
             * a wakeup in between. That's why we set the task state
             * to TASK_INTERRUPTIBLE before doing the checks.
             */
			// 将当前进程设置为可中断的睡眠状态，然后当前进程就让出cpu，进入睡眠，
			// 直到有其他进程调用wake_up或者有中断信号进来唤醒本进程，它才会去执行接下来的代码。
            set_current_state(TASK_INTERRUPTIBLE);
			
			// 如果进程被唤醒后，首先检查是否有事件产生，或者是否出现超时还是被其他信号唤醒的。
            if (ep_events_available(ep) || timed_out)
                break;
        }
		// 如果出现这些情况，就跳出循环，将当前进程从ep->wp的等待队列里面移除，
        __remove_wait_queue(&ep->wq, &wait);
		// 并且将当前进程设置为TASK_RUNNING就绪状态。
        __set_current_state(TASK_RUNNING);
    }
check_events:
    /*
     * Try to transfer events to user space. In case we get 0 events and
     * there's still timeout left over, we go trying again in search of
     * more luck.
     */
	// 如果真的有事件产生，就调用ep_send_events函数，将events事件转移到用户空间里面。
    if (!res && eavail &&
        !(res = ep_send_events(ep, events, maxevents)) && !timed_out)
        goto fetch_events;

    return res;
}

// sys_epoll_wait -> ep_poll -> ep_send_events -> ep_scan_ready_list:
// ep_send_events没有什么工作，真正的工作是在ep_scan_ready_list函数里面：
static int ep_scan_ready_list(struct eventpoll *ep,
				  // 第二个入参是回调函数，ep_send_events传入的是 ep_send_events_proc函数
                  int (*sproc)(struct eventpoll *,
                       struct list_head *, void *),
                  void *priv, int depth, bool ep_locked)
{
    int error, pwake = 0;
    unsigned long flags;
    struct epitem *epi, *nepi;
    LIST_HEAD(txlist);

    /*
     * We need to lock this because we could be hit by
     * eventpoll_release_file() and epoll_ctl().
     */

    if (!ep_locked)
        mutex_lock_nested(&ep->mtx, depth);

    /*
     * Steal the ready list, and re-init the original one to the
     * empty list. Also, set ep->ovflist to NULL so that events
     * happening while looping w/out locks, are not lost. We cannot
     * have the poll callback to queue directly on ep->rdllist,
     * because we want the "sproc" callback to be able to do it
     * in a lockless way.
     */
    spin_lock_irqsave(&ep->lock, flags);
	// ep_scan_ready_list首先将ep就绪链表里面的数据链接到一个全局的txlist里面，
    list_splice_init(&ep->rdllist, &txlist);
	// 然后清空ep的就绪链表，同时还将ep的ovflist链表设置为NULL，
    ep->ovflist = NULL;
    spin_unlock_irqrestore(&ep->lock, flags);

    /*
     * Now call the callback function.
	 * 调用sproc回调函数(这里将调用ep_send_events_proc函数)将事件数据从内核拷贝到用户空间。
     */
    error = (*sproc)(ep, &txlist, priv);

    spin_lock_irqsave(&ep->lock, flags);
    /*
     * During the time we spent inside the "sproc" callback, some
     * other events might have been queued by the poll callback.
     * We re-insert them inside the main ready-list here.
     */
	// ovflist是用单链表，是一个接受就绪事件的备份链表，当执行回调函数将内核进程将事件从内核拷贝到用户空间时，
	// 这段时间目标文件可能会产生新的事件，在回调结束后，需要重新将ovlist链表里面的事件添加到rdllist就绪事件链表里面。
    for (nepi = ep->ovflist; (epi = nepi) != NULL;
         nepi = epi->next, epi->next = EP_UNACTIVE_PTR) {
        /*
         * We need to check if the item is already in the list.
         * During the "sproc" callback execution time, items are
         * queued into ->ovflist but the "txlist" might already
         * contain them, and the list_splice() below takes care of them.
         */
        if (!ep_is_linked(&epi->rdllink)) {
            list_add_tail(&epi->rdllink, &ep->rdllist);
            ep_pm_stay_awake(epi);
        }
    }
    /*
     * We need to set back ep->ovflist to EP_UNACTIVE_PTR, so that after
     * releasing the lock, events will be queued in the normal way inside
     * ep->rdllist.
     */
    ep->ovflist = EP_UNACTIVE_PTR;

    /*
     * Quickly re-inject items left on "txlist".
     */
    list_splice(&txlist, &ep->rdllist);
    __pm_relax(ep->ws);

    if (!list_empty(&ep->rdllist)) {
        /*
         * Wake up (if active) both the eventpoll wait list and
         * the ->poll() wait list (delayed after we release the lock).
         */
        if (waitqueue_active(&ep->wq))
            wake_up_locked(&ep->wq);
        if (waitqueue_active(&ep->poll_wait))
            pwake++;
    }
    spin_unlock_irqrestore(&ep->lock, flags);

    if (!ep_locked)
        mutex_unlock(&ep->mtx);

    /* We have to call this outside the lock */
    if (pwake)
        ep_poll_safewake(&ep->poll_wait);

    return error;
}

// sys_epoll_wait -> ep_poll -> ep_send_events -> ep_scan_ready_list -> ep_send_events_proc:
static int ep_send_events_proc(struct eventpoll *ep, struct list_head *head,
                   void *priv)
{
    struct ep_send_events_data *esed = priv;
    int eventcnt;
    unsigned int revents;
    struct epitem *epi;
    struct epoll_event __user *uevent;
    struct wakeup_source *ws;
    poll_table pt;

    init_poll_funcptr(&pt, NULL);

    /*
     * We can loop without lock because we are passed a task private list.
     * Items cannot vanish during the loop because ep_scan_ready_list() is
     * holding "mtx" during this call.
     */
	// 循环获取监听项的事件数据，
    for (eventcnt = 0, uevent = esed->events;
         !list_empty(head) && eventcnt < esed->maxevents;) {
        epi = list_first_entry(head, struct epitem, rdllink);

        /*
         * Activate ep->ws before deactivating epi->ws to prevent
         * triggering auto-suspend here (in case we reactive epi->ws
         * below).
         *
         * This could be rearranged to delay the deactivation of epi->ws
         * instead, but then epi->ws would temporarily be out of sync
         * with ep_is_linked().
         */
        ws = ep_wakeup_source(epi);
        if (ws) {
            if (ws->active)
                __pm_stay_awake(ep->ws);
            __pm_relax(ws);
        }

        list_del_init(&epi->rdllink);
		// 对每个监听项，调用ep_item_poll获取监听到的目标文件的事件，
        revents = ep_item_poll(epi, &pt);

        /*
         * If the event mask intersect the caller-requested one,
         * deliver the event to userspace. Again, ep_scan_ready_list()
         * is holding "mtx", so no operations coming from userspace
         * can change the item.
         */
		// 如果获取到事件，就调用__put_user函数将数据拷贝到用户空间。
        if (revents) {
            if (__put_user(revents, &uevent->events) ||
                __put_user(epi->event.data, &uevent->data)) {
                list_add(&epi->rdllink, head);
                ep_pm_stay_awake(epi);
                return eventcnt ? eventcnt : -EFAULT;
            }
            eventcnt++;
            uevent++;
            if (epi->event.events & EPOLLONESHOT)
                epi->event.events &= EP_PRIVATE_BITS;
            else if (!(epi->event.events & EPOLLET)) {
                /*
                 * If this file has been added with Level
                 * Trigger mode, we need to insert back inside
                 * the ready list, so that the next call to
                 * epoll_wait() will check again the events
                 * availability. At this point, no one can insert
                 * into ep->rdllist besides us. The epoll_ctl()
                 * callers are locked out by
                 * ep_scan_ready_list() holding "mtx" and the
                 * poll callback will queue them in ep->ovflist.
                 */
                list_add_tail(&epi->rdllink, &ep->rdllist);
                ep_pm_stay_awake(epi);
            }
        }
    }

    return eventcnt;
}