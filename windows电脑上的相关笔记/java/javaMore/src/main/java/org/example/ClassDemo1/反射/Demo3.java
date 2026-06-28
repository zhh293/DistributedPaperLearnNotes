package org.example.ClassDemo1.反射;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Demo3 {
    public static void main(String[] args) throws Exception {
        /*Class<?>clazz=Student.class;
        Method show = clazz.getDeclaredMethod("show", String.class);
        int modifiers = show.getModifiers();
        System.out.println(modifiers);
        String name = show.getName();
        System.out.println(name);
        Class<?> returnType = show.getReturnType();
        System.out.println(returnType);
        Student student = new Student();
        show.invoke(student,"张三");
        Method show1 = clazz.getDeclaredMethod("show", int.class, String.class);
        show1.setAccessible(true);
        Object returnResult = show1.invoke(student, 18, "张三");
        System.out.println(returnResult);*/
        /*Class<?> clazz=Student.class;
        Method show = clazz.getDeclaredMethod("show", String.class, int.class);
        Student student = new Student();
        Object invoke = show.invoke(student, "张三", 18);
        System.out.println(invoke);
        Method show1 = clazz.getDeclaredMethod("show", int.class, String.class);
        show1.setAccessible(true);
        show1.invoke(student, 18, "张三");*/
        Class<?> clazz=Student.class;
        Student student = new Student();
        Field name = clazz.getDeclaredField("name");
        name.setAccessible(true);
        System.out.println(name.getModifiers());
        name.set(student,"张三");
        System.out.println(student.getName());
        System.out.println(name.get(student));
        System.out.println(name.getName());
    }

}
/*
一、获取 Method 对象
要操作类的方法，首先得获取对应的 Method 对象。常用的获取方式如下：

java
import java.lang.reflect.Method;

public class MethodReflectionExample {
    public static void main(String[] args) throws Exception {
        // 获取 Class 对象
        Class<?> clazz = MyClass.class;

        // 获取公共方法（包括继承的方法）
        Method publicMethod = clazz.getMethod("publicMethod", String.class);

        // 获取声明的方法（仅当前类声明的方法，包含私有方法）
        Method privateMethod = clazz.getDeclaredMethod("privateMethod", int.class);
    }
}

class MyClass {
    public void publicMethod(String param) {}
    private void privateMethod(int num) {}
}
二、常用的 Method 方法
1. 方法信息查询
        java
Method method = clazz.getMethod("methodName", paramType1, paramType2);

// 获取方法名称
String name = method.getName(); // 返回 "methodName"

// 获取返回类型
Class<?> returnType = method.getReturnType();

// 获取参数类型数组
Class<?>[] paramTypes = method.getParameterTypes();

// 获取异常类型数组
Class<?>[] exceptionTypes = method.getExceptionTypes();

// 判断方法的修饰符
int modifiers = method.getModifiers();
boolean isPublic = Modifier.isPublic(modifiers);
boolean isStatic = Modifier.isStatic(modifiers);
2. 方法调用
借助 invoke 方法，能够动态调用对象的方法。

java
// 创建目标类的实例
MyClass obj = new MyClass();

// 调用无参方法
method.invoke(obj);

// 调用有参方法
method.invoke(obj, "参数1", 2);

// 调用静态方法（第一个参数传 null）
Method staticMethod = clazz.getMethod("staticMethod");
staticMethod.invoke(null);
3. 私有方法访问
若要访问私有方法，需要先调用 setAccessible(true) 来绕过访问检查。

java
Method privateMethod = clazz.getDeclaredMethod("privateMethod");
privateMethod.setAccessible(true); // 压制 Java 访问控制检查
privateMethod.invoke(obj);
三、处理泛型方法
对于泛型方法，Method 对象返回的是擦除后的类型。若想获取泛型的实际类型，可使用 getGenericParameterTypes() 方法。

java
public <T> void genericMethod(List<T> list) {}

Method method = clazz.getMethod("genericMethod", List.class);
Type[] genericTypes = method.getGenericParameterTypes();

// 获取第一个参数的泛型类型
ParameterizedType paramType = (ParameterizedType) genericTypes[0];
Type typeArg = paramType.getActualTypeArguments()[0]; // 返回 T 的实际类型
四、异常处理
在使用反射调用方法时，可能会出现以下异常：

InvocationTargetException：目标方法内部抛出异常时会触发。
IllegalAccessException：方法不可访问时会抛出。
IllegalArgumentException：参数类型不匹配时会出现。

处理异常的示例如下：

java
try {
        method.invoke(obj, invalidParam);
} catch (InvocationTargetException e) {
Throwable targetEx = e.getTargetException(); // 获取目标方法抛出的原始异常
    targetEx.printStackTrace();
} catch (IllegalAccessException | IllegalArgumentException e) {
        e.printStackTrace();
}
五、性能考量
反射调用方法的性能要比直接调用低，这是因为反射涉及动态解析。为了提升性能，可以采用以下方法：

缓存 Method 对象，避免重复查找。
启用方法句柄（Java 7 及以后版本支持）：

java
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

// 获取方法句柄
MethodHandles.Lookup lookup = MethodHandles.lookup();
MethodHandle handle = lookup.unreflect(method);

// 调用方法
handle.invoke(obj, args); // 比反射调用更快*/
