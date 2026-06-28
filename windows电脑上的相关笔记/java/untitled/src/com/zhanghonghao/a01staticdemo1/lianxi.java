package com.zhanghonghao.a01staticdemo1;

public class lianxi {
    public static void main(String[] args) {
      manager pm = new manager();
      pm.eat();
      cook pp = new cook();
      pp.eat();
      pm.work();
      pp.work();
      worker wp = new worker();
      wp.eat();
      wp.work();
    }
}
