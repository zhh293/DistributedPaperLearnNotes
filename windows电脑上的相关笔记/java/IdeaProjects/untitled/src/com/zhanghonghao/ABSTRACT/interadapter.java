package com.zhanghonghao.ABSTRACT;

public abstract class interadapter implements swim{
//当一个接口中抽象方法过多时，但我们只想用其中的一部分时，就可以使用适配器
//编写中间类××Adapter，实现对应的接口，对接口中的抽象方法进行空实现，让真正的实现类继承中间类，并重写需要的方法，为了避免其他类创建适配器的对象，中间的适配器类用abstract进行修饰

    @Override
    public void swim() {}
    public void show(){}

}
