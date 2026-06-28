package com.zhanghonghao.system;

public class objects {
    public static void main(String[] args) {
        //objects成员方法，equals，isnull，nonull
       /*boolean result = Objects.equals (s1, s2);
System.out.println (result);
// 细节：
//1. 方法的底层会判断 s1 是否为 null，如果为 null，直接返回 false
//2. 如果 s1 不为 null，那么就利用 s1 再次调用 equals 方法
//3. 此时 s1 是 Student 类型，所以最终还是会调用 Student 中的 equals 方法
// 如果没有重写，比较地址值，如果重写了，就比较属性值。*/
//objects是一个对象工具类，提供一些操作对象的方法

        /*//public static boolean isNull (Object obj) 判断对象是否为 null，为 null 返回 true，反之
Student s3 = new Student ();
Student s4 = null;
System.out.println (Objects.isNull (s3));//false
System.out.println (Objects.isNull (s4));//true
System.out.println(Objects.nonNull(s3));//true
System.out.println(Objects.nonNull(s4));//false
*/
    }
}
