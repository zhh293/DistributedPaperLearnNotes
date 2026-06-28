package com.zhanghonghao.normalclass.reflect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public class reflectStudy1 {
    public static void main(String[] args) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<?> class1=Class.forName("com.sky.reflect.student");
        //获取里面所有的方法对象(包括父类的所有公共方法)

        Method[] methods = class1.getMethods();
        for (Method method : methods) {
            System.out.println(method.getName());
        }
        //获取里面所有的方法对象(不能获取父类的，但可以获取本类的私有方法)
        Method[] declaredMethods = class1.getDeclaredMethods();
        for (Method method : declaredMethods) {
            System.out.println(method.getName());
        }
        //
        Method toString = class1.getMethod("toString");
        System.out.println(toString);
        /*Method[] declaredMethods1 = class1.getDeclaredMethods();
        for (Method method : declaredMethods1) {
            System.out.println(method.getName());
        }*/
        //方法的权限
        int modifiers = toString.getModifiers();
        System.out.println(modifiers);

        //方法的名字
        String name = toString.getName();
        System.out.println(name);
        //获取方法的形参
        Parameter[] parameters = toString.getParameters();
        for (Parameter parameter : parameters) {
            System.out.println(parameter.getName());
        }
        //获取方法抛出的异常
        Class<?>[] exceptionTypes = toString.getExceptionTypes();
        for (Class<?> exceptionType : exceptionTypes) {
            System.out.println(exceptionType.getName());
        }
        student student = new student();
        Method eat = class1.getMethod("eat", String.class);
        Object han =eat.invoke(student, "汉堡包");
        System.out.println(han);


    }
}
