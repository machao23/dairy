package main

import (
	"context"
	"fmt"

	"github.com/spf13/cobra"
	"k8s.io/kubernetes/cmd/kube-scheduler/app"
	"k8s.io/kubernetes/cmd/kube-scheduler/app/options"
	"k8s.io/kubernetes/pkg/scheduler"
	v1 "k8s.io/kubernetes/staging/src/k8s.io/api/core/v1"
	"k8s.io/kubernetes/staging/src/k8s.io/apimachinery/pkg/util/wait"
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
	pod := sched.config.NextPod()
	klog.V(3).Infof("Attempting to schedule pod: %v/%v", pod.Namespace, pod.Name)

	suggestedHost, err := sched.schedule(pod)
	// Tell the cache to assume that a pod now is running on a given node, even though it hasn't been bound yet.
	// This allows us to keep scheduling without waiting on binding to occur.
	assumedPod := pod.DeepCopy()

	// bind the pod to its host asynchronously (we can do this b/c of the assumption step above).
	go func() {
		err := sched.bind(assumedPod, &v1.Binding{
			ObjectMeta: metav1.ObjectMeta{Namespace: assumedPod.Namespace, Name: assumedPod.Name, UID: assumedPod.UID},
			Target: v1.ObjectReference{
				Kind: "Node",
				Name: suggestedHost,
			},
		})
	}()
}
