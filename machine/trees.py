# -*- coding: utf-8 -*
'''
Created on Oct 12, 2010
Decision Tree Source Code for Machine Learning in Action Ch. 3
@author: Peter Harrington
'''
from math import log
import operator

def createDataSet():
    dataSet = [[1, 1, 'yes'],
               [1, 1, 'yes'],
               [1, 0, 'no'],
               [0, 1, 'no'],
               [0, 1, 'no']]
    labels = ['no surfacing','flippers']
    #change to discrete values
    return dataSet, labels

# 计算样本集合的香农熵
def calcShannonEnt(dataSet):
    numEntries = len(dataSet)
    labelCounts = {}
	# 对分类分组计数
    for featVec in dataSet: #the the number of unique elements and their occurance
        currentLabel = featVec[-1]
        if currentLabel not in labelCounts.keys(): labelCounts[currentLabel] = 0
        labelCounts[currentLabel] += 1
    shannonEnt = 0.0
    for key in labelCounts:
		# prob就是某个分类占总量的比例，即发生的概率
        prob = float(labelCounts[key])/numEntries
		# 以2为底求对数获取，然后累加
		# 得到所有类别所有可能值包含的信息期望值，即香农熵
        shannonEnt -= prob * log(prob,2) #log base 2
    return shannonEnt

# dataSet: 待划分的数据集
# axis: 划分数据集的特征下标，即第几个特征
# 提取 axis下标的feature值 == value 的样本，并且样本集结果里去掉了该下标feature
def splitDataSet(dataSet, axis, value):
    retDataSet = []
    for featVec in dataSet:
		# 只有指定特征 == 指定的特征值，才返回
        if featVec[axis] == value:
			# 这里是去掉样本里的指定特征:
			# 先截取从开头到指定样本下标前之间的片段1
            reducedFeatVec = featVec[:axis]     #chop out axis used for splitting
			# 再截取从指定样本下标后到末尾之间的片段2，把片段1和片段2
            reducedFeatVec.extend(featVec[axis+1:])
            retDataSet.append(reducedFeatVec)
    return retDataSet
    
# 选择最好的数据集划分方式
def chooseBestFeatureToSplit(dataSet):
	# 所有特征的种数
    numFeatures = len(dataSet[0]) - 1      #the last column is used for the labels
	# 这个是最原始的未划分的基础香农熵，用来比较计算收益的
    baseEntropy = calcShannonEnt(dataSet)
    bestInfoGain = 0.0; bestFeature = -1
	# 穷尽遍历所有特征
    for i in range(numFeatures):        #iterate over all the features
		# featList: 获取这个特征的值集合
        featList = [example[i] for example in dataSet]#create a list of all the examples of this feature
		# 去重
        uniqueVals = set(featList)       #get a set of unique values
        newEntropy = 0.0
		# 穷尽遍历这个特征的所有值划分集合，计算每次
        for value in uniqueVals:
            subDataSet = splitDataSet(dataSet, i, value)
            prob = len(subDataSet)/float(len(dataSet))
            newEntropy += prob * calcShannonEnt(subDataSet)     
		# 计算这次划分前后的收益
        infoGain = baseEntropy - newEntropy     #calculate the info gain; ie reduction in entropy
        if (infoGain > bestInfoGain):       #compare this to the best gain so far
            bestInfoGain = infoGain         #if better than current best, set to best
			# 记录最佳收益划分的特征
            bestFeature = i
    return bestFeature                      #returns an integer

# 如果穷举了所有feature后，分组的结果里label不是唯一的，通常采用多数表决决定叶子节点属于哪一个label
def majorityCnt(classList):
    classCount={}
    for vote in classList:
        if vote not in classCount.keys(): classCount[vote] = 0
        classCount[vote] += 1
    sortedClassCount = sorted(classCount.iteritems(), key=operator.itemgetter(1), reverse=True)
    return sortedClassCount[0][0]

# 创建决策树
# labels就是每个特征下标对应的字面意思
def createTree(dataSet,labels):
    classList = [example[-1] for example in dataSet]
	# 分组后每组的label只有一个，停止分组
    if classList.count(classList[0]) == len(classList): 
        return classList[0]#stop splitting when all of the classes are equal
	# 遍历完所有feature，停止分组
    if len(dataSet[0]) == 1: #stop splitting when there are no more features in dataSet
        return majorityCnt(classList)
    bestFeat = chooseBestFeatureToSplit(dataSet)
    bestFeatLabel = labels[bestFeat]
    myTree = {bestFeatLabel:{}}
    del(labels[bestFeat])
    featValues = [example[bestFeat] for example in dataSet]
    uniqueVals = set(featValues)
    for value in uniqueVals:
        subLabels = labels[:]       #copy all of labels, so trees don't mess up existing labels
		# 递归对剩余的feature分组
        myTree[bestFeatLabel][value] = createTree(splitDataSet(dataSet, bestFeat, value),subLabels)
    return myTree                            

# 传入要预测的样本testVec, 调用决策树inputTree，labels是特征的字面意思映射，获取分类结果    
def classify(inputTree,featLabels,testVec):
	# firstStr是root feature
    firstStr = inputTree.keys()[0]
    secondDict = inputTree[firstStr]
    featIndex = featLabels.index(firstStr)
	# 获取测试样本里root feature对应的值
    key = testVec[featIndex]
    valueOfFeat = secondDict[key]
    if isinstance(valueOfFeat, dict): 
        classLabel = classify(valueOfFeat, featLabels, testVec)
    else: classLabel = valueOfFeat
    return classLabel
