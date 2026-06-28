package org.example.ClassDemo1.邮件;

import cn.hutool.extra.mail.MailAccount;
import cn.hutool.extra.mail.MailUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class Demo1 {
    static Properties properties;
    static MailAccount mailAccount ;
    static {
        properties = new Properties();
        try ( InputStream inputStream = Demo1.class.getClassLoader().getResourceAsStream("config.properties")){
            properties.load(inputStream);
            mailAccount = new MailAccount();
            mailAccount.setHost(properties.getProperty("hutool.mail.host"));
            mailAccount.setPort(Integer.parseInt(properties.getProperty("hutool.mail.port")));
            mailAccount.setFrom(properties.getProperty("hutool.mail.from"));
            mailAccount.setUser(properties.getProperty("hutool.mail.user"));
            mailAccount.setPass(properties.getProperty("hutool.mail.pass"));
            mailAccount.setStarttlsEnable(Boolean.parseBoolean(properties.getProperty("hutool.mail.starttls-enable")));
            mailAccount.setDebug(Boolean.parseBoolean(properties.getProperty("hutool.mail.debug")));
            mailAccount.setSslEnable(Boolean.parseBoolean(properties.getProperty("hutool.mail.ssl-enable")));

            //把账户配置到邮件工具类中
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public static void main(String[] args) {
        sendVerifyCode("928198963@qq.com","为父来看你了");
    }
    public static void sendVerifyCode(String email,String code){
        /*List<String>list=new ArrayList<>();
        list.add("13143qq.com");
        list.add("163.com");*/
//        String 注册验证码 = MailUtil.send(mailAccount, list, "注册验证码", code, false);
        //发送附件
        /*File file = new File("C:\\Users\\Administrator\\Desktop\\1.png");
        File file1 = new File("C:\\Users\\Administrator\\Desktop\\1.xsl");*/
        File file = new File("E:\\web\\Mother.zip");
        String 注册验证码 = MailUtil.send(mailAccount, email, "注册验证码","<h1>我来了，生日快乐哦</h1>", true,file);
        System.out.println(注册验证码);
//        String 注册验证码 = MailUtil.send(mailAccount, "63156153@qq.com", "注册验证码", code, false);
        if("success".equals(注册验证码)){
            System.out.println("发送成功");
        }else {
            System.out.println("发送失败");
        }
    }
    //搜嘎肆内
}
