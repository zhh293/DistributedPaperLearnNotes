package com.zhanghonghao.normalclass.Date;

import java.util.Calendar;
import java.util.Date;

public class 日历类详解 {
    public static void main(String[] args) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.MONTH,Calendar.MAY);
        int i = calendar.get(Calendar.MONTH);
        System.out.println(i);
        int i1 = calendar.get(Calendar.DAY_OF_MONTH);
        System.out.println(i1);
        calendar.add(Calendar.MONTH,1);
        int i2 = calendar.get(Calendar.MONTH);
        System.out.println(i2);
        int i3 = calendar.get(Calendar.DAY_OF_WEEK_IN_MONTH);
        System.out.println(i3);
        Date date = calendar.getTime();
        System.out.println(date);
    }
    /*1. 获取 Calendar 实例
    由于是抽象类，需通过getInstance()获取实例：

    java
            运行
import java.util.Calendar;

    // 获取默认时区和地区的Calendar实例（通常是GregorianCalendar）
    Calendar calendar = Calendar.getInstance();

    // 也可指定时区和Locale
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
2. 核心字段（Field）
    Calendar定义了大量静态常量表示日期时间字段，常用的有：

    字段常量	含义	范围（注意特殊值）
    YEAR	年份	如 2024
    MONTH	月份	0-11（0 表示 1 月，11 表示 12 月）
    DAY_OF_MONTH	月中的天数	1-31
    HOUR	小时（12 小时制）	0-11
    HOUR_OF_DAY	小时（24 小时制）	0-23
    MINUTE	分钟	0-59
    SECOND	秒	0-59
    DAY_OF_WEEK	周中的天数	1-7（1 表示周日，2 表示周一...7 表示周六）

            3. 设置日期时间
            java
    运行
    Calendar calendar = Calendar.getInstance();

// 方法1：单独设置字段
calendar.set(Calendar.YEAR, 2024);          // 设置年份为2024
calendar.set(Calendar.MONTH, Calendar.MAY); // 设置月份为5月（注意Calendar.MAY=4）
calendar.set(Calendar.DAY_OF_MONTH, 20);    // 设置日期为20日

// 方法2：一次性设置年月日时分秒
calendar.set(2024, Calendar.MAY, 20, 15, 30, 45); // 2024-05-20 15:30:45




    4. 获取日期时间字段
            java
    运行
    Calendar calendar = Calendar.getInstance();

    int year = calendar.get(Calendar.YEAR);
    int month = calendar.get(Calendar.MONTH) + 1; // 转换为1-12月
    int day = calendar.get(Calendar.DAY_OF_MONTH);
    int hour = calendar.get(Calendar.HOUR_OF_DAY); // 24小时制
    int minute = calendar.get(Calendar.MINUTE);
    int second = calendar.get(Calendar.SECOND);

System.out.printf("%d-%02d-%02d %02d:%02d:%02d",
    year, month, day, hour, minute, second);




5. 日期计算（加减操作）
    使用add()方法进行日期偏移：

    java
            运行
    Calendar calendar = Calendar.getInstance(); // 假设当前是2024-05-20

// 加3天
calendar.add(Calendar.DAY_OF_MONTH, 3); // 变为2024-05-23

// 减2个月
calendar.add(Calendar.MONTH, -2); // 变为2024-03-23

// 加1年
calendar.add(Calendar.YEAR, 1); // 变为2025-03-23




    6. 与 Date 类的转换
    Calendar和Date可以相互转换：

    java
            运行
    // Calendar -> Date
    Calendar calendar = Calendar.getInstance();
    Date date = calendar.getTime();

    // Date -> Calendar
    Date date = new Date();
    Calendar calendar = Calendar.getInstance();
calendar.setTime(date); // 将Date设置到Calendar中


7. 其他常用方法
    getTimeInMillis()：获取自 1970-01-01 00:00:00 GMT 以来的毫秒数（类似Date.getTime()）
    setFirstDayOfWeek(int day)：设置一周的第一天（默认周日，可改为周一）
    getActualMaximum(int field)：获取指定字段的最大值（如当月最大天数）
    java
            运行
    int maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH); // 当月最大天数*/
}
