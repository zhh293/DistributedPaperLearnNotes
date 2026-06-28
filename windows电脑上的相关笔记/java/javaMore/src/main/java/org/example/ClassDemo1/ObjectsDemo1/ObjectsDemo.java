package org.example.ClassDemo1.ObjectsDemo1;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class ObjectsDemo {
    public static void main(String[] args) {
        Student student = new Student("张三",18,'男');
        String string = student.toString();
        //直接调用toString方法，返回的是类名+@+内存地址
        System.out.println(string);
        //直接输出对象名称，默认会自动调用toString方法
        System.out.println(student);
        //为什么我这里打印的不是地址值呢
        //因为对象重写toString方法，返回的是对象属性值
    }
}
//@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
class Student{
    private String name;
    private int age;
    private char sex;

    //判断对象中的元素是否相等
    @Override
     public boolean equals(Object obj) {
        //判断obj是否是学生类型
        if(obj instanceof Student){
            Student student = (Student) obj;
            return this.name.equals(student.name) && this.age == student.age && this.sex == student.sex;
        }
        return false;
     }
     //String toString() 方法已经被重写过了，所以这里不需要再写
    @Override
    public String toString() {
        return "Student(name=" + name + ", age=" + age + ", sex=" + sex + ")";
    }
}
/*
1. Lombok 的功能
Lombok 借助注解的方式，在编译时自动生成常规的 Java 方法，像 getter、setter、toString() 等。在你的代码里：

@Data 这个注解涵盖了 @ToString 注解的功能，这就意味着它会自动生成 toString() 方法。
生成的 toString() 方法会把对象的所有字段信息输出，格式类似于 "Student(name=张三, age=18, sex=男)"。*/



/*
2. 对象比较方法
equals(Object a, Object b)
功能：比较两个对象是否相等。
实现逻辑：
如果两个引用都是 null，返回 true；
如果只有一个引用是 null，返回 false；
否则调用 a.equals(b)。
示例：
java
Objects.equals("abc", "abc"); // true
Objects.equals(null, "abc");  // false
Objects.equals(null, null);   // true


1. Objects.equals(a, b) vs a.equals(b)
核心区别
对比项	Objects.equals(a, b)	a.equals(b)
空值安全性	支持 null，当 a 或 b 为 null 时不会抛异常	若 a 为 null，会抛出 NullPointerException
比较逻辑	先检查 a 和 b 是否都为 null，再调用 a.equals(b)	直接调用 a 的 equals() 方法
适用场景	任何可能存在 null 的场景	已确保 a 非空的场景


deepEquals(Object a, Object b)
功能：深度比较两个对象是否相等，适用于数组或嵌套对象。
与 equals 的区别：
对于数组，equals 仅比较引用是否相同，而 deepEquals 会递归比较数组元素。
示例：
java
int[] a = {1, 2};
int[] b = {1, 2};
Objects.equals(a, b);     // false（引用不同）
Objects.deepEquals(a, b); // true（内容相同）



3. 哈希值计算
hash(Object... values)
功能：为一组对象生成哈希值。
用途：在重写 hashCode() 方法时常用。
示例：
java
class Person {
    private String name;
    private int age;

    @Override
    public int hashCode() {
        return Objects.hash(name, age);
    }
}


hashCode(Object o)
功能：返回对象的哈希值，若对象为 null 则返回 0。




toString(Object o)
功能：返回对象的 toString() 结果，若对象为 null 则返回 "null"。
示例：
java
Object obj = null;
System.out.println(Objects.toString(obj)); // 输出 "null"


toString(Object o, String nullDefault)
功能：返回对象的 toString() 结果，若对象为 null 则返回指定的默认字符串。
示例：
java
Object obj = null;
System.out.println(Objects.toString(obj, "Default")); // 输出 "Default"



2. Objects.toString(obj) vs obj.toString()
核心区别
对比项	Objects.toString(obj)	obj.toString()
空值安全性	支持 null，当 obj 为 null 时返回 "null"	若 obj 为 null，会抛出 NullPointerException
默认值支持	可通过重载方法指定 null 时的默认值（如 Objects.toString(obj, "default")）	无默认值机制
适用场景	需要安全处理 null 的字符串转换场景	已确保 obj 非空的场景


为什么需要 Objects 工具类？
简化空值检查：避免手动编写 if (obj != null) 代码，减少样板代码。
提高安全性：在集合操作、流处理等场景中，自动处理 null 更安全。
函数式编程适配：Objects 方法通常支持函数式接口（如 Supplier），便于与 Java 8+ 的 Stream API 结合使用。*/
