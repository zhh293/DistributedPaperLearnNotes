package com.zhanghonghao.normalclass.List;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class list1 {
    public static void main(String[] args) {
        //集合进阶
        //泛型深入，在编译阶段约束操作的数据类型，只能写引用数据类型    <数据类型>
        ArrayList list = new ArrayList();
        list.add(1);
        list.add("45677");
        list.add("45678");
        list.add(2.34);
        Iterator iterator = list.iterator();
        while (iterator.hasNext()) {
            System.out.println(iterator.next());
        }
 //如果我们没有给集合指定类型。默认所有的数据类型都是object类型，可以添加任意的数据

        //但是在获取完之后无法使用特有的功能，比如字符串类型中的length方法就无法使用，于是引出了泛型
        //java中的泛型是伪泛型
        /*泛型的细节
泛型中不能写基本数据类型
指定泛型的具体类型后，传递数据时，可以传入该类类型或者其子类类型
如果不写泛型，类型默认是 Object
*/
//1.泛型类，当一个类中某个变量的数据类型不确定时，就可以定义带有泛型的类
/*格式：
修饰符 class 类名 <类型> {
}
举例：
public class ArrayList <E> {
创建该类对象时，E 就确定类型
}
此处 E 可以理解为变量，但是不是用来记录数据的，而是记录数据的类型，可以写成：T、E、K、V*/
        /*public class ArrayList <E> {
}
ArrayList<String> list = new ArrayList<>();
list.add("aaa");
list.add("bbb");*/
        //这时候E的类型才被真正确定
        //2.泛型方法，方法形参类型不确定时，小知识:重载时,泛型优先级低,会先调用非泛型
       /* List<String>list1 = new ArrayList();
        ListUtils.addAll(list1,"张鸿昊");
        System.out.println(list1);
        ListUtils.addAll2(list1,"张鸿昊","张哲闻","陈奕迅");
        System.out.println(list1);*/
        //3.泛型接口
        /*public class MyArrayList2 implements List<String>*/
        /*实现类给出具体的类型：即实现泛型接口时，直接指定泛型的具体类型，这样在该实现类中，泛型就被确定为指定的类型。比如public class MyArrayList2 implements List<String> {}，这里List是泛型接口，MyArrayList2实现它时指定了泛型为String 。
实现类延续泛型，创建实现类对象时再确定类型：实现类在实现泛型接口时，不指定具体类型，而是继续使用泛型，等到创建该实现类的对象时再确定泛型的具体类型。例如定义public class MyList<T> implements List<T> {} ，然后MyList<Integer> myList = new MyList<>();，在创建myList对象时确定了泛型为Integer。
*?
//泛型不具备继承性，但是数据具备继承性

         */
        /*/*
    泛型不具备继承性，但是数据具备继承性
*/

//创建集合的对象
        /*ArrayList<Ye> list1 = new ArrayList<>();
        ArrayList<Fu> list2 = new ArrayList<>();
        ArrayList<Zi> list3 = new ArrayList<>();

//调用method方法
//method(list1);
//method(list2);
//method(list3);

        list1.add(new Ye());
        list1.add(new Fu());
        list1.add(new Zi());
    }

    /*
     * 此时，泛型里面写的是什么类型，那么只能传递什么类型的数据。
     * */
   // public static void method(ArrayList<Ye> list) {
   // }*/
//对于不确定的类型，如果我想把类型局限在一定的范围内，那么就需要通配符地参与了
   //？extends E，表示可以传递E或者E所有的子类
   //？super E：表示可以传递E或者E所有的父类类型
        CAT cat1=new CAT("zzzz",13);
        CAT cat2=new CAT("zzzz",13);
        CAT cat3=new CAT("zzzz",13);
       ArrayList<CAT>list1 = new ArrayList<>();
       list1.add(cat1);
       list1.add(cat2);
       list1.add(cat3);
        dog dog1=new dog("zz",18);
        dog dog2=new dog("zz",18);
        dog dog3=new dog("zz",18);
        ArrayList<dog>list2 = new ArrayList<>();
        list2.add(dog1);
        list2.add(dog2);
        list2.add(dog3);
        ArrayList<animal>list3 = new ArrayList<>();
        list3.add(cat1);
        list3.add(cat2);
        list3.add(cat3);
        list3.add(dog1);
        list3.add(dog2);
        list3.add(dog3);
    }
   /* public static void keepPet(ArrayList<dog> list){
          //遍历集合，调用eat方法
       for(animal s1: list){
           s1.eat();
       }

    }
    public static void keepPet(ArrayList<CAT>list1){
        for(animal s1: list1){
            s1.eat();
        }
    }
    public static void keepPet(ArrayList<? extends animal>list2){
    for(animal s1: list){
           s1.eat();
       }
    }
     public static void keepPet(ArrayList<animal>list1){
        for(animal s1: list1){
            s1.eat();
        }
    }

    */

}
