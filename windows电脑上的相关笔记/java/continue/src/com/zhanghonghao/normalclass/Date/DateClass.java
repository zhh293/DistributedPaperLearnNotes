package com.zhanghonghao.normalclass.Date;

import java.util.Date;

public class DateClass {
    public static void main(String[] args) {
      /*
      * 全世界时间有一个统一的标准
      * 格林威治GMT时间被取消掉了，现在由原子钟决定，利用铯原子的震动频率计算出来的时间，作为世界标准时间UTC
      * 中国标准时间=世界标准时间+8小时
      * 1秒=1000毫秒
      * 1毫秒=1000微妙
      * 1微秒=1000纳秒
      *
public Date () 创建 Date 对象，表示当前时间
public Date (long date) 创建 Date 对象，表示指定时间
        public void setTime (long time) 设置 / 修改毫秒值
        public long getTime () 获取时间对象的毫秒值

      *
      *
      *
      *
      *
      *
      *
      *
      *
      * */

        Date date = new Date();
        System.out.println(date);
        Date date1 = new Date(90000L);
        System.out.println(date1);
        Date date2 = new Date();
        date2.setTime(1000L);
        System.out.println(date2);


        System.out.println(date2.getTime());
        System.out.println(date1.getTime());

        System.out.println(date2.getTime()-date1.getTime());

        /*Date date = new Date();
        System.out.println(date);
        Date date1 = new Date(90000L);
        System.out.println(date1);
        date1.setTime(1000L);
        System.out.println(date1);
        Date date4=new Date(0L);
        date4.setTime(365L*24*60*60*1000);
        System.out.println(date4);
        Date date2 = new Date(100L);
        Date date3 = new Date(10L);
        long time = date2.getTime();
        long time1 = date3.getTime();
        System.out.println(time>time1);*/
    }
}
