package org.example.ClassDemo1.会话技术;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class cookie {
    //会话技术：会话技术就是让服务器记住客户端，让客户端记住服务器。
    //一次会话包含多次请求和响应：浏览器第一次给服务器资源请求，服务器响应浏览器，浏览器第二次给服务器资源请求，服务器响应浏览器。
    //再一次会话的范围内获取数据
    //cookie快速入门，客户端会话技术；session：服务器会话技术
    //cookie：服务器给客户端返回一个cookie，客户端下次再访问服务器时，带上这个cookie，服务器根据这个cookie来判断客户端。
    //session：服务器给客户端返回一个session，客户端下次再访问服务器时，带上这个session，服务器根据这个session来判断客户端。
    //使用步骤
    //1.创建Cookie对象
    //Cookie cookie=new Cookie("username","zhanghonghao");
    //2.设置Cookie的属性
    //cookie.setMaxAge(60*60*24*7);
    //cookie.setPath("/");
    //3.发送Cookie
    //response.addCookie(cookie);
    //4.获取Cookie,拿到数据
      //Cookie[] cookies=request.getCookies();
    /*public static void main(String[] args) {
        String word="a";
        StringBuilder sb=new StringBuilder();
      long sb1=  (long)(sb.capacity());
    }
    public int maxDepth(TreeNode root) {
        if(root==null){
            return 0;
        }
        List<Integer>list=new ArrayList<>();
        return MaxLength(root,list);

    }
    public int MaxLength(TreeNode root, List<Integer> list){
        int count=0;
        if(root!=null){
            count++;
            MaxLength(root.left,list);
            MaxLength(root.right,list);
            list.add(count);
        }
        new Comparator<Integer>() {
            @Override
            public int compare(Integer o1, Integer o2) {
                //从大到小排
                return o2 - o1;
            }
        }.sort(list);
        return list.getFirst();
    }*/
}
