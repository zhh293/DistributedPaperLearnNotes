package com.zhanghonghao.normalclass.正则表达式;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class practice {
    public static void main(String[] args) {
        String telephone = "12334445";
        //爬虫
        //本地爬虫和网络爬虫
        //Pattern,表示正则表达式
        //Matcher,作用是按照正则表达式去读取字符串
        String str="Java自从95年问世以来,经历了很多版本,目前企业中运用最多的是Java8和Java11,"+"因为这两个版本是长期支持版本,下一个长期支持版本是Java17,期待Java17登上历史舞台";
       Pattern p= Pattern.compile("Java\\d{0,2}");
       Matcher m= p.matcher(str);
       //find方法会在串中寻中是否有满足规则的子串,没有返回false,有则返回true,在底层记录子串的起始索引和结束索引+1
        //group方法底层会根据find方法记录的索引进行字符串的截取
        //然后把截取的小串返回
        //第二次调用find方法时,会继续读取后面的内容,直到读取到第二个满足要求的子串
        //再调用group方法截取第二个字串

       while(m.find()){
           System.out.println(m.group());
       }
       //网络爬虫 
     /* 扩展需求2：
        把连接：https://m.sengzan.com/jiaoyu/29104.html?ivk_sa=1025883i
        中所有的身份证号码都爬取出来。 */

    //创建一个URL对象

            // 创建一个URL对象
            try {
                URL url = new URL("https://cn.bing.com/search?q=%E8%B5%B7%E7%82%B9%E4%B8%AD%E6%96%87%E7%BD%91&form=ANNTH1&refig=6798665856c64024b5708b613d9d2fa5&pc=CNNDDB&pq=qi%27dian&pqlth=7&assgl=5&sgcn=%E8%B5%B7%E7%82%B9%E4%B8%AD%E6%96%87%E7%BD%91&qs=SC&smvpcn=0&swbcn=10&sctcn=0&sc=10-7&sp=2&ghc=0&cvid=6798665856c64024b5708b613d9d2fa5&clckatsg=1&hsmssg=0");
                URLConnection conn = url.openConnection();
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                String regex = "[1 - 9]\\d{17}";
                Pattern pattern = Pattern.compile(regex);
                while ((line = br.readLine())!= null) {
                    Matcher matcher = pattern.matcher(line);
                    while (matcher.find()) {
                        System.out.println(matcher.group());
                    }
                }
                br.close();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

}
