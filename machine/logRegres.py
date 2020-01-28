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

# 随机梯度上升算法
def stocGradAscent0(dataMatrix, classLabels):
    m,n = shape(dataMatrix)
    alpha = 0.01
    weights = ones(n)   #initialize to all ones
	# 随机梯度最大的区别是每次只迭代一个样本，每个样本只迭代一次
	# 批量梯度迭代一定的次数，每次遍历计算预测所有样本
    for i in range(m):
        h = sigmoid(sum(dataMatrix[i]*weights))
        error = classLabels[i] - h
        weights = weights + alpha * error * dataMatrix[i]
    return weights

# 在全量梯度的精度和随机梯度的性能上的均衡算法
def stocGradAscent1(dataMatrix, classLabels, numIter=150):
    m,n = shape(dataMatrix)
    weights = ones(n)   #initialize to all ones
    for j in range(numIter):
        dataIndex = range(m)
        for i in range(m):
			# 步长每次迭代后需要调整减小，缓解数据波动，越到后面，步长越小，避免跨过最优解；
			# 小步长会让优化问题收敛，大步长会让优化问题发散
			# 随机梯度算法因为每次只计算一个样本，这个样本随机选择的不一定是符合大部分情况的好样本，前进的方向就会不对，导致回归系数收敛偏差的过程中产生波动
			# 但是最终的收敛方向和批量梯度算法是一致的，毕竟大部分样本都是好样本
            alpha = 4/(1.0+j+i)+0.0001    #apha decreases with iteration, does not 
            randIndex = int(random.uniform(0,len(dataIndex)))#go to 0 because of the constant
			# 随机选择一个样本
            h = sigmoid(sum(dataMatrix[randIndex]*weights))
            error = classLabels[randIndex] - h
            weights = weights + alpha * error * dataMatrix[randIndex]
            del(dataIndex[randIndex])
    return weights
        