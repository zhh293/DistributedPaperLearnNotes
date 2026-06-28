package org.example.ClassDemo1.注解;

public @interface 元注解 {
}
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
