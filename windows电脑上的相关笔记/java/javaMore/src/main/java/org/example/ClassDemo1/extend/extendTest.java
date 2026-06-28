package org.example.ClassDemo1.extend;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.lang.reflect.Field;

public class extendTest {
    public static void main(String[] args) {
        Teacher teacher = new Teacher("hewieuk",187);
        teacher.setName("lksjh");
        System.out.println(teacher);
    }
}
@Data
@AllArgsConstructor
@NoArgsConstructor
class People{
    private String name;
    private int age;
    private static String sex;
    static {
        sex = "男";
    }
    static String school;
    /*public People(String name, int age) {
        this.name = name;

        this.age = age;
    }

    public People() {

    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public int getAge() {
        return age;
    }
    public void setAge(int age) {
        this.age = age;
    }*/
    public void eat(){
        System.out.println(this.getName()+"吃饭");
    }
}


class student extends People{
   /* public student() {}
    public student(String name, int age) {
        super(name, age);
    }*/

    public void study(){
       // People.sex = "男";
        People.school = "上海";
        System.out.println(this.getName()+"学习中");
   }
}

class Teacher extends People{
   // String  sex;
    public Teacher() {}
    public Teacher(String name, int age/*, String sex*/) {
        super(name, age);
       // this.sex = sex;
    }

    /*public Teacher(String name,String sex){
        super.setName( name);
        this.sex = sex;
    }*/

    public void teach(){
        Class<Teacher> teacherClass = Teacher.class;
        Field[] declaredFields = teacherClass.getDeclaredFields();
        //获取父类的私有属性
        for(Field field:declaredFields){
            if(field.getName().equals("sex")){
                field.setAccessible(true);
                try {
                    field.set(this,"女");
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
        System.out.println("老师要授课");
    }
}
/*引入:

子类继承父类，子类就得到了父类的属性和行为。

但是并非所有父类的属性和行为等子类都可以继承。

子类不能继承父类的东西:

子类不能继承父类的构造器:子类有自己的构造器。

        (没有争议的)

有争议的观点:

子类是否可以继承父类的私有成员(私有成员变量，私有成员方法)? 子类是可以继承父类的私有成员的，只是不能直接访问罢了
以后可以通过反射暴力使用

子类是不能够继承父类的静态成员，但是可以通过子类对象进行访问，但是这些成员只有一份备份，并不是独立于父类的，其中一个对象如果修改了它的值，那么其他对象再使用这个值
的时候就是被修改过的值。


子类是否可以继承父类的静态成员?*/

/*. @Data注解的作用
@Data是一个复合注解，包含：

@Getter/@Setter（生成所有字段的访问器）
@ToString
@EqualsAndHashCode
@RequiredArgsConstructor
2. 子类继承规则
父类使用@Data生成的 getter/setter 方法会被子类继承，除非：

子类中定义了同名方法（方法签名相同）
父类方法被声明为private或final
3. 构造函数的继承
@AllArgsConstructor和@NoArgsConstructor仅为当前类生成构造函数
子类不会继承父类的构造函数，但可以通过super()调用*/



