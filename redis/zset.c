/* ZSETs use a specialized version of Skiplists */
typedef struct zskiplistNode {
    sds ele; // Sorted Set中的元素
    double score; // 元素权重值，排序用
    struct zskiplistNode *backward; 
	// 节点所在所有level数组，每个成员结构体zskipistLevel 对应了跳表的每一层
    struct zskiplistLevel {
		// 指向指定level层的后一个节点
		// 跳表里的节点是按权重排序的，所以后续节点的权重会越来越大
		// 先从最高层（节点数最少层）开始找，当查询目标比当前节点大，就继续往后找
		// 要找比当前节点权重小的，应该是跳到下一层level
        struct zskiplistNode *forward; 
        unsigned long span; // 指定level层和后续节点之间跨越了level0上节点数
    } level[];
} zskiplistNode;