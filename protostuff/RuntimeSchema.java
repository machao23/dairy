public final class RuntimeSchema<T> extends MappedSchema<T> {
	// 遍历指定class下所有的字段
	static void fill(Map<String,java.lang.reflect.Field> fieldMap, Class<?> typeClass)
    {
		// 先处理父类
        if(Object.class!=typeClass.getSuperclass())
            fill(fieldMap, typeClass.getSuperclass());
        // getDeclaredFields遍历所有类的字段
        for(java.lang.reflect.Field f : typeClass.getDeclaredFields())
        {
            int mod = f.getModifiers();
			// fieldMap是LinkedHashMap，保证写入顺序
            if(!Modifier.isStatic(mod) && !Modifier.isTransient(mod))
                fieldMap.put(f.getName(), f);
        }
    }
}