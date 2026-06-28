package org.example.ClassDemo1.反射;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class 泛型擦除 {
    //利用了底层的编译原理等等造成的小技巧
    public static void main(String[] args) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        List<String> names=new ArrayList<>();
        names.add("张三");
        names.add("李四");
        names.add("王五");
        System.out.println(names);
        Class<?> namesClass = names.getClass();
        Method add = namesClass.getDeclaredMethod("add", Object.class);
        Object invoke = add.invoke(names, 123);
        System.out.println(invoke);
        System.out.println(names);
    }


}
/*什么是泛型擦除？
简单来说，泛型擦除是指 Java 编译器在编译泛型代码时，会移除所有泛型类型信息（如List<String>中的<String>），只保留原始类型（如List）。这样，在运行时，Java 虚拟机（JVM）实际上看不到泛型类型信息，所有的泛型类型都被替换为它们的原始类型。
为什么需要泛型擦除？
Java 引入泛型时，为了保持与旧版本代码的兼容性（即所谓的 "向后兼容"），采用了泛型擦除机制。这样，即使引入了泛型，已有的非泛型代码仍然可以正常运行，不需要重新编译。
泛型擦除的具体表现
        类型参数被擦除为原始类型
无界类型参数（如<T>）被擦除为Object。
有界类型参数（如<T extends Number>）被擦除为第一个边界类型（这里是Number）。
例如：
java
// 泛型类
public class Box<T> {
    private T value;
    public void setValue(T value) { this.value = value; }
    public T getValue() { return value; }
}

// 编译后等价于
public class Box {
    private Object value;
    public void setValue(Object value) { this.value = value; }
    public Object getValue() { return value; }
}

编译时的类型检查
泛型的类型检查是在编译期完成的，编译器会在插入必要的类型转换代码后，移除泛型类型信息。例如：
java
List<String> list = new ArrayList<>();
list.add("hello");
String s = list.get(0); // 不需要显式类型转换

// 编译后等价于
List list = new ArrayList();
list.add("hello");
String s = (String) list.get(0); // 编译器插入了类型转换

运行时无法获取泛型类型信息
由于泛型擦除，运行时无法区分两个不同泛型类型的实例。例如：
java
List<String> stringList = new ArrayList<>();
List<Integer> intList = new ArrayList<>();

System.out.println(stringList.getClass() == intList.getClass()); // 输出 true
// 因为运行时两者都是 ArrayList 类型

不能创建具体类型的泛型数组
由于泛型擦除，Java 不允许创建具体类型的泛型数组。例如：
java
List<String>[] stringLists = new List<String>[10]; // 编译错误
// 但可以创建无界通配符的泛型数组
List<?>[] lists = new List<?>[10];

泛型擦除的影响
优点：保持了与旧版本 Java 代码的兼容性，减少了运行时的内存开销。
缺点：
运行时无法获取泛型类型信息，某些反射操作受限。
不能创建具体类型的泛型数组。
某些情况下需要编写额外的类型检查代码。
示例说明
以下代码展示了泛型擦除的效果：

java
import java.util.ArrayList;
import java.util.List;

public class GenericErasureExample {
    public static void main(String[] args) {
        List<String> stringList = new ArrayList<>();
        List<Integer> intList = new ArrayList<>();

        // 运行时类型相同
        System.out.println(stringList.getClass() == intList.getClass()); // 输出 true

        // 编译时类型检查
        stringList.add("hello");
        // stringList.add(123); // 编译错误：类型不匹配

        // 泛型方法的类型擦除
        printList(stringList);
        printList(intList);
    }

    // 泛型方法的类型擦除：T 被擦除为 Object
    public static <T> void printList(List<T> list) {
        for (T element : list) {
            System.out.println(element);
        }
    }
}



在这个例子中，stringList和intList在运行时都是ArrayList类型，泛型信息被擦除。printList方法中的泛型参数T在编译后也被擦除为Object。*/

