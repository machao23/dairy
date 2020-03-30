package main

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"

	"github.com/spf13/cobra"
	"k8s.io/kubernetes/cmd/kube-scheduler/app"
	"k8s.io/kubernetes/cmd/kube-scheduler/app/options"
	"k8s.io/kubernetes/pkg/scheduler"
	"k8s.io/kubernetes/pkg/scheduler/algorithm"
	"k8s.io/kubernetes/pkg/scheduler/algorithm/predicates"
	"k8s.io/kubernetes/pkg/scheduler/core/equivalence"
	v1 "k8s.io/kubernetes/staging/src/k8s.io/api/core/v1"
	"k8s.io/kubernetes/staging/src/k8s.io/apimachinery/pkg/util/wait"
	"k8s.io/kubernetes/staging/src/k8s.io/client-go/util/workqueue"
)

// cmd/kube-scheduler/scheduler.go
// kube-scheduler的入口
func main() {
	command := app.NewSchedulerCommand()
	command.Execute()
}

// cmd/kube-scheduler/app/server.go
// 处理main入口调用的cobra command
func runCommand(cmd *cobra.Command, args []string, opts *options.Options) error {
	c, err := opts.Config()
	stopCh := make(chan struct{})
	// Get the completed config
	cc := c.Complete()
	return Run(cc, stopCh)
}

// 启动scheduler
func Run(cc schedulerserverconfig.CompletedConfig, stopCh <-chan struct{}) error {
	// Create the scheduler.
	sched, err := scheduler.New(cc.Client,
		cc.InformerFactory.Core().V1().Nodes(),
		cc.PodInformer,
		cc.InformerFactory.Core().V1().PersistentVolumes(),
		cc.InformerFactory.Core().V1().PersistentVolumeClaims(),
		cc.InformerFactory.Core().V1().ReplicationControllers(),
		cc.InformerFactory.Apps().V1().ReplicaSets(),
		cc.InformerFactory.Apps().V1().StatefulSets(),
		cc.InformerFactory.Core().V1().Services(),
		cc.InformerFactory.Policy().V1beta1().PodDisruptionBudgets(),
		storageClassInformer,
		cc.Recorder,
		cc.ComponentConfig.AlgorithmSource,
		stopCh,
		scheduler.WithName(cc.ComponentConfig.SchedulerName),
		scheduler.WithHardPodAffinitySymmetricWeight(cc.ComponentConfig.HardPodAffinitySymmetricWeight),
		scheduler.WithEquivalenceClassCacheEnabled(cc.ComponentConfig.EnableContentionProfiling),
		scheduler.WithPreemptionDisabled(cc.ComponentConfig.DisablePreemption),
		scheduler.WithPercentageOfNodesToScore(cc.ComponentConfig.PercentageOfNodesToScore),
		scheduler.WithBindTimeoutSeconds(*cc.ComponentConfig.BindTimeoutSeconds))

	// Prepare a reusable runCommand function.
	run := func(ctx context.Context) {
		sched.Run()
		// 调用了sched.Run()之后就在等待ctx.Done()了
		// 即 scheduler.run就会一直运行着
		<-ctx.Done()
	}

	go func() {
		select {
		case <-stopCh:
			cancel()
		case <-ctx.Done():
		}
	}()

	run(ctx)
	return fmt.Errorf("finished without leader elect")
}

// pkg/scheduler/scheduler.go
// scheduler对象

// Run begins watching and scheduling. It waits for cache to be synced, then starts a goroutine and returns immediately.
func (sched *Scheduler) Run() {
	// wait.Until函数在k8s.io/apimachinery/pkg/util/wait里定义的
	// 做的事情是：每隔n时间调用一次ched.scheduleOne，除非channel sched.config.StopEverything被关闭。
	// 这里的n就是0，也就是一直调用，前一次调用返回下一次调用就开始了。
	// 在另一个go线程里无限运行中
	go wait.Until(sched.scheduleOne, 0, sched.config.StopEverything)
}

// scheduleOne does the entire scheduling workflow for a single pod.  It is serialized on the scheduling algorithm's host fitting.
// scheduleOne实现1个pod的完整调度工作流，这个过程是顺序执行的，也就是非并发的。
// 也就是说前一个pod的scheduleOne一完成，一个return，下一个pod的scheduleOne立马接着执行！
// 如果是同时调度N个pod，计算的时候觉得一个node很空闲，实际调度过去启动的时候发现别的pod抢走了端口啊，内存啊！
// 所以这里的调度算法执行过程用串行逻辑很好理解。注意哦，调度过程跑完不是说要等pod起来，把这个pod绑定到哪个node告诉apiserver，所以不会太慢。
func (sched *Scheduler) scheduleOne() {
	// 获取一个待调度的pod
	pod := sched.config.NextPod()
	klog.V(3).Infof("Attempting to schedule pod: %v/%v", pod.Namespace, pod.Name)

	// 计算合适的node
	suggestedHost, err := sched.schedule(pod)
	// Tell the cache to assume that a pod now is running on a given node, even though it hasn't been bound yet.
	// This allows us to keep scheduling without waiting on binding to occur.
	assumedPod := pod.DeepCopy()

	// bind the pod to its host asynchronously (we can do this b/c of the assumption step above).
	go func() {
		// pod被记录将要调度到某个node
		err := sched.bind(assumedPod, &v1.Binding{
			ObjectMeta: metav1.ObjectMeta{Namespace: assumedPod.Namespace, Name: assumedPod.Name, UID: assumedPod.UID},
			Target: v1.ObjectReference{
				Kind: "Node",
				Name: suggestedHost,
			},
		})
	}()
}

// 计算合适的node
// schedule implements the scheduling algorithm and returns the suggested host.
func (sched *Scheduler) schedule(pod *v1.Pod) (string, error) {
	// ScheduleAlgorithm是个interface，默认实现是generic_scheduler
	// 给定pod和nodes，计算出一个适合跑pod的node并返回
	host, err := sched.config.Algorithm.Schedule(pod, sched.config.NodeLister)
	return host, err
}

// pkg/scheduler/core/generic_scheduler.go

// Schedule tries to schedule the given pod to one of the nodes in the node list.
// If it succeeds, it will return the name of the node.
// If it fails, it will return a FitError error with reasons.
func (g *genericScheduler) Schedule(pod *v1.Pod, nodeLister algorithm.NodeLister) (string, error) {
	trace := utiltrace.New(fmt.Sprintf("Scheduling %s/%s", pod.Namespace, pod.Name))

	nodes, err := nodeLister.List()

	// 筛选可用的node
	trace.Step("Computing predicates")
	// 筛选前的nodes，筛选后的filteredNodes
	filteredNodes, failedPredicateMap, err := g.findNodesThatFit(pod, nodes)

	// 从候选的nodes里挑选一个node
	trace.Step("Prioritizing")
	// When only one node after predicate, just use it.
	if len(filteredNodes) == 1 {
		return filteredNodes[0].Name, nil
	}

	metaPrioritiesInterface := g.priorityMetaProducer(pod, g.cachedNodeInfoMap)
	// 返回的priorityList里有每个候选node和对应的score
	priorityList, err := PrioritizeNodes(pod, g.cachedNodeInfoMap, metaPrioritiesInterface, g.prioritizers, filteredNodes, g.extenders)

	trace.Step("Selecting host")
	// 选出score最高的node
	return g.selectHost(priorityList)
}

// 筛选适合pod调度的nodes
// Filters the nodes to find the ones that fit based on the given predicate functions
// Each node is passed through the predicate functions to determine if it is a fit
func (g *genericScheduler) findNodesThatFit(pod *v1.Pod, nodes []*v1.Node) ([]*v1.Node, FailedPredicateMap, error) {
	var filtered []*v1.Node
	failedPredicateMap := FailedPredicateMap{}

	allNodes := int32(g.cache.NodeTree().NumNodes)
	numNodesToFind := g.numFeasibleNodesToFind(allNodes)

	// Create filtered list with enough space to avoid growing it
	// and allow assigning.
	filtered = make([]*v1.Node, numNodesToFind)

	// checknode会被后续的workqueue.ParallelizeUntil批量多个goroutine并行执行
	checkNode := func(i int) {
		var nodeCache *equivalence.NodeCache
		nodeName := g.cache.NodeTree().Next()
		if g.equivalenceCache != nil {
			nodeCache = g.equivalenceCache.LoadNodeCache(nodeName)
		}
		fits, failedPredicates, err := podFitsOnNode(
			pod,
			meta,
			g.cachedNodeInfoMap[nodeName],
			g.predicates,
			nodeCache,
			g.schedulingQueue,
			g.alwaysCheckAllPredicates,
			equivClass,
		)

		if fits {
			length := atomic.AddInt32(&filteredLen, 1)
			filtered[length-1] = g.cachedNodeInfoMap[nodeName].Node()
		}
	}

	// Stops searching for more nodes once the configured number of feasible nodes
	// are found.
	// ParallelizeUntil()函数是用于并行执行N个独立的工作过程的
	workqueue.ParallelizeUntil(ctx, 16, int(allNodes), checkNode)

	filtered = filtered[:filteredLen]

	if len(filtered) > 0 && len(g.extenders) != 0 {
		for _, extender := range g.extenders {
			filteredList, failedMap, err := extender.Filter(pod, filtered, g.cachedNodeInfoMap)
		}
	}
	return filtered, failedPredicateMap, nil
}

// 检查通过NodeInfo形式给定的node是否满足指定的predicate.
// podFitsOnNode checks whether a node given by NodeInfo satisfies the given predicate functions.
// For given pod, podFitsOnNode will check if any equivalent pod exists and try to reuse its cached
// predicate results as possible.
// This function is called from two different places: Schedule and Preempt.

// 当从Schedule进入时：测试node上所有已经存在的pod
// 外加被指定将要调度到这个node上的其他所有高优先级（优先级不比自己低，也就是>=）的pod后，当前pod是否可以被调度到这个node上。
// When it is called from Schedule, we want to test whether the pod is schedulable
// on the node with all the existing pods on the node plus higher and equal priority
// pods nominated to run on the node.
// When it is called from Preempt, we should remove the victims of preemption and
// add the nominated pods. Removal of the victims is done by SelectVictimsOnNode().
// It removes victims from meta and NodeInfo before calling this function.
func podFitsOnNode(
	pod *v1.Pod,
	meta algorithm.PredicateMetadata,
	info *schedulercache.NodeInfo,
	// 所有的predicate函数
	predicateFuncs map[string]algorithm.FitPredicate,
	nodeCache *equivalence.NodeCache,
	queue internalqueue.SchedulingQueue,
	alwaysCheckAllPredicates bool,
	equivClass *equivalence.Class,
) (bool, []algorithm.PredicateFailureReason, error) {

	// 出于某些原因考虑我们需要运行两次predicate.
	// 如果node上有更高或者相同优先级的“指定pods”（这里的“指定pods”指的是通过schedule计算后指定要跑在一个node上但是还未真正运行到那个node上的pods），
	// We run predicates twice in some cases. If the node has greater or equal priority
	// // 我们将这些pods加入到meta和nodeInfo后执行一次计算过程。
	// nominated pods, we run them when those pods are added to meta and nodeInfo.
	// 如果这个过程所有的predicates都成功了，我们再假设这些“指定pods”不会跑到node上再运行一次。
	// If all predicates succeed in this pass, we run them again when these
	// 第二次计算是必须的，因为有一些predicates比如pod亲和性(可能待调度的2个pod必须在一个node里），也许在“指定pods”没有成功跑到node的情况下会不满足。
	// nominated pods are not added. This second pass is necessary because some
	// predicates such as inter-pod affinity may not pass without the nominated pods.
	// 如果没有“指定pods”(!podsAdded是什么意思？）或者第一次计算过程失败了，那么第二次计算不会进行。
	// If there are no nominated pods for the node or if the first run of the
	// predicates fail, we don't run the second pass.
	// 我们在第一次调度的时候只考虑相等或者更高优先级的pods，
	// We consider only equal or higher priority pods in the first pass, because
	// 因为这些pod是当前pod必须“臣服”的，也就是说不能够从这些pod中抢到资源，这些pod不会被当前pod“抢占”；
	// those are the current "pod" must yield to them and not take a space opened
	// 这样当前pod也就能够安心从低优先级的pod手里抢资源了。
	// for running them. It is ok if the current "pod" take resources freed for
	// lower priority pods.
	// 新pod在上述2种情况下都可调度基于一个保守的假设：
	// Requiring that the new pod is schedulable in both circumstances ensures that
	// 资源和pod反亲和性等的predicate在“指定pods”被处理为Running时更容易失败；
	// we are making a conservative decision: predicates like resources and inter-pod
	// anti-affinity are more likely to fail when the nominated pods are treated
	// pod亲和性在“指定pods”被处理为Not Running时更加容易失败。
	// as running, while predicates like pod affinity are more likely to fail when
	// 我们不能假设“指定pods”是Running的因为它们当前还没有运行，而且事实上，它们确实有可能最终又被调度到其他node上了。
	// the nominated pods are treated as not running. We can't just assume the
	// nominated pods are running because they are not running right now and in fact,
	// they may end up getting scheduled to a different node.
	for i := 0; i < 2; i++ {
		metaToUse := meta
		nodeInfoToUse := info
		if i == 0 {
			podsAdded, metaToUse, nodeInfoToUse = addNominatedPods(pod, meta, info, queue)
		} else if !podsAdded || len(failedPredicates) != 0 {
			break
		}

		// 这里的predicates是从pkg/scheduler/algorithm/predicates/predicates.go里import的
		for predicateID, predicateKey := range predicates.Ordering() {
			var (
				fit     bool
				reasons []algorithm.PredicateFailureReason
				err     error
			)
			if predicate, exist := predicateFuncs[predicateKey]; exist {

				fit, reasons, err = predicate(pod, metaToUse, nodeInfoToUse)
			}
		}
	}

	return len(failedPredicates) == 0, failedPredicates, nil
}

// ----------------------------------------------------------------
// pkg/scheduler/algorithm/predicates/predicates.go

// IMPORTANT NOTE: this list contains the ordering of the predicates, if you develop a new predicate
// it is mandatory to add its name to this list.
// Otherwise it won't be processed, see generic_scheduler#podFitsOnNode().
// The order is based on the restrictiveness & complexity of predicates.
// Design doc: https://github.com/kubernetes/community/blob/master/contributors/design-proposals/scheduling/predicates-ordering.md
var (
	// 不管predicateFuncs里定义了怎样的顺序，影响不了predicate的实际调用顺序。
	predicatesOrdering = []string{CheckNodeConditionPred, CheckNodeUnschedulablePred,
		GeneralPred, HostNamePred, PodFitsHostPortsPred,
		MatchNodeSelectorPred, PodFitsResourcesPred, NoDiskConflictPred,
		PodToleratesNodeTaintsPred, PodToleratesNodeNoExecuteTaintsPred, CheckNodeLabelPresencePred,
		CheckServiceAffinityPred, MaxEBSVolumeCountPred, MaxGCEPDVolumeCountPred, MaxCSIVolumeCountPred,
		MaxAzureDiskVolumeCountPred, CheckVolumeBindingPred, NoVolumeZoneConflictPred,
		CheckNodeMemoryPressurePred, CheckNodePIDPressurePred, CheckNodeDiskPressurePred, MatchInterPodAffinityPred}
)

// ----------------------------------------------------------------
// vendor/k8s.io/client-go/util/workqueue/parallelizer.go

// ParallelizeUntil is a framework that allows for parallelizing N
// independent pieces of work until done or the context is canceled.
func ParallelizeUntil(ctx context.Context, workers, pieces int, doWorkPiece DoWorkPieceFunc) {
	// 创建int类型的channel，capacity大小是pieces
	toProcess := make(chan int, pieces)
	for i := 0; i < pieces; i++ {
		// 往channel里写0到node-1的数字
		toProcess <- i
	}
	close(toProcess)

	// workers是最大并发数，实际并发数 = min (pieces, workers)
	if pieces < workers {
		workers = pieces
	}

	// 类似java里的CountdownLatch
	wg := sync.WaitGroup{}
	wg.Add(workers)
	for i := 0; i < workers; i++ {
		go func() {
			defer utilruntime.HandleCrash()
			defer wg.Done()
			// 从toProcess中拿一个数，举个例子，假如现在并发是10，小于16，那么toProcess里面存的数据其实也是10个
			// 也就是1个goroutine拿到1个数，开始执行doWorkPiece；
			// 假设并发数是16，node数是100，这时候toProcess里面也就是100个数，
			// 这时候就是16个“消费者”在消耗100个数。每拿到一个数需要执行doWorkPiece，这里16就是限制了最多开16个goroutine
			for piece := range toProcess {
				select {
				case <-stop:
					return
				default:
					doWorkPiece(piece)
				}
			}
		}()
	}
	// 阻塞等待16个goroutine都处理完
	wg.Wait()
}
