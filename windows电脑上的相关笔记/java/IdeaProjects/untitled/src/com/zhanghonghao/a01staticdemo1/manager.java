package com.zhanghonghao.a01staticdemo1;

public class manager extends worker {
    int reward;
    public manager() {}
    public manager(int reward, String name,int salary,String ID) {
        super(name,ID,salary);
        this.reward = reward;
    }
    public void setReward(int reward) {
        this.reward = reward;
    }
    public int getReward() {
        return reward;
    }
    public void work() {
        System.out.println("管理其他人");
    }
    public void eat(){
        System.out.println("吃米饭");
    }
}
