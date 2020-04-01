import (
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