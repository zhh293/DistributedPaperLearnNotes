package com.zhanghonghao.normalclass.Date;

public class locatedatetime {
    public static void main(String[] args) {
        /*方法名	说明
static XXX now()	获取当前时间的对象
static XXX of(。。。)	获取指定时间的对象
get开头的方法	获取日历中的年、月、日、时、分、秒等信息
isBefore, isAfter	比较两个 LocalDate
with开头的	修改时间系列的方法
minus开头的	减少时间系列的方法
plus开头的	增加时间系列的方法
表格上方涉及的类：LocalDate、LocalTime、LocalDateTime。
localdatetime可以转化成localdate和localtime，因为localdatetime精确到了秒，而剩下两个只精确到了天
public LocalDate toLocalDate()	LocalDateTime 转换成一个 LocalDate 对象
public LocalTime toLocalTime()	LocalDateTime 转换成一个 LocalTime 对象


*/
        /*工具类
        * Duration
        * public class A09_DurationDemo {
    public static void main(String[] args) {
        // 本地日期时间对象。
        LocalDateTime today = LocalDateTime.now();
        System.out.println(today);

        // 出生的日期时间对象
        LocalDateTime birthDate = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
        System.out.println(birthDate);

        Duration duration = Duration.between(birthDate, today); // 第二个参数减第一个参数
        System.out.println("相差的时间间隔对象：" + duration);

        System.out.println("=========================");
        System.out.println(duration.toDays()); // 两个时间差的天数
        System.out.println(duration.toHours()); // 两个时间差的小时数
        System.out.println(duration.toMinutes()); // 两个时间差的分钟数
        System.out.println(duration.toMillis()); // 两个时间差的毫秒数
        System.out.println(duration.toNanos()); // 两个时间差的纳秒数
    }
}
        *
        *
        *
        *
        *
        *
        *
        *
        *
        *
        * Period
        * public static void main(String[] args) {
    // 当前本地 年月日
    LocalDate today = LocalDate.now();
    System.out.println(today);

    // 生日的 年月日
    LocalDate birthDate = LocalDate.of(2000, 1, 1);
    System.out.println(birthDate);

    Period period = Period.between(birthDate, today);// 第二个参数减第一个参数

    System.out.println("相差的时间间隔对象：" + period);// P25Y6M17D
    System.out.println(period.getYears());
    System.out.println(period.getMonths());
    System.out.println(period.getDays());

    System.out.println(period.toTotalMonths());
}
        *
        *
        *
        *ChronoUnit
        * public class A10_ChronoUnitDemo {
    public static void main(String[] args) {
        // 当前时间
        LocalDateTime today = LocalDateTime.now();
        System.out.println(today);

        // 生日时间
        LocalDateTime birthDate = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
        System.out.println(birthDate);

        System.out.println("相差的年数: " + ChronoUnit.YEARS.between(birthDate, today));
        System.out.println("相差的月数: " + ChronoUnit.MONTHS.between(birthDate, today));
        System.out.println("相差的周数: " + ChronoUnit.WEEKS.between(birthDate, today));
        System.out.println("相差的天数: " + ChronoUnit.DAYS.between(birthDate, today));
        System.out.println("相差的时数: " + ChronoUnit.HOURS.between(birthDate, today));
        System.out.println("相差的分数: " + ChronoUnit.MINUTES.between(birthDate, today));
        System.out.println("相差的秒数: " + ChronoUnit.SECONDS.between(birthDate, today));
        System.out.println("相差的毫秒数: " + ChronoUnit.MILLIS.between(birthDate, today));
        System.out.println("相差的微秒数: " + ChronoUnit.MICROS.between(birthDate, today));
        System.out.println("相差的纳秒数: " + ChronoUnit.NANOS.between(birthDate, today));
        System.out.println("相差的半天数: " + ChronoUnit.HALF_DAYS.between(birthDate, today));
        System.out.println("相差的十年数: " + ChronoUnit.DECADES.between(birthDate, today));
        System.out.println("相差的世纪（百年）数: " + ChronoUnit.CENTURIES.between(birthDate, today));
        System.out.println("相差的千年数: " + ChronoUnit.MILLENNIA.between(birthDate, today));
        System.out.println("相差的纪元数: " + ChronoUnit.ERAS.between(birthDate, today));
    }
}
        *
        * */
        /*有印象就可以了，回来需要的时候直接来找笔记就可以了。。。。。*/





    }
}
