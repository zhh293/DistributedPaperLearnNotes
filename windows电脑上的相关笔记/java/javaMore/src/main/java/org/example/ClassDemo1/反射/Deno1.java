package org.example.ClassDemo1.反射;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

public class Deno1 {
    public static void main(String[] args) throws ClassNotFoundException, InvocationTargetException, InstantiationException, IllegalAccessException, NoSuchMethodException {
         //反射第一步：获取Class对象
       /* Class<?> clazz = String.class;
        System.out.println(clazz);
        Deno1 demo = new Deno1();
        Class<?> clazz1 = demo.getClass();
        System.out.println(clazz1);
        Class<?> clazz2=Class.forName("java.lang.String");
        System.out.println(clazz2);
        String simpleName = clazz1.getSimpleName();
        System.out.println(simpleName);
        String name = clazz1.getName();
        System.out.println(name);*/
        //反射第二步：获取属性，对这个类中的东西进行操作
        Class<Student> studentClass = Student.class;
        Constructor<?>[] constructors = studentClass.getConstructors();
        for(Constructor<?> constructor : constructors){
            System.out.println(constructor);
        }
        Constructor<?>[] declaredConstructors = studentClass.getDeclaredConstructors();
        for(Constructor<?> constructor : declaredConstructors){
            int modifiers = constructor.getModifiers();
            if(Modifier.isPrivate(modifiers)){ //下面这个方法是为了访问private构造器，暴力反射
                constructor.setAccessible(true);
                Student student = (Student) constructor.newInstance();
                System.out.println(student);
            }else{
                Student student = (Student) constructor.newInstance("soga");
                System.out.println(student);
            }
        }
        Constructor<Student> constructor = studentClass.getConstructor(String.class);
        System.out.println( constructor);
        Student student = constructor.newInstance("张三");
        System.out.println(student);



    }

}





/*
反射中class类型获取构造器提供了很多的API:

Constructor getConstructor(Class...

1.

        parameterTypes)

根据参数匹配获取某个构造器，只能拿public修饰的构造器，几乎不用!

Constructor getDeclaredConstructor(Class... parameterTypes)

根据参数匹配获取某个构造器，只要中明就可以定位，不关心权限修饰符，建议使用!

ConstructorllqetConstructors()

获取所有的构造器，只能拿public修饰的构造器。几乎不用!!太弱了!

ConstructorllqetDeclaredConstructors()

获取所有中明的构造器，只要你写我就能拿到，无所谓权限。建议使用!!

*/

/*
一、反射的核心概念
反射的核心在于 Java 程序能够在运行时动态地获取类的完整信息，像类的属性、方法、构造函数等，还可以动态调用对象的方法或者操作对象的属性。这种动态特性让 Java 语言变得更加灵活，尤其适合用于开发框架、工具或者需要高度动态化的应用场景。
二、反射的关键类
Java 的反射功能主要依靠以下几个类来实现，这些类都位于java.lang.reflect包以及java.lang包中：

Class 类：它代表一个类或者接口，是反射的核心。通过Class对象，我们可以获取类的所有信息。获取Class对象主要有三种方式：

java
// 方式一：通过类名获取
Class<?> clazz1 = String.class;
// 方式二：通过对象获取
String str = "hello";
Class<?> clazz2 = str.getClass();
// 方式三：通过全类名获取（可能会抛出ClassNotFoundException异常）
Class<?> clazz3 = Class.forName("java.lang.String");

Field 类：用于表示类的成员变量，借助它能够动态地读取或者修改对象的属性。
Method 类：代表类的方法，可用于动态调用方法。
Constructor 类：表示类的构造函数，能够动态创建对象。
Modifier 类：用于解析类、方法或者字段的修饰符，比如public、static等。
三、反射的常见应用场景
框架开发：像 Spring 通过反射来实现依赖注入，Hibernate 利用反射进行 ORM 映射。
动态代理：在 AOP（面向切面编程）中，动态代理是其核心实现机制。
序列化与反序列化：在处理 JSON 或者 XML 数据时，经常会用到反射技术。
测试工具：测试框架需要通过反射来动态调用私有方法或者访问私有字段。
插件系统：通过反射可以在运行时加载和使用外部插件。
四、反射的基本操作示例
1. 动态创建对象
        java
try {
// 获取Class对象
Class<?> clazz = Class.forName("java.util.ArrayList");
// 创建实例（调用无参构造函数）
Object instance = clazz.getDeclaredConstructor().newInstance();
// 转换为具体类型
List<String> list = (List<String>) instance;
    list.add("hello");
    System.out.println(list); // 输出: [hello]
} catch (Exception e) {
        e.printStackTrace();
}
        2. 动态调用方法
        java
try {
String str = "hello";
Class<?> clazz = str.getClass();
// 获取方法（参数：方法名，参数类型列表）
Method method = clazz.getMethod("toUpperCase");
// 调用方法（参数：对象实例，方法参数）
Object result = method.invoke(str);
    System.out.println(result); // 输出: HELLO
} catch (Exception e) {
        e.printStackTrace();
}
        3. 动态访问和修改字段
        java
try {
Class<?> clazz = Person.class;
Person person = new Person("Alice", 20);
// 获取私有字段
Field field = clazz.getDeclaredField("age");
// 打破访问限制（重要！否则无法访问私有字段）
    field.setAccessible(true);
// 获取字段值
int age = (int) field.get(person);
    System.out.println("Before: " + age); // 输出: 20
// 修改字段值
    field.set(person, 21);
    System.out.println("After: " + person.getAge()); // 输出: 21
        } catch (Exception e) {
        e.printStackTrace();
}

// 辅助类
class Person {
    private String name;
    private int age;
    public Person(String name, int age) {
        this.name = name;
        this.age = age;
    }
    public int getAge() {
        return age;
    }
}
4. 动态获取类的信息
        java
Class<?> clazz = String.class;
// 获取类名
System.out.println("类名: " + clazz.getName());
// 获取所有公共方法
Method[] methods = clazz.getMethods();
System.out.println("公共方法数量: " + methods.length);
// 获取所有公共字段
Field[] fields = clazz.getFields();
System.out.println("公共字段数量: " + fields.length);
// 获取所有构造函数
Constructor<?>[] constructors = clazz.getConstructors();
System.out.println("构造函数数量: " + constructors.length);
五、反射的优缺点
        优点
高度灵活性：能够在运行时处理未知类型的对象，这对于框架开发来说至关重要。
可扩展性：方便实现插件化架构，在运行时动态加载组件。
测试便利：有助于编写更全面的测试用例，例如测试私有方法。
缺点
性能开销大：反射操作涉及动态解析类型，比直接调用代码的效率要低。
破坏封装性：可以访问和修改私有成员，这违背了面向对象编程的设计原则。
安全性风险：可能会导致敏感信息泄露或者被恶意利用。
代码复杂度增加：反射代码通常比较晦涩难懂，维护起来也更加困难。*/
