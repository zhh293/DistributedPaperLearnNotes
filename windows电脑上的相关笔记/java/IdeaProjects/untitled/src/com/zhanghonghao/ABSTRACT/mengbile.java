package com.zhanghonghao.ABSTRACT;

public class mengbile implements show{
    public static void main(String[] args) {
        jiekou.show5();
        show.show1();
        jiekou j=new jiekou();
        j.show();
        //接口代表规则，是行为的抽象，想要让哪个类拥有一个行为，就让这个类实现对应的接口就可以了
        //当一个方法的参数是接口时，可以传递接口所有实现类的对象，这种方式称之为多态接口
        show3(j);
        mengbile m=new mengbile();
        m.show2();
        show3(m);
    }

    @Override
    public void school() {

    }

    @Override
    public void show2() {

    }
    public static void show3(show j){
        System.out.println("hahaha");
    }
}
