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

    /* The maximum number of event must be greater than zero */
    if (maxevents <= 0 || maxevents > EP_MAX_EVENTS)
        return -EINVAL;

    /* Verify that the area passed by the user is writeable */
    if (!access_ok(VERIFY_WRITE, events, maxevents * sizeof(struct epoll_event)))
        return -EFAULT;

    /* Get the "struct file *" for the eventpoll file */
    f = fdget(epfd);
    if (!f.file)
        return -EBADF;

    /*
     * We have to check that the file structure underneath the fd
     * the user passed to us _is_ an eventpoll file.
     */
    error = -EINVAL;
    if (!is_file_epoll(f.file))
        goto error_fput;

    /*
     * At this point it is safe to assume that the "private_data" contains
     * our own data structure.
     */
    ep = f.file->private_data;

    /* Time to fish for events ... */
    error = ep_poll(ep, events, maxevents, timeout);

error_fput:
    fdput(f);
    return error;
}