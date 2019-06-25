from math import log
# 初始化测试样本数据，2个特征+结果
def createDataSet():
    dataSet = [[1, 1, 'yes'],
               [1, 1, 'yes'],
               [1, 0, 'no'],
               [0, 1, 'no'],
               [0, 1, 'no']]
    labels = ['no surfacing','flippers']
    #change to discrete values
    return dataSet, labels

# 计算每组样本的香农熵，即样本的纯度
def calcShannonEnt(dataSet):
    numEntries = len(dataSet)
    labelCounts = {}
    for featVec in dataSet: #the the number of unique elements and their occurance
        currentLabel = featVec[-1]
        if currentLabel not in labelCounts.keys(): labelCounts[currentLabel] = 0
        labelCounts[currentLabel] += 1
    shannonEnt = 0.0
    for key in labelCounts:
        prob = float(labelCounts[key])/numEntries
        shannonEnt -= prob * log(prob,2) #log base 2
    return shannonEnt

# 把样本集dataSet按照指定特征的下标axis，筛选出指定值value的子数据集
def splitDataSet(dataSet, axis, value):
    retDataSet = []
    for featVec in dataSet:
        if featVec[axis] == value:
            reducedFeatVec = featVec[:axis]     #chop out axis used for splitting
            reducedFeatVec.extend(featVec[axis+1:])
            retDataSet.append(reducedFeatVec)
    return retDataSet

def chooseBestFeatureToSplit(dataSet):
	# 特征数量，按特征维度轮询计算熵
    numFeatures = len(dataSet[0]) - 1      #the last column is used for the labels
    baseEntropy = calcShannonEnt(dataSet)
    bestInfoGain = 0.0; bestFeature = -1
    for i in range(numFeatures):        #iterate over all the features
        featList = [example[i] for example in dataSet]#create a list of all the examples of this feature
		# 获取某个特征下所有的枚举值
        uniqueVals = set(featList)       #get a set of unique values
        newEntropy = 0.0
		# 遍历枚举值，按枚举值对数据集做分组，计算累计熵
        for value in uniqueVals:
            subDataSet = splitDataSet(dataSet, i, value)
            prob = len(subDataSet)/float(len(dataSet))
            newEntropy += prob * calcShannonEnt(subDataSet)
        infoGain = baseEntropy - newEntropy     #calculate the info gain; ie reduction in entropy
        if (infoGain > bestInfoGain):       #compare this to the best gain so far
            bestInfoGain = infoGain         #if better than current best, set to best
            bestFeature = i
	# 返回按哪个特征分组能获得最优熵的结果
    return bestFeature                      #returns an integer

# 所有特征都无法确定分类情况下，使用投票决策
def majorityCnt(classList):
    classCount={}
    for vote in classList:
        if vote not in classCount.keys(): classCount[vote] = 0
        classCount[vote] += 1
    sortedClassCount = sorted(classCount.iteritems(), key=operator.itemgetter(1), reverse=True)
    return sortedClassCount[0][0]

# 创建决策树
def createTree(dataSet,labels):
    # classList就是所有的分类结果集合
    classList = [example[-1] for example in dataSet]
    # 如果遍历到了具体一个分类结果，就直接返回
    if classList.count(classList[0]) == len(classList):
        return classList[0]#stop splitting when all of the classes are equal
    # 所有特征都不能决定出唯一的分类结果，就使用投票策略
    if len(dataSet[0]) == 1: #stop splitting when there are no more features in dataSet
        return majorityCnt(classList)
	# 根据香农熵找到最佳切分的起始特征
    bestFeat = chooseBestFeatureToSplit(dataSet)
    # 根据下标找到特征的描述标签
    bestFeatLabel = labels[bestFeat]
    # 该特征作为树的节点
    myTree = {bestFeatLabel:{}}
	# 删除该特征，继续从其他特征决策树枝
    del(labels[bestFeat])
	# 列出最优熵的特征的枚举值
    featValues = [example[bestFeat] for example in dataSet]
    uniqueVals = set(featValues)
	# 根据枚举值开始创建该特征节点下的树枝或树叶
    for value in uniqueVals:
        subLabels = labels[:]       #copy all of labels, so trees don't mess up existing labels
        myTree[bestFeatLabel][value] = createTree(splitDataSet(dataSet, bestFeat, value),subLabels)
    return myTree

if __name__ == "__main__":
    myDat,labels=createDataSet()
    myTree=createTree(myDat,labels)
    print("myTree",myTree)