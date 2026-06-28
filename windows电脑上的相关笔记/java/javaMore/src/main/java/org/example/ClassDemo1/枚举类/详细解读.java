package org.example.ClassDemo1.枚举类;

/*public class 详细解读 {
    public static void main(String[] args) {
        Demo demo1 = new Demo1();
        demo1.show();
    }
}

interface Showable{
    void show();
}
class Demo implements Showable{

}
class Demo1 extends Demo{
    public void show(){
        System.out.println("这是一个枚举对象");
    }
}*/






/*public class 详细解读 {
    public static void main(String[] args) {

    }
}
interface Showable{
    void show();
    enum Sex1 implements Showable{
        MALE, FEMALE,
        Example{

            *//*@Override
            public void show(){
                System.out.println("这是一个枚举对象");
            }*//*
        };
       *//* public void show(){
            System.out.println("这是一个枚举对象");
        }*//*
    }
}*/

/*
public class 详细解读 {
}




1. 枚举类的本质
枚举类（enum）本质上是一个特殊的类，它具有以下特征：
继承自 java.lang.Enum：每个枚举类都隐式继承自 Enum 类
final修饰：枚举类默认是final的，不能被继承
私有构造器：枚举类的构造器默认是private的，不能在外部实例化
2. 枚举对象的本质
在枚举类中定义的每个枚举值（如 MALE, FEMALE 等）本质上是：
枚举类的实例对象
public static final 修饰的常量
        在类加载时创建
以你的代码为例：
enum Sex{
    MALE, FEMALE,
    Example{
        public void show(){
            System.out.println("这是一个枚举对象");
        }
    }
}
在这个例子中：
MALE 是 Sex 类型的一个实例对象
FEMALE 是 Sex 类型的另一个实例对象
Example 也是一个实例对象，但它使用了匿名内部类的方式定义
3. 编译器自动添加的方法
编译器会为枚举类自动添加以下方法：

// 编译器自动生成的方法（伪代码）
public final class Sex extends java.lang.Enum<Sex> {
    // 枚举常量（public static final）
    public static final Sex MALE = new Sex();
    public static final Sex FEMALE = new Sex();
    public static final Sex Example = new Sex$1(); // 匿名内部类

    // values()方法
    public static Sex[] values() { ... }

    // valueOf()方法
    public static Sex valueOf(String name) { ... }

    // ordinal()方法
    public int ordinal() { ... }

    // name()方法
    public String name() { ... }
}

4. 枚举对象的创建过程

// 枚举类定义
enum Color {
    RED, GREEN, BLUE
}

// 编译器实际生成的代码类似这样（简化版）：
class Color extends Enum<Color> {
    public static final Color RED = new Color("RED", 0);
    public static final Color GREEN = new Color("GREEN", 1);
    public static final Color BLUE = new Color("BLUE", 2);

    private Color(String name, int ordinal) {
        super(name, ordinal);
    }

    public static Color[] values() {
        return new Color[]{RED, GREEN, BLUE};
    }

    public static Color valueOf(String name) {
        // 查找对应枚举常量的逻辑
    }
}
5. 枚举对象的特点
单例性：每个枚举值在整个JVM中只有一个实例
类型安全：枚举值是类型安全的，不能创建新的实例
可序列化：枚举天然支持序列化
有索引：每个枚举值都有一个ordinal值（从0开始）
        6. 关于匿名内部类枚举对象
在你的例子中：


Example{
    public void show(){
        System.out.println("这是一个枚举对象");
    }
}

这实际上是创建了一个 Sex 的匿名子类实例。这个匿名子类继承自 Sex，并重写了某些方法或添加了新方法。
但是，由于这个 show() 方法没有在 Sex 类中声明，所以你无法通过 Sex 类型的引用调用它，只能通过类型转换或者在匿名类内部调用。

总结：枚举类是一种特殊的类，枚举对象是该类的静态常量实例，在类加载时创建，具有单例性和类型安全性。


*/




