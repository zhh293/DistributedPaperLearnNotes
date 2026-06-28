package com.zhanghonghao.normalclass.正则表达式;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class practice1 {
    public static void main(String[] args) {

        /*Pattern p = Pattern.compile("1[3-9]\\d{9}");
        Matcher m = p.matcher(s);
        while (m.find()) {
            System.out.println(m.group());
        }
        Pattern p1 = Pattern.compile("\\w+@[\\w&&[^_]]{2,6}(\\.[a-zA-Z]{2,3}){1,2}");
        Matcher m1 = p1.matcher(s);
        while (m1.find()) {
            System.out.println(m1.group());

        }*/
        /*Pattern p2=Pattern.compile("010-?[0-9]{6,8}");
        Matcher m2 = p2.matcher(s);
        while (m2.find()) {
            System.out.println(m2.group());

        }
        Pattern p3=Pattern.compile("\\w+@\\w+\\.\\w+");
        Matcher m3 = p3.matcher(s);
        while (m3.find()) {
            System.out.println(m3.group());
        }
        Pattern p5=Pattern.compile("[1-9]\\d{10}");
        Matcher m5 = p5.matcher(s);
        while (m5.find()) {
            System.out.println(m5.group());
        }
        String s2="(010-?[0-9]{6,8})|(\\w+@\\w+\\.\\w+)";
            Pattern p4=Pattern.compile(s2);
            Matcher m4 = p4.matcher(s);
            while (m4.find()) {
                System.out.println(m4.group());
            }
        String s1="\\.";
        System.out.println(s1);
    }*/
        String s = "来黑马程序员学习Java, " +
                "电话: 18512516758, 18512508907" +
                "或者联系邮箱: boniu@itcast.cn, " +
                "座机电 话: 01036517895, 010-9851256" +
                "邮箱: bozai@itcast.cn, " +
                "热线电话: 400-618-9090, 400-618-4000, 4006184000, 4006189090";
        //Pattern p = Pattern.compile("1[3-9][0-9]\\d{8}");
        //Pattern p = Pattern.compile("[\\w]{3,30}@[\\w]{3,30}\\.[a-z]{2,4}");
        //Matcher m = p.matcher(s);
        //while (m.find()) {
        //    System.out.println(m.group());
        //}





    }

}
