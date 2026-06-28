package com.zhanghonghao.ABSTRACT;

public interface show {
    public abstract void school();
    public default void show() {
        show3();
    }
    public static void show1(){
        System.out.println("show1");
        show5();
    }
    public abstract void show2();
    private static void show3(){
        System.out.println("show3");
    }
    private void show4(){
        System.out.println("show4");
    }
    private static void show5(){
        System.out.println("show5");
    }
}
