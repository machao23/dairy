import (
	"fmt"
	"net"
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"k8s.io/kubernetes/pkg/probe/exec"
	"k8s.io/kubernetes/pkg/proxy"
	"k8s.io/kubernetes/pkg/proxy/config"
	"k8s.io/kubernetes/pkg/proxy/healthcheck"
	"k8s.io/kubernetes/pkg/proxy/iptables"
	"k8s.io/kubernetes/pkg/proxy/ipvs"
	"k8s.io/kubernetes/pkg/util/configz"
	"k8s.io/kubernetes/pkg/util/oom"
	"k8s.io/kubernetes/pkg/util/resourcecontainer"
	"k8s.io/kubernetes/staging/src/k8s.io/api/core/v1"
	"k8s.io/kubernetes/staging/src/k8s.io/apimachinery/pkg/runtime"
	"k8s.io/kubernetes/staging/src/k8s.io/apimachinery/pkg/types"
	"k8s.io/kubernetes/staging/src/k8s.io/apimachinery/pkg/util/wait"
	"k8s.io/kubernetes/staging/src/k8s.io/apiserver/pkg/server/healthz"
	"k8s.io/kubernetes/staging/src/k8s.io/apiserver/pkg/server/mux"
	"k8s.io/kubernetes/staging/src/k8s.io/apiserver/pkg/server/routes"
	"k8s.io/kubernetes/staging/src/k8s.io/client-go/informers"
	"k8s.io/kubernetes/staging/src/k8s.io/client-go/tools/record"
)

// cmd/kube-proxy/app/server_others.go

// 创建一个Proxy Server
func newProxyServer(
	config *proxyconfigapi.KubeProxyConfiguration,
	cleanupAndExit bool,
	cleanupIPVS bool,
	scheme *runtime.Scheme,
	master string) (*ProxyServer, error) {

	if c, err := configz.New(proxyconfigapi.GroupName); err == nil {
		c.Set(config)
	}

	//协议IPV4 or IPV6
	protocol := utiliptables.ProtocolIpv4
	if net.ParseIP(config.BindAddress).To4() == nil {
		klog.V(0).Infof("IPv6 bind address (%s), assume IPv6 operation", config.BindAddress)
		protocol = utiliptables.ProtocolIpv6
	}

	var iptInterface utiliptables.Interface
	var ipvsInterface utilipvs.Interface
	var kernelHandler ipvs.KernelHandler
	var ipsetInterface utilipset.Interface
	var dbus utildbus.Interface

	// Create a iptables utils.
	execer := exec.New()
	// dbus对象创建（linux实现进程间通信机制）
	dbus = utildbus.New()
	// iptables操作对象创建
	iptInterface = utiliptables.New(execer, dbus, protocol)
	// IPVS
	kernelHandler = ipvs.NewLinuxKernelHandler()
	// ipset
	ipsetInterface = utilipset.New(execer)
	// IPVS环境检测
	canUseIPVS, _ := ipvs.CanUseIPVSProxier(kernelHandler, ipsetInterface)
	if canUseIPVS {
		ipvsInterface = utilipvs.New(execer)
	}

	// api client
	client, eventClient, err := createClients(config.ClientConnection, master)

	// Create event recorder
	// 主机名
	hostname, err := utilnode.GetHostname(config.HostnameOverride)
	// 事件广播器
	eventBroadcaster := record.NewBroadcaster()
	// Create event recorder
	recorder := eventBroadcaster.NewRecorder(scheme, v1.EventSource{Component: "kube-proxy", Host: hostname})

	nodeRef := &v1.ObjectReference{
		Kind:      "Node",
		Name:      hostname,
		UID:       types.UID(hostname),
		Namespace: "",
	}

	var healthzServer *healthcheck.HealthzServer
	var healthzUpdater healthcheck.HealthzUpdater
	// 创建默认的healthzServer服务对象
	if len(config.HealthzBindAddress) > 0 {
		healthzServer = healthcheck.NewDefaultHealthzServer(config.HealthzBindAddress, 2*config.IPTables.SyncPeriod.Duration, recorder, nodeRef)
		healthzUpdater = healthzServer
	}

	var proxier proxy.ProxyProvider
	var serviceEventHandler proxyconfig.ServiceHandler
	var endpointsEventHandler proxyconfig.EndpointsHandler
	// proxyMode模式配置获取
	proxyMode := getProxyMode(string(config.Mode), iptInterface, kernelHandler, ipsetInterface, iptables.LinuxKernelCompatTester{})
	// node的IP
	nodeIP := net.ParseIP(config.BindAddress)
	if nodeIP.IsUnspecified() {
		nodeIP = utilnode.GetNodeIP(client, hostname)
	}

	// proxyMode为"IPTables"
	if proxyMode == proxyModeIPTables {
		klog.V(0).Info("Using iptables Proxier.")

		//创建iptables proxier对象
		proxierIPTables, err := iptables.NewProxier(
			iptInterface,
			utilsysctl.New(),
			execer,
			config.IPTables.SyncPeriod.Duration,
			config.IPTables.MinSyncPeriod.Duration,
			config.IPTables.MasqueradeAll,
			int(*config.IPTables.MasqueradeBit),
			config.ClusterCIDR,
			hostname,
			nodeIP,
			recorder,
			healthzUpdater,
			config.NodePortAddresses,
		)
		proxier = proxierIPTables
		serviceEventHandler = proxierIPTables
		endpointsEventHandler = proxierIPTables
	} else if proxyMode == proxyModeIPVS {
		klog.V(0).Info("Using ipvs Proxier.")
		// 先只关注默认的IpTables模式
	} else {
		klog.V(0).Info("Using userspace Proxier.")
		// Userspace模式要淘汰了
	}
	//注册reloadfunc为proxier的sync()同步方法
	iptInterface.AddReloadFunc(proxier.Sync)

	return &ProxyServer{
		Client:                 client,                           //apiServer client
		EventClient:            eventClient,                      //事件client
		IptInterface:           iptInterface,                     //iptables接口
		IpvsInterface:          ipvsInterface,                    //ipvs接口
		IpsetInterface:         ipsetInterface,                   //ipset接口
		execer:                 execer,                           //exec命令执行器
		Proxier:                proxier,                          //proxier创建对象
		Broadcaster:            eventBroadcaster,                 //事件广播器
		Recorder:               recorder,                         //事件记录器
		ConntrackConfiguration: config.Conntrack,                 //Conntrack配置
		Conntracker:            &realConntracker{},               //Conntrack对象
		ProxyMode:              proxyMode,                        //proxy模式
		NodeRef:                nodeRef,                          //node节点reference信息
		MetricsBindAddress:     config.MetricsBindAddress,        //metric服务地址配置
		EnableProfiling:        config.EnableProfiling,           //debug/pprof配置
		OOMScoreAdj:            config.OOMScoreAdj,               //OOMScoreAdj值配置
		ResourceContainer:      config.ResourceContainer,         //容器资源配置
		ConfigSyncPeriod:       config.ConfigSyncPeriod.Duration, //同步周期配置
		ServiceEventHandler:    serviceEventHandler,              //处理service事件proxier对象
		EndpointsEventHandler:  endpointsEventHandler,            //处理endpoints事件proxier对象
		HealthzServer:          healthzServer,                    //健康检测服务
	}, nil
}

// 运行 ProxyServer
// Run runs the specified ProxyServer.  This should never exit (unless CleanupAndExit is set).
func (s *ProxyServer) Run() error {
	// 根据启动参数配置"oom-score-adj"分值调整，取值区间[-1000, 1000]
	var oomAdjuster *oom.OOMAdjuster
	if s.OOMScoreAdj != nil {
		oomAdjuster = oom.NewOOMAdjuster()
		if err := oomAdjuster.ApplyOOMScoreAdj(0, int(*s.OOMScoreAdj)); err != nil {
			klog.V(2).Info(err)
		}
	}

	//"resource-container"设置是否运行在容器里
	if len(s.ResourceContainer) != 0 {
		// Run in its own container.
		if err := resourcecontainer.RunInResourceContainer(s.ResourceContainer); err != nil {
			klog.Warningf("Failed to start in resource-only container %q: %v", s.ResourceContainer, err)
		} else {
			klog.V(2).Infof("Running in resource-only container %q", s.ResourceContainer)
		}
	}

	//事件广播器
	if s.Broadcaster != nil && s.EventClient != nil {
		s.Broadcaster.StartRecordingToSink(&v1core.EventSinkImpl{Interface: s.EventClient.Events("")})
	}

	// Start up a healthz server if requested
	if s.HealthzServer != nil {
		s.HealthzServer.Run()
	}

	// Start up a metrics server if requested
	if len(s.MetricsBindAddress) > 0 {
		mux := mux.NewPathRecorderMux("kube-proxy")
		healthz.InstallHandler(mux)
		mux.HandleFunc("/proxyMode", func(w http.ResponseWriter, r *http.Request) {
			fmt.Fprintf(w, "%s", s.ProxyMode)
		})
		mux.Handle("/metrics", prometheus.Handler())
		if s.EnableProfiling {
			routes.Profiling{}.Install(mux)
		}
		configz.InstallHandler(mux)
		go wait.Until(func() {
			err := http.ListenAndServe(s.MetricsBindAddress, mux)
			if err != nil {
				utilruntime.HandleError(fmt.Errorf("starting metrics server failed: %v", err))
			}
		}, 5*time.Second, wait.NeverStop)
	}

	// 如果需要(命令选项或配置项)调节conntrack配置值
	// Tune conntrack, if requested
	// Conntracker is always nil for windows
	if s.Conntracker != nil {
		max, err := getConntrackMax(s.ConntrackConfiguration)
		if err != nil {
			return err
		}
		if max > 0 {
			err := s.Conntracker.SetMax(max)
		}
		//设置conntracker的TCPEstablishedTimeout
		if s.ConntrackConfiguration.TCPEstablishedTimeout != nil && s.ConntrackConfiguration.TCPEstablishedTimeout.Duration > 0 {
			timeout := int(s.ConntrackConfiguration.TCPEstablishedTimeout.Duration / time.Second)
			if err := s.Conntracker.SetTCPEstablishedTimeout(timeout); err != nil
		}

		//设置conntracker的TCPCloseWaitTimeout
		if s.ConntrackConfiguration.TCPCloseWaitTimeout != nil && s.ConntrackConfiguration.TCPCloseWaitTimeout.Duration > 0 {
			timeout := int(s.ConntrackConfiguration.TCPCloseWaitTimeout.Duration / time.Second)
			if err := s.Conntracker.SetTCPCloseWaitTimeout(timeout); err != nil 
		}
	}

	// informer机制获取与监听Services和Endpoints的配置与事件信息
	informerFactory := informers.NewSharedInformerFactory(s.Client, s.ConfigSyncPeriod)

	// 注册ServiceEventHandler服务事件的处理，同步service
	// kube-proxy同样使用client-go标准的ApiServer同步方式，创建informer,注册事件处理器handler，持续监控watch事件并调用handler处理事件add/update/delete
	// Create configs (i.e. Watches for Services and Endpoints)
	// Note: RegisterHandler() calls need to happen before creation of Sources because sources
	// only notify on changes, and the initial update (on process start) may be lost if no handlers
	// are registered yet.
	serviceConfig := config.NewServiceConfig(informerFactory.Core().V1().Services(), s.ConfigSyncPeriod)
	serviceConfig.RegisterEventHandler(s.ServiceEventHandler)
	go serviceConfig.Run(wait.NeverStop)

	// 注册EndpointsEventHandler端点事件的处理
	endpointsConfig := config.NewEndpointsConfig(informerFactory.Core().V1().Endpoints(), s.ConfigSyncPeriod)
	endpointsConfig.RegisterEventHandler(s.EndpointsEventHandler)
	go endpointsConfig.Run(wait.NeverStop)

	// This has to start after the calls to NewServiceConfig and NewEndpointsConfig because those
	// functions must configure their shared informer event handlers first.
	go informerFactory.Start(wait.NeverStop)

	//  服务成功启动，将启动事件广播。
	// Birth Cry after the birth is successful
	s.birthCry()

	//  Proxier(代理服务提供者)进行循环配置同步与处理proxy逻辑
	// Just loop forever for now...
	s.Proxier.SyncLoop()
	return nil
}

// pkg/proxy/config/config.go

// 创建serviceConfig实例
func NewServiceConfig(serviceInformer coreinformers.ServiceInformer, resyncPeriod time.Duration) *ServiceConfig {
	result := &ServiceConfig{
		lister:       serviceInformer.Lister(), // 监听器
		listerSynced: serviceInformer.Informer().HasSynced, //监听器同步状态值
	}

	//在服务informer上添加了资源事件的处理器handleFunc。（Add/update/delete）
	serviceInformer.Informer().AddEventHandlerWithResyncPeriod(
		cache.ResourceEventHandlerFuncs{
			AddFunc:    result.handleAddService,
			UpdateFunc: result.handleUpdateService,
			DeleteFunc: result.handleDeleteService,
		},
		resyncPeriod,
	)

	return result
}

// 通过 goroutine执行configServer.Run
// Run starts the goroutine responsible for calling
// registered handlers.
func (c *ServiceConfig) Run(stopCh <-chan struct{}) {
	defer utilruntime.HandleCrash()

	klog.Info("Starting service config controller")
	defer klog.Info("Shutting down service config controller")

	if !controller.WaitForCacheSync("service config", stopCh, c.listerSynced) {
		return
	}

	for i := range c.eventHandlers {
		klog.V(3).Info("Calling handler.OnServiceSynced()")
		//服务与proxier处理同步
		c.eventHandlers[i].OnServiceSynced()
	}

	<-stopCh
}

// 新增事件处理
func (c *ServiceConfig) handleAddService(obj interface{}) {
	service, ok := obj.(*v1.Service)
	for i := range c.eventHandlers {
		//服务新增事件与proxier处理同步
		klog.V(4).Info("Calling handler.OnServiceAdd")
		c.eventHandlers[i].OnServiceAdd(service)
	}
}

// 更新事件处理
func (c *ServiceConfig) handleUpdateService(oldObj, newObj interface{}) {
	oldService, ok := oldObj.(*v1.Service)
	service, ok := newObj.(*v1.Service)
	for i := range c.eventHandlers {
		//服务更新事件与proxier处理同步
		klog.V(4).Info("Calling handler.OnServiceUpdate")
		c.eventHandlers[i].OnServiceUpdate(oldService, service)
	}
}

// 删除事件处理
func (c *ServiceConfig) handleDeleteService(obj interface{}) {
	service, ok := obj.(*v1.Service)
	for i := range c.eventHandlers {
		//服务删除事件与proxier处理同步
		klog.V(4).Info("Calling handler.OnServiceDelete")
		c.eventHandlers[i].OnServiceDelete(service)
	}
}