package org.example.ClassDemo1.反射;

import java.lang.reflect.Field;

public class Demo2 {
    public static void main(String[]args) throws NoSuchFieldException, IllegalAccessException {
       Class<?>clazz=Student.class;

        Field name = clazz.getDeclaredField("name");
        Student student=new Student();
        name.setAccessible(true);
        name.set(student,"张三");
        Object object = name.get(student);
        System.out.println(object);
        System.out.println(student.getName());
    }
}
/*
一、获取 Field 对象的方法
要操作类的字段，首先得获取对应的Field对象。Class类提供了多种获取Field的方法：

获取公共字段（包括继承的）

java
Field field = clazz.getField("fieldName");

作用：获取类中名为fieldName的公共字段（包括从父类继承的）
异常：若字段不存在或不可访问，会抛出NoSuchFieldException

        获取所有公共字段

java
Field[] fields = clazz.getFields();

作用：返回类中所有公共字段（包括从父类继承的）

获取声明的字段（不包括继承的）

java
Field field = clazz.getDeclaredField("fieldName");

作用：获取类自身声明的字段（无论访问权限，不包括继承的）
异常：字段不存在时抛出NoSuchFieldException

        获取所有声明的字段

java
Field[] fields = clazz.getDeclaredFields();

作用：返回类自身声明的所有字段（无论访问权限，不包括继承的）
二、Field 类的常用方法
获取Field对象后，就能使用以下方法对字段进行操作：
        1. 获取字段的基本信息
        java
Field field = clazz.getDeclaredField("age");

// 获取字段名称
String name = field.getName(); // 输出: "age"

// 获取字段类型（返回Class对象）
Class<?> type = field.getType(); // 对于int类型，返回int.class

// 获取字段的修饰符（返回int，需用Modifier类解析）
int modifiers = field.getModifiers();
boolean isPublic = Modifier.isPublic(modifiers);
boolean isStatic = Modifier.isStatic(modifiers);
2. 访问和修改字段值
        java
// 创建对象实例
Person person = new Person("Alice", 20);
Field field = Person.class.getDeclaredField("age");

// 打破访问限制（针对private/protected字段）
field.setAccessible(true);

// 获取字段值
int age = (int) field.get(person); // 获取person对象的age字段值

// 修改字段值
field.set(person, 21); // 将person对象的age字段值修改为21
3. 处理特殊类型的字段
        java
// 处理静态字段（第一个参数传null）
Field staticField = clazz.getDeclaredField("count");
staticField.setAccessible(true);
int count = (int) staticField.get(null); // 获取静态字段值

// 处理数组字段
Field arrayField = clazz.getDeclaredField("scores");
arrayField.setAccessible(true);
int[] scores = (int[]) arrayField.get(obj); // 获取数组字段

// 处理基本类型（自动装箱/拆箱）
Field intField = clazz.getDeclaredField("value");
int value = intField.getInt(obj); // 等效于 (int) intField.get(obj)
三、获取变量值后的处理方法
获取字段值后，可能需要对其进行类型转换、修改或其他操作：
        1. 类型转换
获取的字段值是Object类型，需要根据字段类型进行强制转换：

java
Field field = clazz.getDeclaredField("name");
field.setAccessible(true);
String name = (String) field.get(obj); // 转换为String类型
2. 处理泛型字段
反射无法直接获取泛型类型信息（类型擦除），但可以通过getGenericType()获取字段的泛型类型：

java
Field listField = clazz.getDeclaredField("list");
Type genericType = listField.getGenericType();
if (genericType instanceof ParameterizedType) {
ParameterizedType pType = (ParameterizedType) genericType;
Type[] typeArgs = pType.getActualTypeArguments();
    System.out.println("泛型参数类型: " + typeArgs[0]); // 例如: class java.lang.String
}
        3. 修改字段值
        java
Field field = clazz.getDeclaredField("age");
field.setAccessible(true);

// 修改基本类型字段
field.setInt(obj, 25); // 等效于 field.set(obj, 25);

// 修改引用类型字段
field.set(obj, new Date()); // 设置为Date对象*/
