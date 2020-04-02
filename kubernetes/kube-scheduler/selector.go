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