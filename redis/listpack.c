unsigned char *lpNew(size_t capacity) {
	// LP_HDR_SIZE默认6个字节，4个字节是listpack总字节数，2个字节是元素数量
    unsigned char *lp = lp_malloc(capacity > LP_HDR_SIZE+1 ? capacity : LP_HDR_SIZE+1);
	// 记录总字节数
    lpSetTotalBytes(lp,LP_HDR_SIZE+1);
	// 初始化元素数量
    lpSetNumElements(lp,0);
	// 结尾标识 LP_EOF 是 255
    lp[LP_HDR_SIZE] = LP_EOF;
    return lp;
}