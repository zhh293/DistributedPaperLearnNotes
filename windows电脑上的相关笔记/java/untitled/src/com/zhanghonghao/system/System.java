package com.zhanghonghao.system;

public class System {
    public static void main(String[] args) {
        //system也是一个工具类，有许多api
        //exit，终止虚拟机，形参是一个状态码，0表示当前虚拟机是正常停止的，非0是异常停止
        //arraycopy，数组拷贝
        //currentTimeMills，返回系统时间的毫秒值,可以用来测试程序运行的时间
        Long l= java.lang.System.currentTimeMillis();
        java.lang.System.out.println(l);
        int []arr=new int[10];
        int[]arr1={1,2,3,4,5,6,7,8,9,10};
        //第一个参数是数据从哪个数组来
        //参数二从数组中第几个索引开始拷贝
        //参数三是要拷贝到那个数组中
        //参数四是目的地数组的索引
        //参数五拷贝的个数
        java.lang.System.arraycopy(arr1,0,arr,0,arr.length);
        for (int i=0;i<arr.length;i++){
            java.lang.System.out.println(arr[i]);
        }
        //object和objects。。。object是java的顶级父类，object类中的方法随便被使用
        //object的构造方法就只有空参构造，因为没有一个属性是所有子类都共有的，提取不出来
        Object object=new Object();
        String str1=object.toString();
        java.lang.System.out.println(str1);
        //细节,System:类名。out是静态变量。连在一起就是用来获取打印的对象，当我们打印一个对象的时候
        //底层会调用tostring方法，把对象变成字符串，然后打印在控制台上，如果你不想打印出来的是地址值
        //那么你就重写tostring方法，不再使用object父类自带的tostring方法
        String str2="abcd";
        StringBuilder str3=new StringBuilder(str2);
        java.lang.System.out.println(str2.equals(str3));
        //因为equals方法是被s调用的，而s是字符串
        //所以equals要看String类中的
        //字符串中的equals方法先判断参数是否为字符串，如果是再比较内部属性
        java.lang.System.out.println(str3.equals(str2));
        //因为equals方法是被s调用的，但是Stringbuilder中没有重写equals方法，使用的是object中的方法
        //在object中的方法比较的是地址值，所以是false
        //clone方法，将对象的属性值完全拷贝给对象，也叫对象拷贝，对象复制
        /*//1. 先创建一个对象
int [] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0};
User u1 = new User ( id: 1, username: "zhangsan", password: "1234qwer", path: "girl111",data);
//2. 克隆对象
// 细节：
// 方法在底层会帮我们创建一个对象，并把原对象中的数据拷贝过去。
// 书写细节：
//1. 重写 Object 中的 clone 方法
//2. 让 javabean 类实现 Cloneable 接口
//3. 创建原对象并调用 clone 就可以了。
User u2 = (User) u1.clone ();
//验证浅克隆
int []arr=u1.getData();
arr[0]=100;
System.out.println(u1);
System.out.println(u2);
//此时两个对象中的属性值都会改变
}
}*/
        //拷贝方式一，浅拷贝，二，深拷贝
        /*把 A 对象的属性值完全拷贝给 B 对象，也叫对象拷贝，对象复制
    浅克隆：不管对象内部的属性是基本数据类型还是引用数据类型，都完全拷贝过来
  深克隆：
基本数据类型拷贝过来
字符串复用
引用数据类型会重新创建新的*/
    }



    //equals方法，比较两个对象是否相等,重写之后的的equals方法比较的就是对象中的属性值了，不是地址值了
}
//深克隆的代码，自己手写的哦，太麻烦了
/*@Override
protected Object clone () throws CloneNotSupportedException {
// 调用父类中的 clone 方法
// 相当于让 Java 帮我们克隆一个对象，并把克隆之后的对象返回出去。
// 先把被克隆对象中的数组获取出来
int [] data = this.data;
// 创建新的数组
int [] newData = new int [data.length];
// 拷贝数组中的数据
for (int i = 0; i < data.length; i++) {
newData [i] = data [i];
}
// 调用父类中的方法克隆对象
User u = (User) super.clone ();
// 因为父类中的克隆方法是浅克隆，替换克隆出来对象中的数组地址值
u.data = newData;
return u;
}*/