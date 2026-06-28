package com.zhanghonghao.internal;

public class test {
    public static void main(String[] args) {
       internal1 a = new internal1();
       //a.show(a,a.engine);
       //b类表示的事物是a类的一部分，且b单独存在没有意义的时候需要内部类
        //内部类的分类  成员，局部，静态，匿名内部类
        //写在成员位置，属于外部类的成员
        internal1 b = new internal1();
         b.engine.engineage=18;
        internal1.engine s=new internal1().new engine();
        s.enginename=a.getClass().getName();
        internal1 c=new internal1();
        c.getengine().engineage=18;
        Object s1=c.getengine();
        System.out.println(s1);
        practice p=new practice();
        p.getInner().show();
        staticinternal.inner st1=new staticinternal.inner();
        st1.show();
        staticinternal.inner.show2();
        partialinternal p1=new partialinternal();
        p1.show();
        p1.show2();
        //new的不是接口，new的是实现这个接口的没有名字的类的对象
        new nonameinternal(){
         @Override
         public void display() {
          System.out.println("display");
         }
        };
        //new的是animal这个类的子类的对象，继承关系，芜湖
        new animal(){
         @Override
         public void eat() {
          System.out.println("eat");
         }
        };
        //都得重写抽象方法，服了，写就写吧
        //当方法的参数是接口或者类时，以接口为例，可以传递这个接口的实现对象
        //如果实现类只要使用一次，就可以用匿名内部类简化代码
     method(new internal1(){
      public void eat(){
       System.out.println("eat");
      }
     });
     method1(new nonameinternal() {
      @Override
      public void display() {
       System.out.println("display");
      }
     });
     practice p2=new practice();
     method1(p2);
     //参数如果为一个类的对象，那么传入参数时，只能传入本类对象和子类对象，即范围要比形参的范围要小
     //这就是多态思想而已，没有什么新奇的，学会灵活贯通嘛。
    }
    public static void method(internal1 l){
          System.out.println("method");
 }
 public static void method1(nonameinternal l){
     System.out.println("method1");
 }
}
