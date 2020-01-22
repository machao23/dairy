# -*- coding: utf-8 -*
'''
Created on Sep 16, 2010
kNN: k Nearest Neighbors

Input:      inX: vector to compare to existing dataset (1xN)
            dataSet: size m data set of known vectors (NxM)
            labels: data set labels (1xM vector)
            k: number of neighbors to use for comparison (should be an odd number)
            
Output:     the most popular class label

@author: pbharrin
'''
from numpy import *
import operator
from os import listdir

# inX: 待分类的当前点
# dataSet: 已知类别的训练样本集
# labels: 已知类别的标签集
# k：选择距离最近点的个数
def classify0(inX, dataSet, labels, k):
	# shape返回各个维度的个数，group.shape 返回 (4,2)，即4行2列
    dataSetSize = dataSet.shape[0] # 4
	# 以下3行距离计算
	# 使用欧式距离公式，计算已知类别数据集中的所有点与当前点之间的距离
	# tile函数将变量内容复制成输入矩阵同样大小的矩阵
	# 假设待分类的点坐标是[0,0] tile([0,0], (4,1)) = array([[0,0],[0,0],[0,0],[0,0]])
	# 这样就和训练样本集的维度一致,可以做减法计算距离了
	# diffMat = [[0-1, 0-1.1], [0-1, 0-1], [0-0, 0-0], [0-0, 0-1]] = [[-1,-1.1], [-1,-1], [0,0], [0,-0.1]]
    diffMat = tile(inX, (dataSetSize,1)) - dataSet
	# 平方计算拿到正数: [[1,1.21], [1,1], [0,0], [0,0.01]]
    sqDiffMat = diffMat**2
	# 求和成1列 [2.21, 2, 0, 0.01]
    sqDistances = sqDiffMat.sum(axis=1)
	# 对平方值开根号 [1.4866, 1.414, 0, 0.1]
    distances = sqDistances**0.5
	# 按照距离由小到大排序下标,即 [2,3,1,0] 
    sortedDistIndicies = distances.argsort()     
    classCount={}        
	# 选取与当前点距离最小的k个点
    for i in range(k):
		# 最近的点对应的类别
        voteIlabel = labels[sortedDistIndicies[i]]
		# classCount 是每个类别在k个点里出现的次数
        classCount[voteIlabel] = classCount.get(voteIlabel,0) + 1
	# 返回前k个点出现频率最高的类别，作为当前点的预测分类
	# 此处的排序从大到小的逆序排序, 即B, A 
    sortedClassCount = sorted(classCount.iteritems(), key=operator.itemgetter(1), reverse=True)
	# 返回发生频率最高的标签,B
    return sortedClassCount[0][0]

# 创建已知类别的数据集
# 一共4组数据，每组数据有2个我们已知的特征值
def createDataSet():
    group = array([[1.0,1.1],[1.0,1.0],[0,0],[0,0.1]])
    labels = ['A','A','B','B']
    return group, labels

def file2matrix(filename):
    love_dictionary={'largeDoses':3, 'smallDoses':2, 'didntLike':1}
    fr = open(filename)
    arrayOLines = fr.readlines()
    numberOfLines = len(arrayOLines)            # 文件行数
	# 创建返回的矩阵, 例如2行文件就是 [[0,0],[0,0],[0,0]]
    returnMat = zeros((numberOfLines,3))        
    classLabelVector = []                       # 类别结果
    index = 0
    for line in arrayOLines:
        line = line.strip() # 删除换行符
        listFromLine = line.split('\t') # 按\t分隔符切分每行
        returnMat[index,:] = listFromLine[0:3] # 截取前3个元素
        if(listFromLine[-1].isdigit()):
            classLabelVector.append(int(listFromLine[-1]))
        else:
            classLabelVector.append(love_dictionary.get(listFromLine[-1]))
        index += 1
	# returnMat即每行的前3个特征: [[40920, 8.3269, 0.9539] ... ]
	# classLabelVector 即每行的最后一个元素，即已知的类别: [3,2,1 ... ]
    return returnMat,classLabelVector

# 归一化特征值: new = (old - min) / (max - min)    
def autoNorm(dataSet):
	# min和value分别是每列的最小值和最大值
	# min: [0, 0, 0.001156]
    minVals = dataSet.min(0)
    maxVals = dataSet.max(0)
    ranges = maxVals - minVals
	# shape(dataSet) 返回矩阵的行数和个数，即(1000,3)
	# normDataSet 就是个全0的和dataSet相同的矩阵
    normDataSet = zeros(shape(dataSet))
    m = dataSet.shape[0]
	# minVals因为列数也是3，所以只需要tile复制m行得到的矩阵就和dataSet一样
	# 分子: old - min
    normDataSet = dataSet - tile(minVals, (m,1))
    normDataSet = normDataSet/tile(ranges, (m,1))   #element wise divide
    return normDataSet, ranges, minVals
   
def datingClassTest():
	# 10%的数据作为验证
    hoRatio = 0.10      #hold out 10%
	# 文件中读取数据转成矩阵
    datingDataMat,datingLabels = file2matrix('datingTestSet2.txt')       #load data setfrom file
	# 归一化特征值
    normMat, ranges, minVals = autoNorm(datingDataMat)
    m = normMat.shape[0]
	# 待验证的数据量：1000*0.1 = 100
    numTestVecs = int(m*hoRatio)
    errorCount = 0.0
    for i in range(numTestVecs):
		# 执行函数classify0，根据后900行的样本，使用kNN算法轮询计算前100行的特征值
        classifierResult = classify0(normMat[i,:],normMat[numTestVecs:m,:],datingLabels[numTestVecs:m],3)
        print "the classifier came back with: %d, the real answer is: %d" % (classifierResult, datingLabels[i])
		# 拿计算的结果和已知结果比较
        if (classifierResult != datingLabels[i]): errorCount += 1.0
    print "the total error rate is: %f" % (errorCount/float(numTestVecs))
    print errorCount
    
def classifyPerson():
    resultList = ['not at all', 'in small doses', 'in large doses']
    percentTats = float(raw_input(\
                                  "percentage of time spent playing video games?"))
    ffMiles = float(raw_input("frequent flier miles earned per year?"))
    iceCream = float(raw_input("liters of ice cream consumed per year?"))
    datingDataMat, datingLabels = file2matrix('datingTestSet2.txt')
    normMat, ranges, minVals = autoNorm(datingDataMat)
    inArr = array([ffMiles, percentTats, iceCream, ])
    classifierResult = classify0((inArr - \
                                  minVals)/ranges, normMat, datingLabels, 3)
    print "You will probably like this person: %s" % resultList[classifierResult - 1]
    
def img2vector(filename):
    returnVect = zeros((1,1024))
    fr = open(filename)
    for i in range(32):
        lineStr = fr.readline()
        for j in range(32):
            returnVect[0,32*i+j] = int(lineStr[j])
    return returnVect

def handwritingClassTest():
    hwLabels = []
    trainingFileList = listdir('trainingDigits')           #load the training set
    m = len(trainingFileList)
    trainingMat = zeros((m,1024))
    for i in range(m):
        fileNameStr = trainingFileList[i]
        fileStr = fileNameStr.split('.')[0]     #take off .txt
        classNumStr = int(fileStr.split('_')[0])
        hwLabels.append(classNumStr)
        trainingMat[i,:] = img2vector('trainingDigits/%s' % fileNameStr)
    testFileList = listdir('testDigits')        #iterate through the test set
    errorCount = 0.0
    mTest = len(testFileList)
    for i in range(mTest):
        fileNameStr = testFileList[i]
        fileStr = fileNameStr.split('.')[0]     #take off .txt
        classNumStr = int(fileStr.split('_')[0])
        vectorUnderTest = img2vector('testDigits/%s' % fileNameStr)
        classifierResult = classify0(vectorUnderTest, trainingMat, hwLabels, 3)
        print "the classifier came back with: %d, the real answer is: %d" % (classifierResult, classNumStr)
        if (classifierResult != classNumStr): errorCount += 1.0
    print "\nthe total number of errors is: %d" % errorCount
    print "\nthe total error rate is: %f" % (errorCount/float(mTest))
