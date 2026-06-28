package com.zhanghonghao.ABSTRACT;

public class jiekou implements show,swim{
    //为了防止接口更新之后使用它的类不会立即报错，从而影响正常行为，于是允许定义默认方法了
    //需要用default修饰
    //public default void show(){}
    //默认方法的注意事项，不强制被重写，但如果被重写，去掉default关键字，public可以省略，default不可以省略，如果实现了多个接口，多个接口中存在相同的默认方法，则子类中必须对这个默认方法进行重写
    //接口中的私有方法,是为了抽取出来其他方法中的重复代码，方便之后的书写的，而不需要被外界访问，所以才存在的,不加static就是给默认方法服务的，加上就是给静态方法服务的。666

    //允许在接口中定义静态方法，用static修饰
    //静态方法只能通过接口名字调用，不能通过类名或者对象名来使用，static 不能省略
    public void school(){
        System.out.println("jajhfe");
    }
   public void show(){
        System.out.println("jajhfe");
   }

    @Override
    public void show2() {
        System.out.println("jajhfe");
    }

    public void swim(){
   System.out.println("jajhfe");
   }
   public static void show5(){
        System.out.println("jajhfe");
   }
}
