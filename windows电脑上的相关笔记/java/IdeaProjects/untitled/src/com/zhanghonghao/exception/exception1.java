package com.zhanghonghao.exception;

public class exception1 {
    public static void main(String[] args) {
        //异常介绍
        //error，exception(runtimexception,其他异常)
        //runtimeexception及其子类，运行时的异常，exception是最上层父类
        //编译时异常在编译阶段就会抛出异常，继承exception
        //扩展，设置两种异常的原因
        //异常在代码中的作用：1.查询bug的关键信息2.异常可以作为特殊的返回值，告诉底层执行情况
        /*student student = new student();
        student.setAge(15);//知道异常之后，可以自己偷偷处理，也可以打印在控制台上
        student.setName("张三");*/
        //jvm默认的处理方法，自己处理，调用者处理
        //1.JVM处理，把异常的名称等信息打印出来，并且程序停止运行，下面的代码不会执行了
        //自己处理，捕获异常，格式
        //try{}catch(){}
        int []arr={1,2,3,4,5};
        try{
            System.out.println(arr[10]);
        }catch(Exception e){
            System.out.println(e);
        }
        //或者
        try{
            System.out.println(arr[10]);
        }catch (ArrayIndexOutOfBoundsException e){
            System.out.println("索引越界了");
        }
        //灵魂四问
        //1.try没有异常该怎么办呢，不执行catch中的代码
        //2.如果try中出现了多个问题，怎么执行，可以写多个catch与之对应，也可以在一个catch的形参中传入多个异常类型，用|隔开
        //同时，父类异常一定要写在子类下面，你也可以用exception捕获所有异常
        //3.try中的问题没有被捕获怎么办，那么JVM默认处理，try catch白写了
        //4.try中遇到了问题，try下面的代码还会执行吗，不会，直接跳转到catch中的代码并且执行，如果没有对应的catch，那么jvm默认处理
        //异常中的常见方法
        //getmessage,tostring,printstacktrace
        //System.err.println(123);
        //抛出异常，throws，throw，注意：写在方法定义处，表示声明一个异常。告诉调用者，使用本方法可能会有哪些异常。
         /*public void 方法()throws 异常类名1,异常类名2...{
   ...
}编译时异常：必须要写。
运行时异常：可以不写。*/
        /*throw
注意：写在方法内，结束方法。手动抛出异常对象，交给调用者。方法中下面的代码不再执行了。public void 方法(){
    throw new NullPointerException();
}*/
        int[]brr={};
        int mmax= 0;
        try {
            mmax = getmax(brr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println(mmax);




    }
    public static int getmax(int[]arr){
        if(arr==null||arr.length==0){
            throw new RuntimeException();
        }
        int max = arr[0];
        for(int i=1;i<arr.length;i++){
            if(arr[i]>max){
                max = arr[i];
            }
        }
        return max;
    }

}
