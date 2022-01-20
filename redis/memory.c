/* Create a string object with EMBSTR encoding if it is smaller than
 * OBJ_ENCODING_EMBSTR_SIZE_LIMIT, otherwise the RAW encoding is
 * used.
 * 小于44字节的字符串使用嵌入式，否则用RAW字符串
 * The current limit of 44 is chosen so that the biggest string object
 * we allocate as EMBSTR will still fit into the 64 byte arena of jemalloc. 
 * 入参 ptr是字符串内容，len是字符串长度
 * */
#define OBJ_ENCODING_EMBSTR_SIZE_LIMIT 44
robj *createStringObject(const char *ptr, size_t len) {
    if (len <= OBJ_ENCODING_EMBSTR_SIZE_LIMIT)
        return createEmbeddedStringObject(ptr,len);
    else
        return createRawStringObject(ptr,len);
}

/* Create a string object with encoding OBJ_ENCODING_RAW, that is a plain
 * string object where o->ptr points to a proper sds string. 
 * 创建 RAW 字符串，指向SDS指针，sdsnewlen创建一个SDS结构体
 * 这里做了2次内存分配，一次是 sdsnewlen分配sds结构体，还有一次就是createObject分配 redisObject内存
 */
robj *createRawStringObject(const char *ptr, size_t len) {
    return createObject(OBJ_STRING, sdsnewlen(ptr,len));
}

/* ===================== Creation and parsing of objects ==================== */

robj *createObject(int type, void *ptr) {
    // 分配 redisObject实例o 的内存
    robj *o = zmalloc(sizeof(*o));
    o->type = type;
    o->encoding = OBJ_ENCODING_RAW;
    // raw string的ptr就是指向sds的指针
    o->ptr = ptr;
    o->refcount = 1;

    return o;
}

/* Create a string object with encoding OBJ_ENCODING_EMBSTR, that is
 * an object where the sds string is actually an unmodifiable string
 * allocated in the same chunk as the object itself. */
robj *createEmbeddedStringObject(const char *ptr, size_t len) {
    // 分配内存大小：redisObject + sdshdr8 + 字符串长度Len + 字符串终止符1(?)
    robj *o = zmalloc(sizeof(robj)+sizeof(struct sdshdr8)+len+1);
    // 指针sh 指向sdshdr8在实例o里所在的位置, o+1 表示移动redisObject大小的距离
    struct sdshdr8 *sh = (void*)(o+1);

    o->type = OBJ_STRING;
    o->encoding = OBJ_ENCODING_EMBSTR;
    // 指针ptr 指向字符数组在实例o里所在的位置, sh+1 表示移动sdshdr8大小的距离
    o->ptr = sh+1;
    o->refcount = 1;

    sh->len = len;
    sh->alloc = len;
    sh->flags = SDS_TYPE_8;
    if (ptr == SDS_NOINIT)
        sh->buf[len] = '\0';
    else if (ptr) {
        // 字符串入参ptr的内容拷贝到sds的字符数组buf里面
        memcpy(sh->buf,ptr,len);
        sh->buf[len] = '\0';
    } else {
        memset(sh->buf,0,len+1);
    }
    return o;
}

/* Encode the length of the previous entry and write it to "p". Return the
 * number of bytes needed to encode this length if "p" is NULL. 
 对上一个链表节点entry的长度编码写入变量p
 */
unsigned int zipStorePrevEntryLength(unsigned char *p, unsigned int len) {
    if (len < ZIP_BIG_PREVLEN) {
        p[0] = len;
        // 小于254字节，长度p变量就使用1个字节
        return 1;
    } else {
        return zipStorePrevEntryLengthLarge(p,len);
    }
}

/* Encode the length of the previous entry and write it to "p". This only
 * uses the larger encoding (required in __ziplistCascadeUpdate). 
 上一个节点entry长度超过254的字节的长度编码
 */
int zipStorePrevEntryLengthLarge(unsigned char *p, unsigned int len) {
    uint32_t u32;
    if (p != NULL) {
        p[0] = ZIP_BIG_PREVLEN;
        // 上一个实体长度超过254字节，p[0]就是254,表示一个字节无法表示长度
        // 然后把长度len拷贝到p的第2~5个字节，第1个字节不能用作实际长度了，为了和小字符串的长度值分开表示
        u32 = len;
        memcpy(p+1,&u32,sizeof(u32));
        memrev32ifbe(p+1);
    }
    return 1 + sizeof(uint32_t);
}