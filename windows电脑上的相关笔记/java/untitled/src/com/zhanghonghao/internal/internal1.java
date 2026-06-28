package com.zhanghonghao.internal;

public class internal1 {
    //类的五大成员：属性，方法，构造方法，内部类，代码块
    //内部类，在一个类中再定以一个类
    //内部类可以直接访问外部类成员，包括私有
    //外部类要访问内部类成员，必须创建对象，成员内部类可以被修饰符修饰
    //jdk16之前不能定义静态变量，16开始才可以定义
    //1.在外部类中编写方法，对外提供内部类对象2.外部类名.内部类名  对象名=new 外部类名.new 内部类名
    //内部类用private修饰时，用第二种，否则，用第一种


    public internal1(){}
   String  carname;
    int carage;
    String carcolor;
    engine engine=new engine();
    public void show(internal1 l,engine e){
        System.out.println(l.carname);
        System.out.println(e.engineage);
    }
   class engine{
        String enginename;
        int engineage;
        public void show(){
            System.out.println(carname);
            System.out.println(enginename);
        }
    }
  public engine getengine(){
        return engine;
  }




}
