package com.zhanghonghao.bagfinalchunk;

public class qualify {
    public static void main(String[] args) {

        //权限修饰符  private(只能在本类中使用)   空着不写(同一个类，同一个包中的其它类)  protected(同一个类，同一个包中的其它类，不同包下的子类)   public(拥有protected所有权限,并且在不同包下的无关类中也能使用)
/*bag bag = new bag();
bag.name="张鸿昊";
System.out.println(bag.name);*/
        //代码块，局部，构造，静态
        //局部代码块：写在方法里面的
        int a=10;
        System.out.println(a);
        { int b=10;}
        //System.out.println(b);
        //构造代码块
        //写在构造代码块中的代码会先于构造方法执行,作用：可以把多个构造方法中的重复代码抽取出来
        //写在成员位置的代码块
        bag bag=new bag();
        bag bag1=new bag("zhang",18);
        //有重复的代码，不需要再使用构造代码块了，可以这样实现
        /*public bag(){调用方法();}
        public bag(String name,int age){调用方法();this.name=name;this.age=age;}*/
        bag bag2=new bag("zhang",18);
        bag bag3=new bag("zhang",18);





    }

}
