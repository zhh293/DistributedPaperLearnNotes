package com.zhanghonghao.bagfinalchunk;

public class bag {
    //包就是文件夹
    //包名的规则：公司域名反写+包的作用
    //使用同一个包中的类时，不需要导包
    //使用java.lang也不需要
    //其他情况都需要导包，如果同时使用两个包中的同类名，需要用全类名
    String name;
    int age;
    //静态代码块
    static {
        //通过static关键字修饰，随着类的加载而加载，自动触发，只执行一次
        //在类加载的时候，需要数据初始化的时候用
        System.out.println("六百六十六，程序加载完成了");
    }
    {
        System.out.println("开始创建对象了");
    }
    public bag(String name, int age) {
        System.out.println("带参构造");
        this.name = name;
        this.age = age;
    }
    public bag(){
        System.out.println("空参构造");
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public int getAge() {
        return age;
    }
    public void setAge(int age) {
        this.age = age;
    }

}
