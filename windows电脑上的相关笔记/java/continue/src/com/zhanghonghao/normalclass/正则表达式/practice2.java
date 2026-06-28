package com.zhanghonghao.normalclass.正则表达式;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class practice2 {
    public static void main(String[] args) {
        String str="Java自从95年问世以来,abbbbbbbbbbbbaaaaaaaaaaa,经历了很多版本,目前企业中运用最多的是Java8和Java11,"+"因为这两个版本是长期支持版本,下一个长期支持版本是Java17,期待Java17登上历史舞台";
        Pattern p1=Pattern.compile("Java(?=8|11|17)");
        //?理解为前面的数据Java,=表示在Java后面要跟随的数据，但是获取时只获取前半部分,如果是:号，则全部都获取
        Matcher m1=p1.matcher(str);
        while(m1.find()){
            System.out.println(m1.group());
        }
        Pattern p2=Pattern.compile("((?i)Java)(?=8|11|17)");
        //?i表示的是忽略大小写
        Matcher m2=p2.matcher(str);
        while(m2.find()){
            System.out.println(m2.group());
        }
        Pattern p3=Pattern.compile("((?i)Java)\\d{1,2}");
        Matcher m3=p3.matcher(str);
        while(m3.find()){
            System.out.println(m3.group());

        }
        Pattern p4=Pattern.compile("((?i)Java)(?!8|11|17)");
        Matcher m4=p4.matcher(str);
        while(m4.find()){
            System.out.println(m4.group());
        }
        //贪婪爬取和非贪婪爬取
        //只写+和*表示贪婪匹配，+？或者*？非贪婪爬取
        Pattern p5=Pattern.compile("ab+");
        Matcher m5=p5.matcher(str);
        while(m5.find()){
            System.out.println(m5.group());
        }
        Pattern p6=Pattern.compile("ab+?");
        Matcher m6=p6.matcher(str);
        while(m6.find()){
            System.out.println(m6.group());
        }
        //replaceAll按照正则表达式的规则进行替换，这个方法运行时会先不断查找连续地满足正则表达式的字符串，直到遇到不满足的字符串，才会把之前的全部替换
        //split 按照正则表达式切割字符串
      String s1=  str.replaceAll("ab\\w{1,100}","ok");
        System.out.println(s1);
      String[] s2=str.split("Java\\d{0,2}");
      for(String s:s2){
          System.out.println(s);

      }
    }
}
