package com.zhh.handsome.保护性暂停扩展;

import java.util.HashMap;
import java.util.Map;

public class Demo {



    //用户id一定要与信件id对应，而信件id是自增的，所以创建用户的时候也要从0开始自增，噢噢噢噢哦哦哦，我是大帅逼，无敌之人
    //准备学习深度学习!!!!!!!!!!!!!!!!!!!
    //加油努力，为了人民币。。。。。。。。。。。。。。

    class People extends Thread {
        private MailBox mailBox;
        private String name;
        private Integer id;
        public People(String name,Integer id,MailBox mailBox){
            this.name = name;
            this.mailBox = mailBox;
            this.id = id;
        }
        //收信
        @Override
        public void run() {
            System.out.println(name + "开始收信");
            Object mail=mailBox.getMail(3000,id);
            if(mail!=null){
                System.out.println(name + "收到信了，内容是："+mail);
            }else{
                System.out.println(name + "等信超时了");
            }
        }

    }

    class MailBox{
       private final Map<Integer,Object>mail=new HashMap<Integer,Object>();
        //收信
        public synchronized Object getMail(long waitTime,Integer mailId){
            long startTime = System.currentTimeMillis();
            long passTime = 0;
            while (mail.get(mailId)==null){
                long wait = waitTime - passTime;
                if(wait<=0){
                    break;
                }
                try {
                    this.wait(wait);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                passTime = System.currentTimeMillis() - startTime;
            }
            Object returnMail = mail.get(mailId);
            mail.remove(mailId);
            return returnMail;
        }
        //寄信
        public synchronized void setMail(Object mail,Integer mailId){
            //模拟邮递员送信需要时间
            try {
                Thread.sleep(1000);
                this.mail.put(mailId,mail);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            this.notifyAll();
        }
    }
    private static Integer mailId=0;
    private Integer generateMailId(){
        synchronized (Demo.class){
            return ++mailId;
        }
    }

    class Postman extends Thread{
        private  MailBox mailBox;
        private String name;
        private Object mail;
        public Postman(String name,Object mail,MailBox mailBox){
            this.mailBox = mailBox;
            this.name = name;
            this.mail = mail;
        }

        //寄信
        @Override
        public void run() {
            System.out.println(name + "开始寄信");
            mailBox.setMail(mail,generateMailId());
            System.out.println(name + "信寄出去了，内容是："+mail);
        }
    }

    public static void main(String[] args) {

    }























}
