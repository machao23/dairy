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

// 通过Unsafe方式实例化
public final class RuntimeUnsafeFieldFactory {
	public static final RuntimeFieldFactory<String> STRING = new RuntimeFieldFactory<String>(ID_STRING)
    {
        public <T> Field<T> create(int number, java.lang.String name, 
                final java.lang.reflect.Field f, IdStrategy strategy)
        {
			// 使用Unsafe对Field取值和赋值，不依赖具体的Getter和Setter
            final long offset = us.objectFieldOffset(f);
            return new Field<T>(FieldType.STRING, number, name, 
                    f.getAnnotation(Tag.class))
            {                  
                public void mergeFrom(Input input, T message) throws IOException
                {
                    us.putObject(message, offset, input.readString());
                }
                public void writeTo(Output output, T message) throws IOException
                {
					// Unsafe方式取值
                    String value = (String)us.getObject(message, offset);
                    if(value!=null)
                        output.writeString(number, value, false);
                }
                public void transfer(Pipe pipe, Input input, Output output, 
                        boolean repeated) throws IOException
                {
                    input.transferByteRangeTo(output, true, number, repeated);
                }
            };
        }
    };
}

public final class ProtostuffOutput extends WriteSession implements Output {
	// 使用PB方式序列化string类型字段值
	public void writeString(int fieldNumber, String value, boolean repeated) throws IOException
    {
        tail = sink.writeStrUTF8VarDelimited(
                value, 
                this, 
                sink.writeVarInt32(
                        makeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED), 
                        this, 
                        tail));
	}
}