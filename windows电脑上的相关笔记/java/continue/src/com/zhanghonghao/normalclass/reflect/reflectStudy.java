package com.zhanghonghao.normalclass.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;

public class reflectStudy {
    public static void main(String[] args) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException, NoSuchFieldException {
        //获取class字节码文件

        Class<?> class1= Class.forName("com.sky.reflect.student");
         /*Class class2= student.class;
         student student=new student();
         Class class3=student.getClass();*/
         //从字节码文件中获取各种元素
        //万物皆对象
        Constructor<?>[] cons = class1.getDeclaredConstructors();
        for (Constructor<?> con : cons) {
            System.out.println(con);
        }
        Constructor<?>[] constructors = class1.getConstructors();
        for (Constructor<?> con : constructors) {
            System.out.println(con);
        }
        Constructor<?> declaredConstructor = class1.getDeclaredConstructor();
        System.out.println(declaredConstructor);
        Constructor<?> declaredConstructor1 = class1.getDeclaredConstructor(String.class,int.class);
        System.out.println(declaredConstructor1);
        int modifiers = declaredConstructor1.getModifiers();
        System.out.println(modifiers);
        Parameter[] parameters = declaredConstructor1.getParameters();
        for (Parameter parameter : parameters) {
            System.out.println(parameter);
        }
        //上面仅仅可以获取私有构造方法，但不能用它创建对象
        //但可以临时取消权限校验，暴力反射
        declaredConstructor1.setAccessible(true);
        Object object = (student)declaredConstructor1.newInstance("张三", 23);
        System.out.println(object);
        /*
        * 运用反射获取成员变量*/
        Field[] fields = class1.getFields();
        for (Field field : fields) {
            System.out.println(field);
        }
        //Field name = class1.getField("name");
        /*Field[] declaredFields = class1.getDeclaredFields();
        for (Field field : declaredFields) {
            System.out.println(field);
        }*/
        Field name = class1.getDeclaredField("name");
        int modifiers1 = name.getModifiers();
        String name1 = name.getName();
        Class<?> type = name.getType();
        System.out.println(type);
        System.out.println(name1);
        System.out.println(modifiers1);
        System.out.println(name);
        student student = new student("张三",23);
        name.setAccessible(true);
        Object object1 = name.get(student);
        System.out.println(object1);
        name.set(student,"李四");
        Object object2 = name.get(student);
        System.out.println(object2);


        Field age = class1.getDeclaredField("age");
        System.out.println(age);

    }

    /*获取class对象的三种方式
    * 类名.class，一般当作参数进行传递
    * Class.forName("全类名")，最常用
    * 对象.getClass()，有创建的对象之后才可以使用
    * Class 类中用于获取构造方法的方法
Constructor<?>[] getConstructors()：返回所有公共构造方法对象的数组
Constructor<?>[] getDeclaredConstructors()：返回所有构造方法对象的数组
Constructor<T> getConstructor(Class<?>... parameterTypes)：返回单个公共构造方法对象
Constructor<T> getDeclaredConstructor(Class<?>... parameterTypes)：返回单个构造方法对象
Constructor 类中用于创建对象的方法
T newInstance(Object... initargs)：根据指定的构造方法创建对象
setAccessible(boolean flag)：设置为 true，表示取消访问检查
    * */

}
