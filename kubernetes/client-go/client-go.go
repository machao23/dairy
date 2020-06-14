// tools/cache/reflector.go

func (r *Reflector) ListAndWatch(stopCh <-chan struct{}) error {
	// list 资源
	list, err := r.listerWatcher.List(options)
	// 提取 items
	items, err := meta.ExtractList(list)
	 // 更新存储(Delta FIFO)中的 items
	if err := r.syncWith(items, resourceVersion); err != nil {
	   return fmt.Errorf("%s: Unable to sync list result: %v", r.name, err)
	}
	r.setLastSyncResourceVersion(resourceVersion)
 
	// ……
 
	for {
	   select {
	   case <-stopCh:
		  return nil
	   default:
	   }
 
	   timeoutSeconds := int64(minWatchTimeout.Seconds() * (rand.Float64() + 1.0))
	   options = metav1.ListOptions{
		  ResourceVersion: resourceVersion,
		  TimeoutSeconds: &timeoutSeconds,
	   }
 
	   r.metrics.numberOfWatches.Inc()
		// 开始 watch
	   w, err := r.listerWatcher.Watch(options)
		// ……
		// w 交给 watchHandler 处理
	   if err := r.watchHandler(w, &resourceVersion, resyncerrc, stopCh); err != nil {
		  if err != errorStopRequested {
			 klog.Warningf("%s: watch of %v ended with: %v", r.name, r.expectedType, err)
		  }
		  return nil
	   }
	}
 }

 // 有变化的资源添加到 Delta FIFO 中
 func (r *Reflector) watchHandler(w watch.Interface, resourceVersion *string, errc chan error, stopCh <-chan struct{}) error {
	// ……
 loop:
	 // 这里进入一个无限循环
	for {
	   select {
	   case <-stopCh:
		  return errorStopRequested
	   case err := <-errc:
		  return err
		   // watch 返回值中的一个 channel
	   case event, ok := <-w.ResultChan():
		  // ……
		  newResourceVersion := meta.GetResourceVersion()
		   // 根据事件类型处理，有 Added Modified Deleted 3种
		   // 3 种事件分别对应 store 中的增改删操作
		  switch event.Type {
		  case watch.Added:
			 err := r.store.Add(event.Object)
			 
		  case watch.Modified:
			 err := r.store.Update(event.Object)
			 
		  case watch.Deleted:
			 err := r.store.Delete(event.Object)
			 
		  default:
			 utilruntime.HandleError(fmt.Errorf("%s: unable to understand watch event %#v", r.name, event))
		  }
		  *resourceVersion = newResourceVersion
		  r.setLastSyncResourceVersion(newResourceVersion)
		  eventCount++
	   }
	}
 
	// ……
	 
	return nil
 }


// tools/cache/controller.go
// 对外暴露这个方法
func (c *controller) Run(stopCh <-chan struct{}) {
	defer utilruntime.HandleCrash()
	go func() {
	   <-stopCh
	   c.config.Queue.Close()
	}()
	 // 内部 Reflector 创建
	r := NewReflector(
	   c.config.ListerWatcher,
	   c.config.ObjectType,
	   c.config.Queue,
	   c.config.FullResyncPeriod,
	)
	r.ShouldResync = c.config.ShouldResync
	r.clock = c.clock
 
	c.reflectorMutex.Lock()
	c.reflector = r
	c.reflectorMutex.Unlock()
 
	var wg wait.Group
	defer wg.Wait()
 
	wg.StartWithChannel(stopCh, r.Run)
	 // 循环调用 processLoop
	wait.Until(c.processLoop, time.Second, stopCh)
 }

 func (c *controller) processLoop() {
	for {
		// 主要逻辑
		// 这里的 Queue 就是 Delta FIFO，Pop 是个阻塞方法，内部实现时会逐个 pop 队列中的数据，交给 PopProcessFunc 处理。
	   obj, err := c.config.Queue.Pop(PopProcessFunc(c.config.Process))
		// 异常处理
	}
 }

 // 处理从Delta FIFO里POP的数据
 Process: func(obj interface{}) error {
	for _, d := range obj.(Deltas) {
		switch d.Type {
            // 更新、添加、同步、删除等操作
		case Sync, Added, Updated:
			if old, exists, err := clientState.Get(d.Object); err == nil && exists {
				if err := clientState.Update(d.Object); err != nil {
					return err
				}
				h.OnUpdate(old, d.Object)
			} else {
				if err := clientState.Add(d.Object); err != nil {
					return err
				}
				h.OnAdd(d.Object)
			}
		case Deleted:
			if err := clientState.Delete(d.Object); err != nil {
				return err
			}
			h.OnDelete(d.Object)
		}
	}
	return nil
},

// tools/cache/store.go

// clientState的Add方法
func (c *cache) Add(obj interface{}) error {
    // 计算key；一般是namespace/name
   key, err := c.keyFunc(obj)

    // cacheStorage 是一个 ThreadSafeStore 实例
   c.cacheStorage.Add(key, obj)
   return nil
}

// tools/cache/thread_safe_store.go

// ThreadSafeStore类的定义
func (c *threadSafeMap) Add(key string, obj interface{}) {
	c.lock.Lock()
	defer c.lock.Unlock()
	 // 拿出 old obj
	oldObject := c.items[key]
	 // 写入 new obj
	c.items[key] = obj
	 // 更新索引，有一堆逻辑
	c.updateIndices(oldObject, obj, key)
 }