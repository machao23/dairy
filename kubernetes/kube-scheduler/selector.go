import (
	"fmt"
	"strconv"

	"k8s.io/kubernetes/pkg/scheduler/algorithm"
	v1 "k8s.io/kubernetes/staging/src/k8s.io/api/core/v1"
	"k8s.io/kubernetes/staging/src/k8s.io/apimachinery/pkg/fields"
	"k8s.io/kubernetes/staging/src/k8s.io/apimachinery/pkg/labels"
	"k8s.io/kubernetes/staging/src/k8s.io/apimachinery/pkg/selection"
)

// vendor/k8s.io/apimachinery/pkg/apis/meta/v1

// labels标签匹配计算依赖labels.selector工具(公共使用部分)
// 除了LabelsSelector外还有NodeSelector 、FieldsSelector、PropertySelector等，但基本都是类似的Selector接口实现，逻辑上都基本一致
func LabelSelectorAsSelector(ps *LabelSelector) (labels.Selector, error) {
	selector := labels.NewSelector()
	return selector, nil
}

// vendor/k8s.io/apimachinery/pkg/labels/selector.go:

// NewSelector返回的是一个InternelSelector类型，而InternelSelector类型是一个Requirement
// NewSelector returns a nil selector
func NewSelector() Selector {
	return internalSelector(nil)
}

type internalSelector []Requirement

// Requirement就是条件表达式，所以selector就是多个条件表达式
// Requirement contains values, a key, and an operator that relates the key and values.
// The zero value of Requirement is invalid.
// Requirement implements both set based match and exact match
// Requirement should be initialized via NewRequirement constructor for creating a valid Requirement.
// +k8s:deepcopy-gen=true
type Requirement struct {
	key      string
	operator selection.Operator
	// In huge majority of cases we have at most one value here.
	// It is generally faster to operate on a single-element slice
	// than on a single-element map, so we have a slice here.
	strValues []string
}

// InternelSelector类的Matches()底层实现是遍历调用requirement.Matches()
// 这里可以看出selector包含了多个匹配label的规则，只有全部规则都成功才算匹配成功
// Matches for a internalSelector returns true if all
// its Requirements match the input Labels. If any
// Requirement does not match, false is returned.
func (lsel internalSelector) Matches(l Labels) bool {
	for ix := range lsel {
		if matches := lsel[ix].Matches(l); !matches {
			return false
		}
	}
	return true
}

// requirment.matchs() 条件表达式匹配操作实现,基于表达式operator,计算key/value,返回匹配与否
// Matches returns true if the Requirement matches the input Labels.
// There is a match in the following cases:
// (1) The operator is Exists and Labels has the Requirement's key.
// (2) The operator is In, Labels has the Requirement's key and Labels'
//     value for that key is in Requirement's value set.
// (3) The operator is NotIn, Labels has the Requirement's key and
//     Labels' value for that key is not in Requirement's value set.
// (4) The operator is DoesNotExist or NotIn and Labels does not have the
//     Requirement's key.
// (5) The operator is GreaterThanOperator or LessThanOperator, and Labels has
//     the Requirement's key and the corresponding value satisfies mathematical inequality.
func (r *Requirement) Matches(ls Labels) bool {
	switch r.operator {
	case selection.In, selection.Equals, selection.DoubleEquals:
		if !ls.Has(r.key) {
			return false
		}
		return r.hasValue(ls.Get(r.key))
	case selection.NotIn, selection.NotEquals:
		if !ls.Has(r.key) {
			return true
		}
		return !r.hasValue(ls.Get(r.key))
	case selection.Exists:
		return ls.Has(r.key)
	case selection.DoesNotExist:
		return !ls.Has(r.key)
	case selection.GreaterThan, selection.LessThan:
		if !ls.Has(r.key) {
			return false
		}
		lsValue, err := strconv.ParseInt(ls.Get(r.key), 10, 64)
		var rValue int64
		for i := range r.strValues {
			rValue, err = strconv.ParseInt(r.strValues[i], 10, 64)
		}
		return (r.operator == selection.GreaterThan && lsValue > rValue) || (r.operator == selection.LessThan && lsValue < rValue)
	default:
		return false
	}
}

// pkg/scheduler/algorithm/predicates/predicates.go

// Node Affinity, 获取目标Node信息,调用podMatchesNodeSelectorAndAffinityTerms()对被调度pod和目标node进行亲和性匹配
// PodMatchNodeSelector checks if a pod node selector matches the node label.
func PodMatchNodeSelector(pod *v1.Pod, meta algorithm.PredicateMetadata, nodeInfo *schedulercache.NodeInfo) (bool, []algorithm.PredicateFailureReason, error) {
	node := nodeInfo.Node()
	if podMatchesNodeSelectorAndAffinityTerms(pod, node) {
		return true, nil, nil
	}
	return false, []algorithm.PredicateFailureReason{ErrNodeSelectorNotMatch}, nil
}

// podMatchesNodeSelectorAndAffinityTerms checks whether the pod is schedulable onto nodes according to
// the requirements in both NodeAffinity and nodeSelector.
func podMatchesNodeSelectorAndAffinityTerms(pod *v1.Pod, node *v1.Node) bool {
	// 如果设置了NodeSelector,则检测Node labels是否满足NodeSelector所定义的所有terms项.
	// Check if node.Labels match pod.Spec.NodeSelector.
	if len(pod.Spec.NodeSelector) > 0 {
		selector := labels.SelectorFromSet(pod.Spec.NodeSelector)
		if !selector.Matches(labels.Set(node.Labels)) {
			return false
		}
	}

	//如果设置了NodeAffinity（比nodeSelector表达式更复杂，同时支持软亲和），则进行Node亲和性匹配
	nodeAffinityMatches := true
	affinity := pod.Spec.Affinity
	if affinity != nil && affinity.NodeAffinity != nil {
		nodeAffinity := affinity.NodeAffinity
		// if no required NodeAffinity requirements, will do no-op, means select all nodes.
		if nodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution == nil {
			// if nodeAffinity.RequiredDuringSchedulingRequiredDuringExecution == nil && nodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution == nil {
			return true
		}

		// Match node selector for requiredDuringSchedulingIgnoredDuringExecution.
		if nodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution != nil {
			nodeSelectorTerms := nodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution.NodeSelectorTerms
			nodeAffinityMatches = nodeAffinityMatches && nodeMatchesNodeSelectorTerms(node, nodeSelectorTerms)
		}

	}
	return nodeAffinityMatches
}

// nodeMatchesNodeSelectorTerms checks if a node's labels satisfy a list of node selector terms,
// terms are ORed, and an empty list of terms will match nothing.
func nodeMatchesNodeSelectorTerms(node *v1.Node, nodeSelectorTerms []v1.NodeSelectorTerm) bool {
	nodeFields := map[string]string{}
	// 获取检测目标node的Filelds
	for k, f := range algorithm.NodeFieldSelectorKeys {
		nodeFields[k] = f(node)
	}
	// 调用v1helper.MatchNodeSelectorTerms()
	// 参数：nodeSelectorTerms  亲和性配置的必要条件Terms
	//      labels             被检测的目标node的label列表
	//      fields             被检测的目标node filed列表
	return v1helper.MatchNodeSelectorTerms(nodeSelectorTerms, labels.Set(node.Labels), fields.Set(nodeFields))
}

// pod亲和性检查
func NewPodAffinityPredicate(info NodeInfo, podLister algorithm.PodLister) algorithm.FitPredicate {
	checker := &PodAffinityChecker{
		info:      info,
		podLister: podLister,
	}
	return checker.InterPodAffinityMatches
}

// 被PodAffinityChecker调用，检查pod亲和性
func (c *PodAffinityChecker) InterPodAffinityMatches(pod *v1.Pod, meta algorithm.PredicateMetadata, nodeInfo *schedulercache.NodeInfo) (bool, []algorithm.PredicateFailureReason, error) {
	node := nodeInfo.Node()

	//  检测当pod被调度到目标node上是否触犯了其它pods所定义的反亲和配置. 
	// 即：当调度一个pod到目标Node上，而某个或某些Pod定义了反亲和配置与被 调度的Pod相匹配(触犯)，那么就不应该将此Node加入到可选的潜在调度Nodes列表内.
	if failedPredicates, error := c.satisfiesExistingPodsAntiAffinity(pod, meta, nodeInfo); 

	// Now check if <pod> requirements will be satisfied on this node.
	// pod亲和性检查
	affinity := pod.Spec.Affinity
	if affinity == nil || (affinity.PodAffinity == nil && affinity.PodAntiAffinity == nil) {
		// 没有配置亲和性，返回成功
		return true, nil, nil
	}

	// 满足Pods亲和与反亲和配置
	if failedPredicates, error := c.satisfiesPodsAffinityAntiAffinity(pod, meta, nodeInfo, affinity); 

	return true, nil, nil
}

// 被 InterPodAffinityMatches 调用
func (c *PodAffinityChecker) satisfiesExistingPodsAntiAffinity(pod *v1.Pod, meta algorithm.PredicateMetadata, nodeInfo *schedulercache.NodeInfo) (algorithm.PredicateFailureReason, error) {
	node := nodeInfo.Node()

	var topologyMaps *topologyPairsMaps

	//  过滤掉pod的nodeName等于NodeInfo.Node.Name,且不存在于nodeinfo中.
    //  即运行在其它Nodes上的Pods，只看这个node上其他的pods
	// Filter out pods whose nodeName is equal to nodeInfo.node.Name, but are not
	// present in nodeInfo. Pods on other nodes pass the filter.
	filteredPods, err := c.podLister.FilteredList(nodeInfo.Filter, labels.Everything())

	// 获取被调度Pod与其它存在反亲和配置的Pods匹配的topologyMaps
	if topologyMaps, err = c.getMatchingAntiAffinityTopologyPairsOfPods(pod, filteredPods);
	
	// 遍历所有topology pairs(所有反亲和topologyKey/Value)，检测Node是否有影响.
	// Iterate over topology pairs to get any of the pods being affected by
	// the scheduled pod anti-affinity terms
	for topologyKey, topologyValue := range node.Labels {
		if topologyMaps.topologyPairToPods[topologyPair{key: topologyKey, value: topologyValue}] != nil {
			klog.V(10).Infof("Cannot schedule pod %+v onto node %v", podName(pod), node.Name)
			return ErrExistingPodsAntiAffinityRulesNotMatch, nil
		}
	}

	return nil, nil
}

// 被satisfiesExistingPodsAntiAffinity调用，获取被调度Pod与其它存在反亲和配置的Pods匹配的topologyMaps
func (c *PodAffinityChecker) getMatchingAntiAffinityTopologyPairsOfPods(pod *v1.Pod, existingPods []*v1.Pod) (*topologyPairsMaps, error) {
	topologyMaps := newTopologyPairsMaps()

	for _, existingPod := range existingPods {
		// 遍历所有存在Pods,获取pod所运行的Node信息
		existingPodNode, err := c.info.GetNodeInfo(existingPod.Spec.NodeName)
		// 依据被调度的pod、目标pod、目标Node信息(上面获取得到)获取TopologyPairs。
		existingPodTopologyMaps, err := getMatchingAntiAffinityTopologyPairsOfPod(pod, existingPod, existingPodNode)

		topologyMaps.appendMaps(existingPodTopologyMaps)
	}
	return topologyMaps, nil
}

// 被 getMatchingAntiAffinityTopologyPairsOfPods 调用
//1)是否ExistingPod定义了反亲和配置，如果没有直接返回
//2)如果有定义，是否有任务一个反亲和Term匹配需被调度的pod.
//  如果配置则将返回term定义的TopologyKey和Node的topologyValue.
// getMatchingAntiAffinityTopologyPairs calculates the following for "existingPod" on given node:
// (1) Whether it has PodAntiAffinity
// (2) Whether ANY AffinityTerm matches the incoming pod
func getMatchingAntiAffinityTopologyPairsOfPod(newPod *v1.Pod, existingPod *v1.Pod, node *v1.Node) (*topologyPairsMaps, error) {
	affinity := existingPod.Spec.Affinity

	topologyMaps := newTopologyPairsMaps()
	for _, term := range GetPodAntiAffinityTerms(affinity.PodAntiAffinity) {
		namespaces := priorityutil.GetNamespacesFromPodAffinityTerm(existingPod, &term)
		selector, err := metav1.LabelSelectorAsSelector(term.LabelSelector)

		if priorityutil.PodMatchesTermsNamespaceAndSelector(newPod, namespaces, selector) {
			if topologyValue, ok := node.Labels[term.TopologyKey]; ok {
				pair := topologyPair{key: term.TopologyKey, value: topologyValue}
				topologyMaps.addTopologyPair(pair, existingPod)
			}
		}
	}
	return topologyMaps, nil
}

// 被 InterPodAffinityMatches 调用
// Checks if scheduling the pod onto this node would break any term of this pod.
func (c *PodAffinityChecker) satisfiesPodsAffinityAntiAffinity(pod *v1.Pod,
	meta algorithm.PredicateMetadata, nodeInfo *schedulercache.NodeInfo,
	affinity *v1.Affinity) (algorithm.PredicateFailureReason, error) {
	node := nodeInfo.Node()
	// 通过指定podFilter过滤器获取满足条件的pod列表
	filteredPods, err := c.podLister.FilteredList(nodeInfo.Filter, labels.Everything())
	//获取亲和、反亲和配置定义的"匹配条件"Terms
	affinityTerms := GetPodAffinityTerms(affinity.PodAffinity)
	antiAffinityTerms := GetPodAntiAffinityTerms(affinity.PodAntiAffinity)
	matchFound, termsSelectorMatchFound := false, false
	for _, targetPod := range filteredPods {
		// 遍历所有目标Pod,检测所有亲和性配置"匹配条件"Terms
		// Check all affinity terms.
		if !matchFound && len(affinityTerms) > 0 {
			// podMatchesPodAffinityTerms()对namespaces和标签条件表达式进行匹配目标pod
			affTermsMatch, termsSelectorMatch, err := c.podMatchesPodAffinityTerms(pod, targetPod, nodeInfo, affinityTerms)
	
			if termsSelectorMatch {
				termsSelectorMatchFound = true
			}
			if affTermsMatch {
				matchFound = true
			}
		}
		// 同上，遍历所有目标Pod,检测所有Anti反亲和配置"匹配条件"Terms.
		// Check all anti-affinity terms.
		if len(antiAffinityTerms) > 0 {
			antiAffTermsMatch, _, err := c.podMatchesPodAffinityTerms(pod, targetPod, nodeInfo, antiAffinityTerms)
			if err != nil || antiAffTermsMatch {
				klog.V(10).Infof("Cannot schedule pod %+v onto node %v, because of PodAntiAffinityTerm, err: %v",
					podName(pod), node.Name, err)
				return ErrPodAntiAffinityRulesNotMatch, nil
			}
		}
	}

	if !matchFound && len(affinityTerms) > 0 {
		// We have not been able to find any matches for the pod's affinity terms.
		// This pod may be the first pod in a series that have affinity to themselves. In order
		// to not leave such pods in pending state forever, we check that if no other pod
		// in the cluster matches the namespace and selector of this pod and the pod matches
		// its own terms, then we allow the pod to pass the affinity check.
		if termsSelectorMatchFound {
			klog.V(10).Infof("Cannot schedule pod %+v onto node %v, because of PodAffinity",
				podName(pod), node.Name)
			return ErrPodAffinityRulesNotMatch, nil
		}
		// Check if pod matches its own affinity properties (namespace and label selector).
		if !targetPodMatchesAffinityOfPod(pod, pod) {
			klog.V(10).Infof("Cannot schedule pod %+v onto node %v, because of PodAffinity",
				podName(pod), node.Name)
			return ErrPodAffinityRulesNotMatch, nil
		}
	}
	
	return nil, nil
}

// 被 satisfiesPodsAffinityAntiAffinity 调用
// 通过获取亲和配置定义的所有namespaces和标签条件表达式进行匹配目标pod,
// 完全符合则获取此目标pod的运行node的topologykey（此为affinity指定的topologykey）的值和潜在Node的topologykey的值比对是否一致.
// podMatchesPodAffinityTerms checks if the "targetPod" matches the given "terms"
// of the "pod" on the given "nodeInfo".Node(). It returns three values: 1) whether
// targetPod matches all the terms and their topologies, 2) whether targetPod
// matches all the terms label selector and namespaces (AKA term properties),
// 3) any error.
func (c *PodAffinityChecker) podMatchesPodAffinityTerms(pod, targetPod *v1.Pod, nodeInfo *schedulercache.NodeInfo, terms []v1.PodAffinityTerm) (bool, bool, error) {
	// 获取{namespaces,selector}列表
	props, err := getAffinityTermProperties(pod, terms)

	// 匹配目标pod是否在affinityTerm定义的{namespaces,selector}列表内所有项，如果不匹配则返回false,
	if !podMatchesAllAffinityTermProperties(targetPod, props) {
		return false, false, nil
	}
	// 如果匹配则获取此pod的运行node信息(称为目标Node)，
	// Namespace and selector of the terms have matched. Now we check topology of the terms.
	targetPodNode, err := c.info.GetNodeInfo(targetPod.Spec.NodeName)

	// 通过“目标Node”所定义的topologykey（此为affinity指定的topologykey）的值来匹配“潜在被调度的Node”的topologykey是否一致
	for _, term := range terms {
		if len(term.TopologyKey) == 0 {
			return false, false, fmt.Errorf("empty topologyKey is not allowed except for PreferredDuringScheduling pod anti-affinity")
		}

		// 判断两者的topologyKey定义的值是否一致。
		if !priorityutil.NodesHaveSameTopologyKey(nodeInfo.Node(), targetPodNode, term.TopologyKey) {
			return false, true, nil
		}
	}
	return true, true, nil
}

// pkg/apis/core/v1/helper/helper.go

// matchExpressions”定义检测(匹配key与value)
// matchFileds”定义检测(不匹配key，只value)
// MatchNodeSelectorTerms checks whether the node labels and fields match node selector terms in ORed;
// nil or empty term matches no objects.
func MatchNodeSelectorTerms(
	nodeSelectorTerms []v1.NodeSelectorTerm,
	nodeLabels labels.Set,
	nodeFields fields.Set,
) bool {
	for _, req := range nodeSelectorTerms {
		if len(req.MatchExpressions) != 0 {
			// MatchExpressions条件表达式匹配
			labelSelector, err := NodeSelectorRequirementsAsSelector(req.MatchExpressions)
			if err != nil || !labelSelector.Matches(nodeLabels) {
				continue
			}
		}

		if len(req.MatchFields) != 0 {
			// MatchFields条件表达式匹配
			fieldSelector, err := NodeSelectorRequirementsAsFieldSelector(req.MatchFields)
			if err != nil || !fieldSelector.Matches(nodeFields) {
				continue
			}
		}

		return true
	}

	return false
}

// Expressions表达式匹配
func NodeSelectorRequirementsAsSelector(nsm []v1.NodeSelectorRequirement) (labels.Selector, error) {
	selector := labels.NewSelector()
	for _, expr := range nsm {
		var op selection.Operator
		switch expr.Operator {
		case v1.NodeSelectorOpIn:
			op = selection.In
		case v1.NodeSelectorOpNotIn:
			op = selection.NotIn
		case v1.NodeSelectorOpExists:
			op = selection.Exists
		case v1.NodeSelectorOpDoesNotExist:
			op = selection.DoesNotExist
		case v1.NodeSelectorOpGt:
			op = selection.GreaterThan
		case v1.NodeSelectorOpLt:
			op = selection.LessThan
		default:
			return nil, fmt.Errorf("%q is not a valid node selector operator", expr.Operator)
		}
		// 表达式的三个关键要素： expr.Key, op, expr.Values
		r, err := labels.NewRequirement(expr.Key, op, expr.Values)
		selector = selector.Add(*r)
	}
	return selector, nil
}

// Fields表达式匹配
func NodeSelectorRequirementsAsFieldSelector(nsm []v1.NodeSelectorRequirement) (fields.Selector, error) {
	selectors := []fields.Selector{}
	for _, expr := range nsm {
		switch expr.Operator {
		case v1.NodeSelectorOpIn:
			selectors = append(selectors, fields.OneTermEqualSelector(expr.Key, expr.Values[0]))

		case v1.NodeSelectorOpNotIn:
			selectors = append(selectors, fields.OneTermNotEqualSelector(expr.Key, expr.Values[0]))
	}

	return fields.AndSelectors(selectors...), nil
}

// pkg/scheduler/algorithm/priorities/node_affinity.go

// 软Node亲和匹配
// 对潜在被调度Node的labels进行Match匹配检测，如果匹配则将条件所给定的Weight权重值累计。 最后将返回各潜在的被调度Node最后分值。
func CalculateNodeAffinityPriorityMap(pod *v1.Pod, meta interface{}, nodeInfo *schedulercache.NodeInfo) (schedulerapi.HostPriority, error) {
	node := nodeInfo.Node()
	// 默认为Spec配置的Affinity
	affinity := pod.Spec.Affinity
	if priorityMeta, ok := meta.(*priorityMetadata); ok {
		// We were able to parse metadata, use affinity from there.
		affinity = priorityMeta.affinity
	}

	var count int32
	if affinity != nil && affinity.NodeAffinity != nil && affinity.NodeAffinity.PreferredDuringSchedulingIgnoredDuringExecution != nil {
		// 遍历PreferredDuringSchedulingIgnoredDuringExecution定义的`必要条件项`(Terms)
		for i := range affinity.NodeAffinity.PreferredDuringSchedulingIgnoredDuringExecution {
			preferredSchedulingTerm := &affinity.NodeAffinity.PreferredDuringSchedulingIgnoredDuringExecution[i]
			// 如果weight为0则不做任何处理
			if preferredSchedulingTerm.Weight == 0 {
				continue
			}
			// 获取node亲和MatchExpression表达式条件
			nodeSelector, err := v1helper.NodeSelectorRequirementsAsSelector(preferredSchedulingTerm.Preference.MatchExpressions)
			if nodeSelector.Matches(labels.Set(node.Labels)) {
				count += preferredSchedulingTerm.Weight
			}
		}
	}
	// 返回Node和得分
	return schedulerapi.HostPriority{
		Host:  node.Name,
		Score: int(count),
	}, nil
}

// 将各个node的最后得分重新计算分布区间在0〜10.
// 代码内给定一个NormalizeReduce()方法，MaxPriority值为10,reverse取反false关闭
var CalculateNodeAffinityPriorityReduce = NormalizeReduce(schedulerapi.MaxPriority, false)

// pkg/scheduler/algorithm/priorities/reduce.go

// 结果评分取值0〜MaxPriority
// reverse取反为true时，最终评分=(MaxPriority-其原评分值）
func NormalizeReduce(maxPriority int, reverse bool) algorithm.PriorityReduceFunction {
	return func(
		_ *v1.Pod,
		_ interface{},
		_ map[string]*schedulercache.NodeInfo,
		result schedulerapi.HostPriorityList) error {

		var maxCount int
		for i := range result {
			if result[i].Score > maxCount {
				maxCount = result[i].Score
			}
		}

		// 如果最大的值为0，且取反设为真，则将所有的评分设置为MaxPriority
		if maxCount == 0 {
			if reverse {
				for i := range result {
					result[i].Score = maxPriority
				}
			}
			return nil
		}

		for i := range result {
			score := result[i].Score
			// 计算后得分 = maxPrority * 原分值 / 最大值
			score = maxPriority * score / maxCount
			if reverse {
				// 如果取反为真则 maxPrority - 计算后得分
				score = maxPriority - score
			}

			result[i].Score = score
		}
		return nil
	}
}

// pkg/scheduler/algorithm/priorities/interpod_affinity.go

// CalculateInterPodAffinityPriority() 基于pod亲和性配置匹配"必要条件项”Terms,并发处理所有目标nodes,为其目标node统计亲和weight得分. 
// CalculateInterPodAffinityPriority compute a sum by iterating through the elements of weightedPodAffinityTerm and adding
// "weight" to the sum if the corresponding PodAffinityTerm is satisfied for
// that node; the node(s) with the highest sum are the most preferred.
// Symmetry need to be considered for preferredDuringSchedulingIgnoredDuringExecution from podAffinity & podAntiAffinity,
// symmetry need to be considered for hard requirements from podAffinity
func (ipa *InterPodAffinity) CalculateInterPodAffinityPriority(pod *v1.Pod, nodeNameToInfo map[string]*schedulercache.NodeInfo, nodes []*v1.Node) (schedulerapi.HostPriorityList, error) {
	//"需被调度Pod"是否存在亲和、反亲和约束配置
	affinity := pod.Spec.Affinity
	hasAffinityConstraints := affinity != nil && affinity.PodAffinity != nil
	hasAntiAffinityConstraints := affinity != nil && affinity.PodAntiAffinity != nil

	allNodeNames := make([]string, 0, len(nodeNameToInfo))
	for name := range nodeNameToInfo {
		allNodeNames = append(allNodeNames, name)
	}

	// priorityMap stores the mapping from node name to so-far computed score of
	// the node.
	pm := newPodAffinityPriorityMap(nodes)
	// processPod()主要处理pod亲和和反亲和weight累计的逻辑代码。 
	// 这里只是定义processPod这个函数，具体调用在后面
	processPod := func(existingPod *v1.Pod) error {
		existingPodNode, err := ipa.info.GetNodeInfo(existingPod.Spec.NodeName)
		existingPodAffinity := existingPod.Spec.Affinity
		existingHasAffinityConstraints := existingPodAffinity != nil && existingPodAffinity.PodAffinity != nil
		existingHasAntiAffinityConstraints := existingPodAffinity != nil && existingPodAffinity.PodAntiAffinity != nil

		if hasAffinityConstraints {
			// For every soft pod affinity term of <pod>, if <existingPod> matches the term,
			// increment <pm.counts> for every node in the cluster with the same <term.TopologyKey>
			// value as that of <existingPods>`s node by the term`s weight.
			terms := affinity.PodAffinity.PreferredDuringSchedulingIgnoredDuringExecution
			// 亲和性检测逻辑代码
			pm.processTerms(terms, pod, existingPod, existingPodNode, 1)
		}
		if hasAntiAffinityConstraints {
			// For every soft pod anti-affinity term of <pod>, if <existingPod> matches the term,
			// decrement <pm.counts> for every node in the cluster with the same <term.TopologyKey>
			// value as that of <existingPod>`s node by the term`s weight.
			terms := affinity.PodAntiAffinity.PreferredDuringSchedulingIgnoredDuringExecution
			pm.processTerms(terms, pod, existingPod, existingPodNode, -1)
		}

		if existingHasAffinityConstraints {
			// For every hard pod affinity term of <existingPod>, if <pod> matches the term,
			// increment <pm.counts> for every node in the cluster with the same <term.TopologyKey>
			// value as that of <existingPod>'s node by the constant <ipa.hardPodAffinityWeight>
			if ipa.hardPodAffinityWeight > 0 {
				terms := existingPodAffinity.PodAffinity.RequiredDuringSchedulingIgnoredDuringExecution
				// TODO: Uncomment this block when implement RequiredDuringSchedulingRequiredDuringExecution.
				//if len(existingPodAffinity.PodAffinity.RequiredDuringSchedulingRequiredDuringExecution) != 0 {
				//	terms = append(terms, existingPodAffinity.PodAffinity.RequiredDuringSchedulingRequiredDuringExecution...)
				//}
				for _, term := range terms {
					pm.processTerm(&term, existingPod, pod, existingPodNode, float64(ipa.hardPodAffinityWeight))
				}
			}
			// For every soft pod affinity term of <existingPod>, if <pod> matches the term,
			// increment <pm.counts> for every node in the cluster with the same <term.TopologyKey>
			// value as that of <existingPod>'s node by the term's weight.
			terms := existingPodAffinity.PodAffinity.PreferredDuringSchedulingIgnoredDuringExecution
			pm.processTerms(terms, existingPod, pod, existingPodNode, 1)
		}
		if existingHasAntiAffinityConstraints {
			// For every soft pod anti-affinity term of <existingPod>, if <pod> matches the term,
			// decrement <pm.counts> for every node in the cluster with the same <term.TopologyKey>
			// value as that of <existingPod>'s node by the term's weight.
			terms := existingPodAffinity.PodAntiAffinity.PreferredDuringSchedulingIgnoredDuringExecution
			pm.processTerms(terms, existingPod, pod, existingPodNode, -1)
		}
		return nil
	}

	//ProcessNode()通过一个判断是否存在亲和性配置选择调用之前定义的processPod()
	processNode := func(i int) {
		nodeInfo := nodeNameToInfo[allNodeNames[i]]
		if nodeInfo.Node() != nil {
			if hasAffinityConstraints || hasAntiAffinityConstraints {
				// We need to process all the nodes.
				for _, existingPod := range nodeInfo.Pods() {
					// 
					if err := processPod(existingPod); err != nil {
						pm.setError(err)
					}
				}
			} else {
				// The pod doesn't have any constraints - we need to check only existing
				// ones that have some.
				for _, existingPod := range nodeInfo.PodsWithAffinity() {
					if err := processPod(existingPod); err != nil {
						pm.setError(err)
					}
				}
			}
		}
	}

	// 并发多线程处理调用ProcessNode()
	workqueue.ParallelizeUntil(context.TODO(), 16, len(allNodeNames), processNode)
	if pm.firstError != nil {
		return nil, pm.firstError
	}

	for _, node := range nodes {
		if pm.counts[node.Name] > maxCount {
			maxCount = pm.counts[node.Name]
		}
		if pm.counts[node.Name] < minCount {
			minCount = pm.counts[node.Name]
		}
	}

	// calculate final priority score for each node
	result := make(schedulerapi.HostPriorityList, 0, len(nodes))
	for _, node := range nodes {
		fScore := float64(0)
		if (maxCount - minCount) > 0 {
			fScore = float64(schedulerapi.MaxPriority) * ((pm.counts[node.Name] - minCount) / (maxCount - minCount))
		}
		result = append(result, schedulerapi.HostPriority{Host: node.Name, Score: int(fScore)})
		if klog.V(10) {
			klog.Infof("%v -> %v: InterPodAffinityPriority, Score: (%d)", pod.Name, node.Name, int(fScore))
		}
	}
	return result, nil
}

// ProcessTerms() 给定Pod和此Pod的定义的亲和性配置(podAffinityTerm)、被测目标pod、运行被测目标pod的Node信息，
// 对所有潜在可被调度的Nodes列表进行一一检测,并对根据检测结果为node进行weight累计。
func (p *podAffinityPriorityMap) processTerms(terms []v1.WeightedPodAffinityTerm, podDefiningAffinityTerm, podToCheck *v1.Pod, fixedNode *v1.Node, multiplier int) {
	for i := range terms {
		term := &terms[i]
		p.processTerm(&term.PodAffinityTerm, podDefiningAffinityTerm, podToCheck, fixedNode, float64(term.Weight*int32(multiplier)))
	}
}
