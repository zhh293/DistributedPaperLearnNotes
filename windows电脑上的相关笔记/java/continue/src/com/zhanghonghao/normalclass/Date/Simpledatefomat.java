package com.zhanghonghao.normalclass.Date;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Simpledatefomat {
    public static void main(String[] args) throws ParseException {
        //作用：格式化：把时间变成我们喜欢的模样
        //解析：那字符串表示的时间变成Date对象
        SimpleDateFormat sdf = new SimpleDateFormat("ss/mm/HH dd/MM/yyyy");
        System.out.println(sdf.format(new Date(0L)));
        SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss E a");
        System.out.println(sdf2.format(new Date()));
        String str="2023-11-11 11:11:11";
        SimpleDateFormat sdf3 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date parse = sdf3.parse(str);
        String format = sdf3.format(parse);
        System.out.println(format);
        long time = parse.getTime();
        System.out.println(time);
        String str2="2000-11-11";
        SimpleDateFormat sdf4 = new SimpleDateFormat("yyyy-MM-dd");
        Date parse2 = sdf4.parse(str2);
        SimpleDateFormat sdf5 = new SimpleDateFormat("yyyy年MM月dd日");
        String format2 = sdf5.format(parse2);
        System.out.println(format2);
    }
}
