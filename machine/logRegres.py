# -*- coding: utf-8 -*
'''
Created on Oct 27, 2010
Logistic Regression Working Module
@author: Peter
'''
from numpy import *

def loadDataSet():
    dataMat = []; labelMat = []
    fr = open('testSet.txt')
    for line in fr.readlines():
        lineArr = line.strip().split()
        dataMat.append([1.0, float(lineArr[0]), float(lineArr[1])])
        labelMat.append(int(lineArr[2]))
    return dataMat,labelMat

def sigmoid(inX):
    return 1.0/(1+exp(-inX))

# 使用梯度上升找到最佳参数
def gradAscent(dataMatIn, classLabels):
	# 转换为NumPy矩阵类型
    dataMatrix = mat(dataMatIn)             #convert to NumPy matrix
	# transpose方法把矩阵的行和列进行交换，labelMat由1*N的classLabel转换成N*1矩阵
    labelMat = mat(classLabels).transpose() #convert to NumPy matrix
	# m = 100, n = 3
    m,n = shape(dataMatrix)
	# 向目标移动的学习步长
    alpha = 0.001
	# 迭代次数
    maxCycles = 500
	# 因为有样本集里有3列，即3个特征: 常数0, X1和X2；所以权重weights是一个3*1的矩阵
    weights = ones((n,1))
	# 多次循环逼近0偏差，本质就是在求导
    for k in range(maxCycles):              #heavy on matrix operations
		# dataMatrix * weights 的内积是100 * 1的矩阵
		# 现在我们要找到一个最佳的权重weights = w0*1 + w1 *X1 + w2*X2, 预测属于类别1的概率 https://www.jianshu.com/p/eb94c60015c7
		# 假设我们已经有了一个权重weghts，把样本数据集代入后得到预测结果h
        h = sigmoid(dataMatrix*weights)     #matrix mult
		
		# 计算真实label与预测label的差值
		# 假设样本1预测是0.8，样本2预测是0.4，真实label是1和0，那么偏差就是(1-0.8) + (0-0.4) = -0.2
		# 我们要取训练样本集里总偏差最小的weights矩阵
        error = (labelMat - h)              #vector subtraction

		# 按照该差值的方向调整回归系数weights
		# 梯度上升算法 w = w + 调节因子 * 回归系数，调整偏差，学习步长alpha就是每次调整的幅度
		# M行的矩阵A * N列的矩阵B = M*N的矩阵C 
        weights = weights + alpha * dataMatrix.transpose()* error #matrix mult
    return weights

# 得到回归系数weights后，画出样本集和Logistics回归最佳拟合直线
def plotBestFit(weights):
    import matplotlib.pyplot as plt
    dataMat,labelMat=loadDataSet()
    dataArr = array(dataMat)
    n = shape(dataArr)[0] 
    xcord1 = []; ycord1 = []
    xcord2 = []; ycord2 = []
    for i in range(n):
		# 将标签为1的数据元素和为0的分别放在(xcode1,ycode1)、(xcord2,ycord2)
        if int(labelMat[i])== 1:
            xcord1.append(dataArr[i,1]); ycord1.append(dataArr[i,2])
        else:
            xcord2.append(dataArr[i,1]); ycord2.append(dataArr[i,2])
    fig = plt.figure()
    ax = fig.add_subplot(111)
    ax.scatter(xcord1, ycord1, s=30, c='red', marker='s')
    ax.scatter(xcord2, ycord2, s=30, c='green')
    x = arange(-3.0, 3.0, 0.1)
	# 绘制出w0 + w1*x1 + w2*x2 = 0的直线 (0是2个类别的分界处，因为sigmoid(0) = 0.5)
	# x2就是我们画图的y值，而f(x)被我们磨合误差给算到w0,w1,w2身上去了
	# 所以： w0+w1*x+w2*y=0 => y = (-w0-w1*x)/w2
    y = (-weights[0]-weights[1]*x)/weights[2]
    ax.plot(x, y)
    plt.xlabel('X1'); plt.ylabel('X2');
    plt.show()

def stocGradAscent0(dataMatrix, classLabels):
    m,n = shape(dataMatrix)
    alpha = 0.01
    weights = ones(n)   #initialize to all ones
    for i in range(m):
        h = sigmoid(sum(dataMatrix[i]*weights))
        error = classLabels[i] - h
        weights = weights + alpha * error * dataMatrix[i]
    return weights

def stocGradAscent1(dataMatrix, classLabels, numIter=150):
    m,n = shape(dataMatrix)
    weights = ones(n)   #initialize to all ones
    for j in range(numIter):
        dataIndex = range(m)
        for i in range(m):
            alpha = 4/(1.0+j+i)+0.0001    #apha decreases with iteration, does not 
            randIndex = int(random.uniform(0,len(dataIndex)))#go to 0 because of the constant
            h = sigmoid(sum(dataMatrix[randIndex]*weights))
            error = classLabels[randIndex] - h
            weights = weights + alpha * error * dataMatrix[randIndex]
            del(dataIndex[randIndex])
    return weights

def classifyVector(inX, weights):
    prob = sigmoid(sum(inX*weights))
    if prob > 0.5: return 1.0
    else: return 0.0

def colicTest():
    frTrain = open('horseColicTraining.txt'); frTest = open('horseColicTest.txt')
    trainingSet = []; trainingLabels = []
    for line in frTrain.readlines():
        currLine = line.strip().split('\t')
        lineArr =[]
        for i in range(21):
            lineArr.append(float(currLine[i]))
        trainingSet.append(lineArr)
        trainingLabels.append(float(currLine[21]))
    trainWeights = stocGradAscent1(array(trainingSet), trainingLabels, 1000)
    errorCount = 0; numTestVec = 0.0
    for line in frTest.readlines():
        numTestVec += 1.0
        currLine = line.strip().split('\t')
        lineArr =[]
        for i in range(21):
            lineArr.append(float(currLine[i]))
        if int(classifyVector(array(lineArr), trainWeights))!= int(currLine[21]):
            errorCount += 1
    errorRate = (float(errorCount)/numTestVec)
    print "the error rate of this test is: %f" % errorRate
    return errorRate

def multiTest():
    numTests = 10; errorSum=0.0
    for k in range(numTests):
        errorSum += colicTest()
    print "after %d iterations the average error rate is: %f" % (numTests, errorSum/float(numTests))
        