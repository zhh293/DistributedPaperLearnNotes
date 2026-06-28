package com.zhanghonghao.abstractlianxi1;

public class basketballcoach extends coach implements teachball{
    public basketballcoach(String name, int age) {
        super(age, name);
    }

    @Override
    public void teach() {
        System.out.println("教打篮球");
    }
}
