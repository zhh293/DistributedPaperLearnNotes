package com.zhanghonghao.normalclass.Date;

import java.util.Calendar;
import java.util.Date;

public class CalendarPractice {
    public static void main(String[] args) {
        /*Calendar calendar = Calendar.getInstance();
        System.out.println(calendar);
        int i1 = calendar.get(Calendar.YEAR);
        System.out.println(i1);
        calendar.add(Calendar.YEAR,1);
        System.out.println(calendar);*/



        /*
方法名

public final Date getTime()获取日期对象

public final setTime(Date date)给日历设置日期对象

public long getTimeInMillis()拿到时间毫秒值

public void setTimeInMillis(long millis)给日历设置时间毫秒值

public int get(int field)取日历中的某个字段信息

public void set(int field,int value)修改日历的某个字段信息

public void add(int field,int amount)某个字段增加/减少指定的值

*/
   Calendar cal = Calendar.getInstance();
   /*//1.获取日历对象
//细节:Calendar是一个抽象类，不能
//底层原理:
是通过一个静态方法获取到子类对象
//会根据系统的不同时区来获取不同的日历对象。
//把会把时间中的纪元，年，月，日，时，分，秒，星期，的都放到一个数组当中
//细节2:
//月份:范国0~11如果获取出来的是0.那么实际上是1月
//星期:在老外的眼里，星期日是一周中的第一天
1(星期日)
2(星期一)3(星期二)
4(星期三)
5(星期四)
6(星期五)
7(星期六)

*/
  Date date = new Date(0L);
  cal.setTime(date);
  System.out.println(cal);
        /*int i = cal.get(1);
        int i1 = cal.get(2);
        int i2 = cal.get(3);
        int i3 = cal.get(4);
        int i4 = cal.get(5);
        int i5 = cal.get(6);
        int i6 = cal.get(7);
        System.out.println(i+"year");
        System.out.println(i1+1+"month");
        System.out.println(i2);
        System.out.println(i3);
        System.out.println(i4+"day");
        System.out.println(i5);
        System.out.println(i6);*/
        int i = cal.get(Calendar.DAY_OF_WEEK);
        System.out.println(getweek(i));
        cal.set(Calendar.DAY_OF_WEEK,4);
        cal.set(Calendar.HOUR_OF_DAY,8);
        cal.add(Calendar.DAY_OF_WEEK,1);
        System.out.println(cal);
        cal.add(Calendar.DAY_OF_WEEK,-1);
        System.out.println(cal);


    }
    public static String getweek(int index){
        String[]arr={"","星期日","星期一","星期二","星期三","星期四","星期五","星期六"};
        return arr[index];
    }
}
