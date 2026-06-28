package org.example.ClassDemo1.注解;

public @interface Demo1 {
    String value() default "default";
    int age() default 18;
    String bookName() default "default";
    String[] books() default {"default"};
    double price() default 0.0;
}
/*
注解的基本概念
1. 内置注解
Java 提供了几种内置注解：

@Override：检查该方法是否重写了父类的方法。
@Deprecated：标记某个元素（类、方法等）已过时，不推荐使用。
@SuppressWarnings：抑制编译器警告。
@FunctionalInterface：标记一个接口是函数式接口（只有一个抽象方法的接口）。
        2. 元注解
元注解是用于定义注解的注解，Java 提供了四种元注解：

@Retention：指定注解的保留策略（SOURCE、CLASS或RUNTIME）。
@Target：指定注解可以应用的程序元素类型（如类、方法、字段等）。
@Documented：指示该注解应该被包含在 JavaDoc 中。
@Inherited：指示该注解可以被继承。
自定义注解
自定义注解使用@interface语法定义，例如：

java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MyAnnotation {
    String value() default ""; // 定义一个名为value的元素
    int count() default 1;     // 定义一个名为count的元素，默认值为1
}
注解的反射处理
Java 反射机制允许在运行时检查和使用注解。以下是一些关键的反射方法：
        1. 获取注解实例
getAnnotation(Class<T> annotationClass)：返回该元素上指定类型的注解，如果不存在则返回null。
getAnnotations()：返回该元素上的所有注解（包括继承的注解）。
getDeclaredAnnotations()：返回直接存在于此元素上的所有注解（不包括继承的注解）。
        2. 检查注解是否存在
isAnnotationPresent(Class<? extends Annotation> annotationClass)：判断该元素上是否存在指定类型的注解。
        3. 获取注解的元素值
通过注解实例调用其定义的方法获取元素值。
示例代码
以下示例展示了如何定义、使用和处理注解：

java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

// 定义注解
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface MyAnnotation {
    String value() default "default";
    int count() default 1;
}

// 使用注解
class MyClass {
    @MyAnnotation(value = "test", count = 3)
    public void myMethod() {
        System.out.println("Hello, Annotation!");
    }
}

// 处理注解
public class AnnotationExample {
    public static void main(String[] args) throws NoSuchMethodException {
        // 获取类和方法
        Class<?> clazz = MyClass.class;
        Method method = clazz.getMethod("myMethod");

        // 检查注解是否存在
        if (method.isAnnotationPresent(MyAnnotation.class)) {
            // 获取注解实例
            MyAnnotation annotation = method.getAnnotation(MyAnnotation.class);

            // 获取注解元素值
            String value = annotation.value();
            int count = annotation.count();

            System.out.println("注解值: " + value);
            System.out.println("注解count: " + count);

            // 执行方法
            try {
                method.invoke(clazz.getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
运行时注解与编译时注解
1. 运行时注解（RetentionPolicy.RUNTIME）
注解信息在运行时保留，可以通过反射获取。
适用于需要在运行时动态处理的场景，如依赖注入框架（Spring）、单元测试框架（JUnit）。
        2. 编译时注解（RetentionPolicy.CLASS）
注解信息保留到类文件中，但在运行时不可用。
适用于编译时生成代码或进行静态检查，如 Lombok、Dagger。
        3. 源码级注解（RetentionPolicy.SOURCE）
注解信息仅保留在源码中，编译时被丢弃。
适用于提供编译时提示，如@Override、@SuppressWarnings。*/



/*

1. @Target 注解
        作用
@Target 用于指定自定义注解可以应用于哪些程序元素（如类、方法、字段等）。如果没有指定 @Target，则该注解可以应用于所有类型的元素。
参数（ElementType 枚举）
@Target 接受一个或多个 ElementType 枚举值作为参数：

TYPE：类、接口（包括注解类型）或枚举声明
FIELD：字段声明（包括枚举常量）
METHOD：方法声明
PARAMETER：参数声明
CONSTRUCTOR：构造函数声明
LOCAL_VARIABLE：局部变量声明
ANNOTATION_TYPE：注解类型声明
PACKAGE：包声明
TYPE_PARAMETER：类型参数声明（Java 8+）
TYPE_USE：类型使用（Java 8+，可用于任何使用类型的地方）
示例
        java
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

// 该注解只能用于方法和字段
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface MyAnnotation {
    String value() default "";
}
2. @Retention 注解
        作用
@Retention 用于指定自定义注解的生命周期，即注解在哪个阶段有效。
参数（RetentionPolicy 枚举）
@Retention 接受一个 RetentionPolicy 枚举值作为参数：

SOURCE：注解仅存在于源码中，编译时被编译器丢弃（如 @Override）。
CLASS：注解在编译时保留在类文件中，但运行时不可见（默认行为）。
RUNTIME：注解在运行时保留，可通过反射机制读取（如 @Test）。
示例
        java
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

// 该注解在运行时可见，可通过反射访问
@Retention(RetentionPolicy.RUNTIME)
public @interface MyRuntimeAnnotation {
    String value() default "";
}*/
