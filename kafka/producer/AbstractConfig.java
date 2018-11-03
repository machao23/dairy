public class AbstractConfig {
	public AbstractConfig(ConfigDef definition, Map<?, ?> originals, boolean doLog) {
		// 这里的origin就是producer在new的时候传入的props键值对
        this.originals = (Map<String, ?>) originals;
		// 应该是合并了自定义的props和producer缺省的配置
        this.values = definition.parse(this.originals);
    }

	// AbstractConfig的核心方法，通过反射实例化originals字段中指定的类
	public <T> T getConfiguredInstance(String key, Class<T> t) {
		// 从合并总配置项values里获取指定key约定的class类型
		// 例如key = partitioner.class, value = DefaultPartitioner.class
        Class<?> c = getClass(key);
		// 无论是否Configurable对象，都是统一通过无参构造函数实例化
        Object o = Utils.newInstance(c);
        if (o instanceof Configurable)
			// Configurable接口的实例，单独调用约定的configure方法，传入originals键值对做初始化工作
			// 就可以在统一无参构造实例化的前提下，通过键值对入参达到个性化初始化
            ((Configurable) o).configure(this.originals);
		// 这里的t是显示要求的范式类型T,最后会做强转返回
        return t.cast(o);
    }
}
