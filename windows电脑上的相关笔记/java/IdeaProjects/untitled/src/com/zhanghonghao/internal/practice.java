package com.zhanghonghao.internal;

public class practice implements nonameinternal{
    private  int a=10;

    @Override
    public void display() {

    }

    class inner{
        private int a=20;
        public void show(){
            int a=30;
            System.out.println(new practice().a);
            System.out.println(this.a);
            System.out.println(a);
        }
    }
    public inner getInner(){
        return new inner();
    }
}
