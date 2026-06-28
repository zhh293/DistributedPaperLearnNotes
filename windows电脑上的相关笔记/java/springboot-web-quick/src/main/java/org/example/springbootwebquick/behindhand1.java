package org.example.springbootwebquick;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class behindhand1 {
    @RequestMapping("/hello")
    public result hello() {
        return result.ok("ok","hello world");
    }
    /*@RequestMapping("/address")
    public ADDRESS getadress() {
        ADDRESS adress=new ADDRESS();
        adress.setCity("深圳");
        adress.setProvince("广东");
        return adress;
    }*/
    @RequestMapping("/address")
    public result getadress() {
        ADDRESS adress=new ADDRESS();
        adress.setCity("深圳");
        adress.setProvince("广东");
        return result.ok(adress);
    }
    /*@RequestMapping("/listaddr")
    public List<ADDRESS> getlistaddr() {
        List<ADDRESS> list=new ArrayList<ADDRESS>();
        ADDRESS address=new ADDRESS();
        ADDRESS address1=new ADDRESS();
        list.add(address);
        list.add(address1);
        address.setCity("南京");
        address.setProvince("江苏");
        address1.setProvince("河南");
        address1.setCity("安阳");
        return list;
    }*/
    @RequestMapping("/listaddr")
    public result getlistaddr() {
        List<ADDRESS> list=new ArrayList<ADDRESS>();
        ADDRESS address=new ADDRESS();
        ADDRESS address1=new ADDRESS();
        list.add(address);
        list.add(address1);
        address.setCity("南京");
        address.setProvince("江苏");
        address1.setProvince("河南");
        address1.setCity("安阳");
        return result.ok(list);
    }
    //统一的响应结果
    //在restcontroller和responsebody这类方法下，若返回值类型是实体对象或者集合，传递到客户端的数据会转为json格式

}
/*
@Controller 与 @RestController 的区别
特性	@Controller（传统 MVC）	                        @RestController（RESTful API）
注解组合	仅标记控制器，需配合 @ResponseBody 返回数据   	组合 @Controller + @ResponseBody，默认返回数据（无需额外注解）
返回值处理	1. 默认返回 视图名称（如 JSP 页面）
        2. 需显式加 @ResponseBody 才返回数据（JSON / 字符串等）	所有方法 直接返回数据（自动转换为 JSON/XML 等，无需视图解析）
应用场景	服务端渲染页面（如 Thymeleaf、JSP 模板）	             前后端分离（前端通过 AJAX 获取 JSON 数据，如 Vue/Rea*/
/*
@RequestMapping：
通用请求映射注解，可指定：
value：URL 路径（如 /user）。
method：HTTP 方法（RequestMethod.GET、POST、PUT、DELETE 等）。
示例：@RequestMapping(value = "/user", method = RequestMethod.POST)（处理 POST 请求）。
支持类级别和方法级别：类级别定义基础路径，方法级别定义具体路径（如类上 @RequestMapping("/api")，方法上 @RequestMapping("/user") 对应 URL /api/user）。
快捷注解（如 @PostMapping、@PutMapping、@GetMapping、@DeleteMapping）：
是 @RequestMapping 的 语法糖，固定 HTTP 方法，更简洁。
示例：
@PostMapping("/user") 等价于 @RequestMapping(value = "/user", method = RequestMethod.POST)（处理 POST 请求）。
@PutMapping("/user/{id}") 处理 PUT 请求（更新资源），@DeleteMapping("/user/{id}") 处理 DELETE 请求（删除资源）。
仅用于方法级别，不能在类上使用（类上仍需 @RequestMapping 定义基础路径）。*/
/*
三、@ResponseBody 的作用
核心功能：
将方法的 返回值直接写入 HTTP 响应体（而非解析为视图）。
自动序列化：
返回 Java 对象：Spring 会通过 HttpMessageConverter（如 Jackson 库）转换为 JSON 格式（需引入 jackson-databind 依赖）。
返回 字符串：直接作为响应体（如 return "hello" 会在响应体中显示 hello）。*/
