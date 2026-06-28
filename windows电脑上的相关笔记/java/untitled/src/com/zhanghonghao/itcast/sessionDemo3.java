package com.zhanghonghao.itcast;

import java.util.*;

public class sessionDemo3 {
      static int []arr=new int[5];
       int[]count={20,50,100,200,500};
       public void deposit(int []banknotesCount){
              for(int i=0;i<banknotesCount.length;i++){
                      arr[i]+=banknotesCount[i];
              }
              System.out.println(Arrays.toString(arr));
       }
       public int[] withdraw (int amount){
           int[]brr=new int[5];
           int[]crr=arr.clone();
              for(int i= arr.length-1;i>=0;i++){
                  while(amount>=count[i]&&crr[i]>0){
                             amount-=count[i];
                             brr[i]++;
                             crr[i]--;
                             if(amount==0){
                                    arr=crr.clone();
                                    return brr;
                             }
                     }
              }
              return new int[]{-1};
       }
//    session的实现是依赖于cookie的
    /*服务器关闭 / 重启时，内存中的 Session 会被清空
    默认存储方式：大多数 Web 框架（如 Tomcat、Spring Boot、Node.js 等）默认将 Session 数据存储在服务器内存中。
    内存的临时性：服务器进程停止（如关机、重启、部署更新）时，内存中的数据会完全丢失，所有未持久化的 Session 都会被清空。
    持久化的作用：若将 Session 数据持久化到数据库（如 MySQL）或缓存（如 Redis）中，服务器重启后可通过读取持久化存储恢复 Session，避免数据丢失。
            2. 浏览器关闭时，Session 的存活取决于 Cookie 的类型
    Session 的识别依赖于客户端的 Cookie（存储 Session ID），而浏览器关闭对 Session 的影响分为两种情况：
    情况一：使用默认的 Session Cookie（临时 Cookie）
    特点：默认情况下，浏览器生成的 Cookie 是临时 Cookie，未设置过期时间，仅在浏览器窗口存活期间有效。
    行为：
    当浏览器正常关闭（关闭所有窗口）时，临时 Cookie 会被浏览器自动删除。
    下次访问网站时，浏览器因丢失 Session ID 的 Cookie，无法识别原有 Session，服务器会创建新的 Session。
    原有 Session 在服务器端是否保留？
    若服务器未重启且 Session 未过期（默认通常为 30 分钟），则内存中的 Session 数据仍存在，但因客户端丢失 Session ID，无法再访问该 Session。
    情况二：使用持久化 Cookie（设置过期时间）
    特点：通过程序将 Cookie 的过期时间设置为某个具体时间（如 max-age=86400，即 1 天）。
    行为：
    即使浏览器关闭，持久化 Cookie 仍会存储在客户端本地（如硬盘），直到过期时间到达才会被删除。
    下次打开浏览器访问网站时，Cookie 中的 Session ID 会被重新发送给服务器，服务器可通过该 ID 找到原有 Session（前提是服务器未重启且 Session 未过期）。*/
}
/*
是的，你的理解基本正确，但可以再补充一些细节，帮助更全面地理解 Session 和 Cookie 的协同工作机制。以下是详细解释：
一、Session 与 Cookie 的核心关系
Session 的本质
服务器创建的 Session 是存储在服务器端的数据对象（如内存、缓存、数据库等），用于记录用户的会话状态（如登录信息、购物车等）。
每个 Session 有一个唯一的 Session ID（通常是随机生成的字符串），用于标识该会话。
Cookie 的作用
服务器不会将整个 Session 数据存入 Cookie，而是将 Session ID 存入 Cookie，发送给客户端（浏览器）。
浏览器收到后，会将这个 Cookie 存储起来。后续每次请求该网站时，浏览器会自动携带这个 Cookie 中的 Session ID 到服务器。
服务器通过 Session ID 查找对应的 Session 数据，从而实现 “识别用户身份，保持会话状态” 的目的。*/
/*
次获取的 Session 对象一致，具体描述如下：
        1. 首次请求（SessionDemo1）
无 Cookie 时创建 Session：
当客户端首次访问 SessionDemo1 时，代码 request.getSession() 检测到请求中 没有 Cookie 携带的 Session ID，服务器会：
在内存中 创建新的 HttpSession 对象（生成唯一 Session ID，如 742938a4289）。
通过响应头 Set-Cookie: JSESSIONID=742938a4289，将 Session ID 存入客户端 Cookie（浏览器保存该 Cookie）。
        2. 后续请求（SessionDemo2）
携带 Cookie 传递 Session ID：
客户端再次访问 SessionDemo2 时，浏览器自动在请求头中携带 Cookie 中的 Session ID（JSESSIONID=742938a4289）。
服务器通过代码 request.getSession() 解析该 ID，在内存中查找对应的 HttpSession 对象（与首次创建的是同一个，因为 Session ID 匹配）*/
