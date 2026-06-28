package com.zhanghonghao.normalclass.internetpachong;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class 网络爬虫 {
    public static void main(String[] args) throws IOException {
        //到网络上爬取数据，制造假数据
        //1.爬取姓氏
        /*String s1="https://hanyu.baidu.com/shici/detail?pid=0b2f26d4c0dd3be693fdb1137ee1b0d&from=kg0";
        String boyName="http://www.haoming8.cn/baobao/10881.html";
        String girlName="http://www.haoming8.cn/baobao/7641.html";
        String str1=webCrawler(s1);
        String str2=webCrawler(boyName);
        String str3=webCrawler(girlName);
        //System.out.println(str1);
        //System.out.println(str2);
        //通过正则表达式把符合要求的数据获取出来
        ArrayList <String> list=getData(str1,"(.{4})(，|。）");
        System.out.println(list);


    }
    public static String webCrawler(String url) throws IOException {
        //定义一个
        StringBuilder sb = new StringBuilder();
        URL u = new URL(url);
        URLConnection con=u.openConnection();
        InputStreamReader isr=new InputStreamReader(con.getInputStream());
        int ch;
        while((ch=isr.read())!=-1){
            sb.append((char)ch);
        }
        isr.close();
        return sb.toString();
    }
    public static ArrayList<String> getData(String name,String regex){
        ArrayList<String>list=new ArrayList<>();
        Pattern p=Pattern.compile(regex);
        Matcher m=p.matcher(name);
        while(m.find()){
            list.add(m.group());
        }
        return list;*/
        /*String url="https://hanyu.baidu.com/shici/detail?pid=0b2f26d4c0ddb3ee693fdb1137ee1b0d&from=kg0";
        String s = webCrawler(url);
        //Pattern p=Pattern.compile("<div class=\"text\">.*</div>");
        Pattern p=Pattern.compile("[^[\\w\"].]{4}([，。])");
        //System.out.println(s);
        Matcher m=p.matcher(s);
        while(m.find()){
            System.out.println(m.group());
        }


    }
    public static String webCrawler(String url) throws IOException {
        //定义一个
        StringBuilder sb = new StringBuilder();
        URL u = new URL(url);
        URLConnection con=u.openConnection();
        InputStreamReader isr=new InputStreamReader(con.getInputStream());
        int ch;
        while((ch=isr.read())!=-1){
            sb.append((char)ch);
        }
        isr.close();
        return sb.toString();
    }*/
       /* URL url=new URL("https://www.bilibili.com/video/BV1yv4y1n7CH/?spm_id_from=333.337.search-card.all.click&vd_source=47177de32b036174546b48055e4a9354");
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestMethod("GET");
        urlConnection.connect();
        BufferedReader br=new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
        String line="";
        while ((line=br.readLine())!=null){
            System.out.println(line);
        }
        br.close();*/
        DatagramSocket socket = new DatagramSocket(10086);
        String string="我是你爸！！！！";
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        InetAddress address = InetAddress.getByName("127.0.0.1");
        int port = 10086;
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, port);
        socket.send(packet);
        byte[] buffer = new byte[1024];
        DatagramPacket packet1=new DatagramPacket(buffer,buffer.length);
        socket.receive(packet1);
        byte[] data = packet1.getData();
        System.out.println(new String(data).trim());
        socket.close();
    }
}
// 该方法是阻塞的
// 程序执行到这一步的时候，会在这里死等
// 等发送端发送消息
/*System.out.println(11111);
ds.receive(dp);
System.out.println(2222);*/
