package org.example.springbootwebquick;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Array;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*@RestController
public class behindhand {
   /* @RequestMapping("/hello")
    public String hello() {
        return "hello controller";
    }
    @RequestMapping("/compare")
    public String hello2(@RequestParam int a, @RequestParam int b) {
        if(a > b) return "a";
        return "ok";
    }
    @RequestMapping("/giggly")
    public String hello3(@RequestParam( name="name",required = true) String username,int age) {
        System.out.println("hello3 " + username + " " + age);
        return "ok";
    }
    @RequestMapping("/ooop")
    public String hello4(user ooop){
        ADDRESS address =new ADDRESS();
        address.setCity("南京");
        address.setProvince("江苏");
        ooop.setName("zhanghonghao");
        ooop.setAge(18);
        ooop.setAddress( address );
        System.out.println("hello4 " + ooop.getName()+" "+ooop.getAge()+"家居住在"+ooop.getAddress().getCity()+" "+ooop.getAddress().getProvince());
        return "ok";
    }
    //数组参数，请求参数名与形参数组名称相同且请求参数为多个（输入的时候可按这个形式?hobby=....&hobby=....)，定义数组类型形参即可接收参数
    @RequestMapping("/iop")
    public String hello5(String[]hobby) {
        for (int i = 0; i < hobby.length; i++) {
            System.out.println(hobby[i]);
        }
        //System.out.println(hobby);
        return "ok";
    }
    @RequestMapping("/listparam")
    public String listparam(@RequestParam List<String> hobby){
         System.out.println(hobby);
         return "ok";
    }
    //日期参数
    @RequestMapping("/kop")
    public String kop(@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")LocalDateTime updateTime){
        System.out.println(updateTime);
        return "ok";
    }
    //jason参数,键名与形参对象属性名相同
    @RequestMapping("/jsonparam")
    public String jsonparam(@RequestBody user u){
        System.out.println(u);
        System.out.println("jsonparam " + u.getName()+" "+u.getAge()+" "+u.getAddress().getCity()+" "+u.getAddress().getProvince());
        return "ok";
    }*/
    //路径参数，请求参数的一部分，也是路径
   /* @RequestMapping("/path/{id}/{name}")
    public String path(@PathVariable int id,@PathVariable String name){
        System.out.println(id+"  \n"+name);
        return "ok";
    }*/
    //@Restcontroller=@responsebody+@controller

//}
//http协议，规定了浏览器与服务器之间数据运输的规则，浏览器给服务器发送请求，服务器响应浏览器
//http基于TCP协议，面向连接，安全，一次请求对应一次响应
//缺点  多次请求无法共享数据，但是速度快
//请求格式
//请求行    GET  /brand/findAll?name=OPPO&status=1 HTTP/1.1      POST  /brand HTTP/1.1，GET请求参数在请求行中，没有请求体，GET请求大小是有限制的。POST请求参数在请求体中，请求大小是没有限制的
//user-agent   浏览器的版本，例如chrome浏览器标识类似Mozilla/5.0...Chrome/79
//accept  表示浏览器能接受的资源类型，如text/*,image/*或者*/*
//accept-language  表示浏览器偏好的语言
//accept-encoding  表示浏览器可以支持的压缩类型，如gzip
//content-type  请求主体的数据类型
//content-length  请求主体的大小(字节)
//请求体   与请求头之间有一行间距，POST请求，存放请求参数
//请求响应
//响应行(协议，状态码，描述)1. 1××表示请求已经被接收告诉客户端应该继续请求2. 2××表示成功处理了3. 3××重定向，让客户端再发起一次请求以完成整个处理
//4. 4×× 处理发生错误，责任在客户端5. 5×× 责任在服务器，服务器错误
//响应头，键值对形式
//响应体，最后一部分，存放响应数据
//dispatcherservlet前端控制器，有httpserveletrequest  httpserveletresponse
