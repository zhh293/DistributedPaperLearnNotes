package org.example.ClassDemo1.注解;

public class Demo4 {


   /* 在 Java 开发中，编译器经常会产生一些警告信息（Warning），这些警告不会导致编译失败，但可能提示代码存在潜在问题（如类型不安全、使用过时 API 等）。@SuppressWarnings注解是 Java 提供的一种用于抑制编译器特定警告的机制，帮助开发者管理警告信息，避免无关警告干扰开发注意力。
    一、@SuppressWarnings注解的基本作用
    @SuppressWarnings的核心功能是告诉编译器：对于被注解标记的代码元素（类、方法、字段等），忽略指定类型的警告。

    它解决的主要问题是：
    在大型项目中，编译器可能会产生大量警告（如泛型未指定类型、使用过时方法等），这些警告可能掩盖真正需要关注的问题（如逻辑错误相关的警告）。通过@SuppressWarnings可以精确控制需要忽略的警告类型，让开发者聚焦于重要警告。
    二、@SuppressWarnings的用法详解
1. 注解的使用位置
    @SuppressWarnings可以修饰的代码元素包括：

    类（class）
    方法（method）
    字段（field）
    局部变量（local variable）
    参数（parameter）
    代码块（需配合{}使用）

    原则：尽量将注解作用于最小范围的代码元素（如局部变量或单行代码），避免大范围抑制警告导致遗漏潜在问题。
            2. 核心属性：value
    @SuppressWarnings唯一的属性是value，类型为String[]，用于指定需要抑制的警告类型（每个类型对应一种编译器警告）。

    常见的警告类型及含义：

    警告类型	含义说明
"unchecked"	抑制 “未经检查的类型转换” 警告（如泛型集合未指定类型时的转换）
            "deprecation"	抑制 “使用过时 API” 警告（调用被@Deprecated标记的方法 / 类时）
            "rawtypes"	抑制 “使用原始类型” 警告（如List而非List<String>）
            "unused"	抑制 “变量 / 方法未使用” 警告（定义了但未使用的元素）
            "serial"	抑制 “序列化类缺少serialVersionUID” 警告
"fallthrough"	抑制 “switch 语句穿透” 警告（case 分支未加 break 导致的穿透）
            "uncheckedcast"	抑制 “未经检查的强制类型转换” 警告*/


}
