# -*- coding: utf-8 -*
'''
Created on Oct 19, 2010

@author: Peter
'''
from numpy import *

def loadDataSet():
    postingList=[['my', 'dog', 'has', 'flea', 'problems', 'help', 'please'],
                 ['maybe', 'not', 'take', 'him', 'to', 'dog', 'park', 'stupid'],
                 ['my', 'dalmation', 'is', 'so', 'cute', 'I', 'love', 'him'],
                 ['stop', 'posting', 'stupid', 'worthless', 'garbage'],
                 ['mr', 'licks', 'ate', 'my', 'steak', 'how', 'to', 'stop', 'him'],
                 ['quit', 'buying', 'worthless', 'dog', 'food', 'stupid']]
    classVec = [0,1,0,1,0,1]    #1 is abusive, 0 not
    return postingList,classVec

# 创建一个包含在所有文档中出现的不重复词的词汇列表                 
def createVocabList(dataSet):
    vocabSet = set([])  #create empty set
    for document in dataSet:
        vocabSet = vocabSet | set(document) #union of the two sets
    return list(vocabSet)

# 统计vocabList里每个单词在inputSet的出现次数: [0,0,1,...0]
# 将一组单词转换为一组数字
def setOfWords2Vec(vocabList, inputSet):
    returnVec = [0]*len(vocabList)
    for word in inputSet:
        if word in vocabList:
            returnVec[vocabList.index(word)] = 1
        else: print "the word: %s is not in my Vocabulary!" % word
    return returnVec

# 朴素贝叶斯分类器训练函数，训练就是拿已知的特征集和已知的结果，构建模型
# trainMatrix 每个文档在单词表的技术矩阵的集合: [[0,1,...0], [1,0,...0] ...]
# trainCategory 应该是每篇文档是否包含敏感词，集合的length和trainMatrix一样
def trainNB0(trainMatrix,trainCategory):
	# 文档总数
    numTrainDocs = len(trainMatrix)
	# 词汇表的单词数，32个
    numWords = len(trainMatrix[0])
	# sum即包含敏感词的文档个数是3，因为不包含是0，包含是1
	# pAbusive就是所有文档里包含敏感词的文档的概率: 3/6 = 0.5
    pAbusive = sum(trainCategory)/float(numTrainDocs)
	# 防止因为一个概率是0，导致乘积结果变成0，所以分子初始化为1
    p0Num = ones(numWords); p1Num = ones(numWords)
    # 分子初始化为1，所以分母初始化为2
    p0Denom = 2.0; p1Denom = 2.0
    for i in range(numTrainDocs):
        if trainCategory[i] == 1:
			# 单词表计数矩阵各个向量累加
            p1Num += trainMatrix[i]
			# 单词数量
            p1Denom += sum(trainMatrix[i])
        else:
            p0Num += trainMatrix[i]
            p0Denom += sum(trainMatrix[i])
	# 使用矩阵方式快速计算每个单词的概率
	# p1Vect: 词汇表中每个单词在包含敏感词文档所有单词里出现的概率
	# 大部分因子都非常小，因子相乘会导致下溢出或精度缺失，取自然对数
    p1Vect = log(p1Num/p1Denom)
	# p0Vect: 词汇表中每个单词在正常文档所有单词里出现的概率
    p0Vect = log(p0Num/p0Denom)
    return p0Vect,p1Vect,pAbusive

# 朴素贝叶斯分类函数：所谓的分类就是预测结果
def classifyNB(vec2Classify, p0Vec, p1Vec, pClass1):
	# N个独立特征同时出现的概率 = 特征1 * 特征2 ... * 特征N
	# 计算 P(f|c) * P(c)
	# logA + logB = log(A*B)
    p1 = sum(vec2Classify * p1Vec) + log(pClass1)    #element-wise mult
    p0 = sum(vec2Classify * p0Vec) + log(1.0 - pClass1)
	# P(c|f) =  P(f|c) * P(c) / P(f), 因为都要除于P(f)，所以就直接比较 P(f|c) * P(c) 就行了
	# 2个结果比较，哪个概率大用哪个
    if p1 > p0:
        return 1
    else: 
        return 0
    
def bagOfWords2VecMN(vocabList, inputSet):
    returnVec = [0]*len(vocabList)
    for word in inputSet:
        if word in vocabList:
            returnVec[vocabList.index(word)] += 1
    return returnVec

def testingNB():
    listOPosts,listClasses = loadDataSet()
    myVocabList = createVocabList(listOPosts)
    trainMat=[]
    for postinDoc in listOPosts:
        trainMat.append(setOfWords2Vec(myVocabList, postinDoc))
    p0V,p1V,pAb = trainNB0(array(trainMat),array(listClasses))
    testEntry = ['love', 'my', 'dalmation']
    thisDoc = array(setOfWords2Vec(myVocabList, testEntry))
    print testEntry,'classified as: ',classifyNB(thisDoc,p0V,p1V,pAb)
    testEntry = ['stupid', 'garbage']
    thisDoc = array(setOfWords2Vec(myVocabList, testEntry))
    print testEntry,'classified as: ',classifyNB(thisDoc,p0V,p1V,pAb)

def textParse(bigString):    #input is big string, #output is word list
    import re
    listOfTokens = re.split(r'\W*', bigString)
    return [tok.lower() for tok in listOfTokens if len(tok) > 2] 
    
def spamTest():
    docList=[]; classList = []; fullText =[]
    for i in range(1,26):
        wordList = textParse(open('email/spam/%d.txt' % i).read())
		# 总词汇集合
        docList.append(wordList)
        fullText.extend(wordList)
		# 文档对应的类别
        classList.append(1)
        wordList = textParse(open('email/ham/%d.txt' % i).read())
        docList.append(wordList)
        fullText.extend(wordList)
        classList.append(0)
    vocabList = createVocabList(docList)#create vocabulary
	# 垃圾和正常邮件各25个，总计50个
    trainingSet = range(50); testSet=[]           #create test set
	# 随机从训练样本集里挑选10个邮件，作为测试样本集
    for i in range(10):
        randIndex = int(random.uniform(0,len(trainingSet)))
        testSet.append(trainingSet[randIndex])
        del(trainingSet[randIndex])  
    trainMat=[]; trainClasses = []
    for docIndex in trainingSet:#train the classifier (get probs) trainNB0
        trainMat.append(bagOfWords2VecMN(vocabList, docList[docIndex]))
        trainClasses.append(classList[docIndex])
    p0V,p1V,pSpam = trainNB0(array(trainMat),array(trainClasses))
    errorCount = 0
    for docIndex in testSet:        #classify the remaining items
        wordVector = bagOfWords2VecMN(vocabList, docList[docIndex])
        if classifyNB(array(wordVector),p0V,p1V,pSpam) != classList[docIndex]:
            errorCount += 1
            print "classification error",docList[docIndex]
    print 'the error rate is: ',float(errorCount)/len(testSet)
