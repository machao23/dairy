/*
 * 保存字符串对象的结构
 */
struct sdshdr {
    
    // buf 中已占用空间的长度 
	// 空间换时间，O(1)获取字符串长度
	// 规避C语言缺少动态分配内存的问题，出现内存溢出BUG
	// 规避C语言判断字符串按终止符的规则，redis的字符串可以保存二进制内存，所以按实际长度判断是否读取结束
    int len;

    // buf 中剩余可用空间的长度
	// 空间换时间，规避C语言缺少动态分配内存，导致不必要的内存缩容，保留预留空间避免内存重复分配计算
    int free;

    // 数据空间
    char buf[];
};

///////////////////////////////////////////////////////////

// 双向链表
typedef struct listNode {
    struct listNode *prev;
    struct listNode *next;
    void *value;
} listNode;

typedef struct list {

    // 表头节点
    listNode *head;

    // 表尾节点
    listNode *tail;

    // 节点值复制函数
    void *(*dup)(void *ptr);

    // 节点值释放函数
    void (*free)(void *ptr);

    // 节点值对比函数
    int (*match)(void *ptr, void *key);

    // 链表所包含的节点数量
    unsigned long len;

} list;

/////////////////////////////////////////////////////////

typedef struct dictht {
    
    // 哈希表数组
    dictEntry **table;

    // 哈希表大小
    unsigned long size;
    
    // 哈希表大小掩码，用于计算索引值
    // 总是等于 size - 1, 决定一个key应该存放在table的哪个位置
    unsigned long sizemask;

    // 该哈希表已有节点的数量
    unsigned long used;

} dictht;

/*
 * 哈希表节点
 */
typedef struct dictEntry {
    
    // 键
    void *key;

    // 值
    union {
        void *val;
        uint64_t u64;
        int64_t s64;
    } v;

    // 指向下个哈希表节点，形成链表
    struct dictEntry *next;

} dictEntry;