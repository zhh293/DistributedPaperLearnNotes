package com.zhanghonghao.normalclass.Date;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Set;

public class JDK8 {
    public static void main(String[] args) {
        //JDK8新增的时间相关类
        //JDK8中的时间类中的对象是不可被修改的，跟String一样，修改的时候会创建一个新的对象，原来的对象不变
        //这样就解决了多线程中时间被反复修改的问题
        /*JDK8 时间（新增的）
01 Date 类
ZoneId：时区
Instant：时间戳
ZoneDateTime：带时区的时间

02 日期格式化类：
SimpleDateFormat
DateTimeFormatter
用于时间的格式化和解析

03 日历类：
Calendar
LocalDate：年、月、日
LocalTime：时、分、秒
LocalDateTime：年、月、日 时、分、秒

04 工具类
Duration：时间间隔（秒，纳秒）
Period：时间间隔（年，月，日）
ChronoUnit：时间间隔（所有单位）*/
        //获取所有时区
        Set<String> availableZoneIds = ZoneId.getAvailableZoneIds();
        System.out.println(availableZoneIds.size());

        for (String zoneId : availableZoneIds) {
            System.out.println(zoneId);
        }
        //获取当前系统的默认时区
        ZoneId zoneId=ZoneId.systemDefault();
        System.out.println(zoneId);
        //获取指定的时区
        ZoneId zoneId1 = ZoneId.of("America/Los_Angeles");
        System.out.println(zoneId1);
        /*Instant 时间戳
方法名	                  说明
static Instant now()	获取当前时间的 Instant 对象（标准时间）
static Instant ofXxxx(long epochMilli)	根据（秒 / 毫秒 / 纳秒）获取 Instant 对象
ZonedDateTime atZone(ZoneId zone)	指定时区
boolean isXxx(Instant otherInstant)	判断系列的方法
Instant minusXxx(long millisToSubtract)	减少时间系列的方法
Instant plusXxx(long millisToSubtract)	增加时间系列的方法*/
        Instant instant = Instant.now();
        System.out.println(instant);
        /*instant.getEpochSecond() 获取 instant 从时间原点开始的秒数（忽略纳秒）。
Instant.ofEpochSecond(秒数) 会创建一个新的 Instant 对象，以传入的秒数为基础，纳秒默认为 0。
例如，若 instant.getEpochSecond() 是 1716290096，则 instant1 表示 1716290096 秒（纳秒为 0）从时间原点开始的时刻，
输出可能类似 2024-03-20T12:34:56Z。*/
        Instant instant1 = Instant.ofEpochSecond(instant.getEpochSecond());
        System.out.println(instant1);
        ZonedDateTime zonedDateTime = instant.atZone(zoneId);
        System.out.println(zonedDateTime);
        Instant instant2 = Instant.ofEpochMilli(0L);
        Instant instant3 = Instant.ofEpochMilli(1000L);
        System.out.println(instant2);
        System.out.println(instant3);
        //判断时间先后，判断调用者代表的时间是否在参数表示时间的前面或者后面
        boolean before = instant3.isBefore(instant2);
        boolean after = instant3.isAfter(instant2);
        System.out.println(before);
        System.out.println(after);
/*static ZonedDateTime now()	获取当前时间的ZonedDateTime对象
static ZonedDateTime ofXxxx(。。。)	获取指定时间的ZonedDateTime对象
ZonedDateTime withXxx(时间)	修改时间系列的方法
ZonedDateTime minusXxx(时间)	减少时间系列的方法
ZonedDateTime plusXxx(时间)	增加时间系列的方法

// 细节：
//JDK8 新增的时间对象都是不可变的
// 如果我们修改了，减少了，增加了时间
// 那么调用者是不会发生改变的，产生一个新的时间。

static DateTimeFormatter ofPattern(格式)	获取格式对象
String format(时间对象)	按照指定方式格式化


*/
        ZonedDateTime zonedDateTime1 = instant.atZone(zoneId);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        System.out.println(zonedDateTime1.format(formatter));


    }
}
