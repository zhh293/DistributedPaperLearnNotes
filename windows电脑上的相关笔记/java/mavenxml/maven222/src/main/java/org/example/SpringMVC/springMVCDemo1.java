package org.example.SpringMVC;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class springMVCDemo1 {
    @RequestMapping("/save")
    @ResponseBody
     public String save(){
        System.out.println("保存成功");
        return "success";
    }
}
/*
@ResponseBody的作用其实是将java对象转为json格式的数据*/
/*消息转换器（HttpMessageConverter）简介
消息转换器是 Spring MVC 中用于处理 HTTP 请求和响应的内容转换的组件，负责在以下场景中进行数据转换：

请求处理：将 HTTP 请求体（如 JSON、XML）转换为 Java 对象（如 Controller 方法的 @RequestBody 参数）。
响应处理：将 Java 对象（如 Controller 返回的 @ResponseBody 对象）转换为 HTTP 响应体。

常见实现：

MappingJackson2HttpMessageConverter：处理 JSON 格式。
Jaxb2RootElementHttpMessageConverter：处理 XML 格式。
FormHttpMessageConverter：处理表单数据（application/x-www-form-urlencoded）。
ByteArrayHttpMessageConverter：处理二进制数据（如文件下载）。*/


/*JacksonObjectMapper 的意义与作用
你提出了一个很好的问题。虽然 Spring MVC 确实提供了 @ResponseBody 和 @RequestBody 等注解来自动处理 JSON 序列化和反序列化，但这个自定义的 JacksonObjectMapper 类仍然有重要价值：
        1. 统一日期格式处理
默认问题：Jackson 默认序列化日期时，可能会输出时间戳（如 1634567890）或不友好的格式。
自定义需求：通过 JacksonObjectMapper，你可以为所有 LocalDateTime、LocalDate 和 LocalTime 类型设置统一的日期格式（如 yyyy-MM-dd HH:mm），避免在每个 Controller 中单独处理。

示例：

java
// 没有自定义时，JSON 可能输出为:
{
    "createTime": 1634567890000,
        "updateTime": "2021-10-18T15:38:10.000+00:00"
}

// 自定义后，统一输出为:
{
        "createTime": "2021-10-18 15:38",
        "updateTime": "2021-10-18 15:38"
        }
        2. 处理未知属性
默认行为：Jackson 默认在遇到 JSON 中存在但 Java 对象中不存在的属性时，会抛出 JsonMappingException。
自定义配置：通过 configure(FAIL_ON_UNKNOWN_PROPERTIES, false)，可以忽略这些未知属性，增强系统的健壮性。

示例：

json
// JSON 请求
{
    "id": 1,
        "name": "John",
        "age": 30,
        "extraField": "ignored" // 未知属性，会被忽略
}
3. 全局配置序列化 / 反序列化策略
单一职责：将所有 JSON 处理的配置集中在一个类中，避免在每个 Controller 或 DTO 中重复配置。
扩展性：可以轻松添加更多自定义序列化器 / 反序列化器（如 BigDecimal、枚举等）。
        4. 与 Spring MVC 集成
这个类通常会注册到 Spring MVC 的消息转换器中：
为什么要注册在这里面不用多说了吧，用屁股想想就知道了



5. 对比直接使用注解的局限性
虽然可以在实体类上使用注解（如 @JsonFormat、@JsonIgnore），但：

不够灵活：注解需要写在每个类的字段上，而 JacksonObjectMapper 可以全局生效。
无法覆盖默认行为：某些默认行为（如处理未知属性）只能通过配置 ObjectMapper 实现。
代码冗余：每个类都需要重复添加注解，违反 DRY 原则。
总结
JacksonObjectMapper 的存在是为了提供 全局、统一、可复用 的 JSON 处理策略，解决以下问题：*/

/*
参数注解与消息转换器的区别
你的问题触及了 Spring MVC 中两个不同的数据处理机制：参数绑定和消息转换。虽然你已经配置了 JacksonObjectMapper，但这两个机制的作用场景不同。
        1. 参数注解（@DateTimeFormat）的作用
@DateTimeFormat 是 Spring MVC 用于处理请求参数绑定的注解，它的作用是：

将 URL 参数（如 ?begin=2023-01-01）或表单参数转换为 Java 对象（如 LocalDateTime）。
只对 @RequestParam、@PathVariable 等参数有效。

为什么需要它？
HTTP 请求中的参数是字符串（如 2023-01-01），而你的方法需要 LocalDateTime 类型，Spring 需要知道如何解析这个字符串。
        2. 消息转换器（JacksonObjectMapper）的作用
JacksonObjectMapper 是用于处理 HTTP 消息体（Request Body/Response Body）的，它的作用是：

将 JSON 请求体（如 {"begin":"2023-01-01"}）转换为 Java 对象（反序列化）。
将 Java 对象转换为 JSON 响应体（序列化）。
只对 @RequestBody、@ResponseBody 有效。*/

/*


* 需要注意的是，如果你使用springboot，那么SpringBoot会自动注册一个JacksonObjectMapper，你可以不用自己注册。
* 但是他的效果比较差，
* 相当于消息转化器那一堆代码其实没必要重写，springboot底层会做好基本的处理
* 但是对于好多定制化需求，比如日期格式化，那么就需要自己注册一个消息转换器
* 然后在实现了WebConfigurationSupport的类中，重写configureMessageConverters方法，将消息转换器注册进去
* 这样的话就可以覆盖掉springboot默认的消息转换器
* 达到更好的效果。。。。。。。
*
*
*
*
* */


























/*如果没有配置消息转换器（如 JacksonObjectMapper），Spring MVC 在处理 HTTP 请求和响应时会受到严重限制，导致以下问题：
        1. 无法处理 JSON 请求体
症状：
当 Controller 方法使用 @RequestBody 接收 JSON 参数时，会抛出 HttpMessageNotReadableException 异常。
原因：
没有消息转换器，Spring 无法将 HTTP 请求体中的 JSON 字符串反序列化为 Java 对象。

示例：

java
@PostMapping("/users")
public Result<?> createUser(@RequestBody UserDTO user) {
    // 没有消息转换器时，user 参数为 null 或抛出异常
    return Result.success();
}
2. 无法返回 JSON 响应体
症状：
当 Controller 方法使用 @ResponseBody 返回对象时，会抛出 HttpMessageNotWritableException 异常。
原因：
没有消息转换器，Spring 无法将 Java 对象序列化为 HTTP 响应体中的 JSON 字符串。

示例：

java
@GetMapping("/users/{id}")
public Result<UserDTO> getUser(@PathVariable Long id) {
    // 没有消息转换器时，客户端会收到 500 错误
    return Result.success(userService.findById(id));
}
3. 日期格式处理混乱
症状：
日期类型（如 LocalDateTime）可能被序列化为时间戳或不符合预期的格式。
原因：
没有自定义的消息转换器，Jackson 会使用默认格式（如 ISO 8601 格式）。

示例：

json
// 没有自定义格式时，日期可能输出为：
{
    "createTime": "2023-10-15T14:30:00.000+00:00"
}

// 而不是预期的：
{
        "createTime": "2023-10-15 14:30"
        }
        4. 无法处理未知属性
症状：
当 JSON 请求中包含 Java 对象没有的字段时，会抛出 JsonMappingException 异常。
原因：
没有配置 FAIL_ON_UNKNOWN_PROPERTIES = false，Jackson 默认会严格匹配所有字段。

示例：

json
// 请求中包含未知字段 "extra"
{
    "id": 1,
        "name": "John",
        "extra": "unknown" // 会导致反序列化失败
}
5. 无法处理复杂对象转换
症状：
自定义对象（如枚举、BigDecimal）可能无法正确序列化或反序列化。
原因：
没有注册自定义的序列化器 / 反序列化器，Jackson 无法处理特殊类型。
        6. 响应类型受限
症状：
只能返回简单类型（如 String、基本数据类型），无法返回复杂对象。
原因：
没有消息转换器，Spring 只能使用默认的字符串转换，无法处理对象到 JSON 的转换。
总结
消息转换器是 Spring MVC 处理 JSON 数据的核心组件，没有它会导致：

请求处理失败：无法解析 JSON 请求体。
响应处理失败：无法生成 JSON 响应体。
数据格式混乱：日期、数字等类型无法按预期格式处理。
异常频发：反序列化时对未知属性敏感，导致频繁报错。*/

