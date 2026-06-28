package com.zhanghonghao.doublearraylist;

import java.util.Arrays;
import java.util.Comparator;

public class lamada {
    public static void main(String[] args) {
       Integer[] a = {2,1,4,5,3};
       System.out.println(Arrays.toString(a));
       //二分查找方法，前提，数组中的元素必须是升序的，有序的
        //这个方法返回的是索引值，如果不存在，返回的是(-插入点)-1
        System.out.println(Arrays.binarySearch(a, 5));
        System.out.println(Arrays.binarySearch(a, 6));
        System.out.println(Arrays.binarySearch(a, 2));
        //copyof   拷贝数组
        //第一个参数表示老数组，第二个参数表示新数组的长度
        //新长度小于老长度，部分拷贝。等于时，全部拷贝，大于时，默认补上初始值
        System.out.println(Arrays.toString(Arrays.copyOf(a, 3)));
        System.out.println(Arrays.toString(Arrays.copyOf(a, 5)));
        System.out.println(Arrays.toString(Arrays.copyOf(a, 6)));
        //copyofrange  拷贝数组(可指定范围),包头不包尾，包左不包右
        System.out.println(Arrays.toString(Arrays.copyOfRange(a, 2,4)));
        System.out.println(Arrays.toString(Arrays.copyOfRange(a, 3,9)));
        //sort  排序，默认情况下，进行升序排列，底层使用的是快速排序
        //Arrays.sort(a);
        //System.out.println(Arrays.toString(a));
        //如果想要变成降序的，则可以这样实现
        /*public static void sort(数组，排列规则){}细节：只能给引用数据类型排序，如果数据都是基本数据类型，需要变成对应的包装类，比如：int改成Integer*/
        //下面这个利用插入排序+二分查找的方式进行排序的
        //默认把0索引数据当作有序的序列，1索引到最后认为都是无序的
        //遍历无序的序列得到里面的每一个元素，设遍历出的元素为A
        //把A往有序序列中进行插入，再插入过程中，利用二分查找确定A元素的插入点
        //拿着A元素跟插入点元素进行比较，规则就是compare方法体
        //如果方法体返回值是正数，拿着A与后面的元素比较，反之，与前面的元素进行比较
        //compare中第一个参数为在无序序列中遍历得到的每一个元素，第二个参数表示有序序列中的元素

        Arrays.sort(a, new Comparator<Integer>() {
            @Override
            public int compare(Integer o1, Integer o2) {
                System.out.println("-----------------------------------" );
                System.out.println("o1: " + o1);
                System.out.println("o2: " + o2);
                return o1-o2;
            }
        });
        System.out.println(Arrays.toString(a));
        //fill 填充数组
        Arrays.fill(a, 5);
        System.out.println(Arrays.toString(a));
        //lambda表达式
        Integer[] b = {2,1,4,5,3};
        Arrays.sort(b,new Comparator<Integer>() {
            public int compare(Integer o1, Integer o2) {
                return o2-o1;
            }
        });
     System.out.println(Arrays.toString(b));
     Arrays.sort(b,(o1,o2)->{
         return o2-o1;
     });
     //函数式编程，强调做什么，而不是谁去做
     //()->{}lambda表达式格式，括号中是形参，只能简化函数式接口的匿名内部类的写法
        // 有且只有一个抽象方法的接口叫做函数式接口，调用一个方法时，如果方法的形参是一个接口，那我们要传递这个接口的实现类对象。如果实现类对象只要用到一次，就可以使用匿名内部类进行书写
      method(new swim(){
          public void swim() {
              System.out.println("swim");
          }
      });
      method(()->{
          System.out.println("method");
      });
      test test1=new test();
      method(test1);
    }
    //这里的swim s表示实现了swim接口的对象，传入的是一个创建了的对象，但不是swim创建的对象
    public static void method(swim s){
        s.swim();
    }
    interface swim {
        public abstract void swim();
    }
}
