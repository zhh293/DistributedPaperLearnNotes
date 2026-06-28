package org.example.SpringMVC;

public class 消息转化器 {
/*    1. RequestEntity 的作用
1.1 定义
    RequestEntity<T> 是一个泛型类，用于封装 HTTP 请求的所有信息，包括：

    请求方法（GET、POST、PUT 等）
    请求 URI
    请求头（Headers）
    请求体（Body）
    请求参数（Params）
            1.2 主要作用
    在服务端接收请求：作为控制器方法的参数，用于接收完整的请求信息。
    在客户端发送请求：在 RestTemplate 或 WebClient 中构建请求。
            1.3 使用示例
            java
    // 服务端：作为控制器方法参数
    @PostMapping("/api/echo")
    public ResponseEntity<String> echo(@RequestBody RequestEntity<String> request) {
        HttpMethod method = request.getMethod();
        URI url = request.getUrl();
        HttpHeaders headers = request.getHeaders();
        String body = request.getBody();

        // 处理请求...
        return ResponseEntity.ok("Echo: " + body);
        2. ResponseEntity 的作用
2.1 定义
ResponseEntity<T> 是一个泛型类，用于封装 HTTP 响应的所有信息，包括：

响应状态码（如 200、404、500）
响应头（Headers）
响应体（Body）
2.2 主要作用
在服务端返回响应：作为控制器方法的返回值，自定义完整的响应信息。
在客户端接收响应：作为 RestTemplate 或 WebClient 调用的返回结果。
2.3 使用示例
java
// 服务端：自定义响应状态码和头信息
@GetMapping("/api/users/{id}")
public ResponseEntity<User> getUser(@PathVariable Long id) {
    User user = userService.findById(id);

    if (user == null) {
        return ResponseEntity.notFound().build(); // 404 Not Found
    }

    HttpHeaders headers = new HttpHeaders();
    headers.add("Custom-Header", "Value");

    return ResponseEntity
        .ok()
        .headers(headers)
        .body(user);
}*/
   /* 1. 消息转换器的位置
    消息转换器主要在以下两个地方被使用：
            1.1 请求处理阶段
    将 HTTP 请求体（如 JSON、XML）转换为 Java 对象（使用 @RequestBody 注解）。
    由 HandlerAdapter（通常是 RequestMappingHandlerAdapter）在调用控制器方法前处理。
            1.2 响应处理阶段
    将 Java 对象转换为 HTTP 响应体（如 JSON、XML）。
    由 HandlerAdapter 在控制器方法返回后处理。*/
    /*3. 默认实现
    Spring MVC 默认提供了多种消息转换器，它们在 WebMvcConfigurationSupport 类中被注册：

    java
    @Bean
    public RequestMappingHandlerAdapter requestMappingHandlerAdapter() {
        RequestMappingHandlerAdapter adapter = createRequestMappingHandlerAdapter();
        adapter.setContentNegotiationManager(mvcContentNegotiationManager());
        adapter.setMessageConverters(getMessageConverters());
        // 其他配置...
        return adapter;
    }

    protected List<HttpMessageConverter<?>> getMessageConverters() {
        if (this.messageConverters == null) {
            this.messageConverters = new ArrayList<>();
            // 注册默认的消息转换器
            addDefaultHttpMessageConverters(this.messageConverters);
        }
        return this.messageConverters;
    }

    protected void addDefaultHttpMessageConverters(List<HttpMessageConverter<?>> messageConverters) {
        // 1. ByteArrayHttpMessageConverter
        messageConverters.add(new ByteArrayHttpMessageConverter());

        // 2. StringHttpMessageConverter
        messageConverters.add(new StringHttpMessageConverter());

        // 3. ResourceHttpMessageConverter
        messageConverters.add(new ResourceHttpMessageConverter());

        // 4. ResourceRegionHttpMessageConverter
        messageConverters.add(new ResourceRegionHttpMessageConverter());

        // 5. SourceHttpMessageConverter (JAXB2)
        if (jaxb2Present) {
            messageConverters.add(new SourceHttpMessageConverter<>());
        }

        // 6. AllEncompassingFormHttpMessageConverter
        messageConverters.add(new AllEncompassingFormHttpMessageConverter());

        // 7. Jaxb2RootElementHttpMessageConverter (JAXB2)
        if (jaxb2Present) {
            messageConverters.add(new Jaxb2RootElementHttpMessageConverter());
        }

        // 8. Jackson 消息转换器 (JSON)
        if (jackson2Present) {
            messageConverters.add(new MappingJackson2HttpMessageConverter());
        }

        // 9. Jackson XML 消息转换器 (XML)
        if (jackson2XmlPresent) {
            messageConverters.add(new MappingJackson2XmlHttpMessageConverter());
        }

        // 10. Gson 消息转换器 (JSON)
        if (gsonPresent) {
            messageConverters.add(new GsonHttpMessageConverter());
        }

        // 其他转换器...
    }*/


    /*注册自定义消息转换器：

    java
    @Configuration
    public class WebConfig implements WebMvcConfigurer {

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            // 添加自定义消息转换器
            converters.add(new MyCustomMessageConverter());

            // 注意：如果需要替换默认转换器，需要先清除 converters 列表
            // converters.clear();
            // converters.add(new MyCustomMessageConverter());
        }*/

/*
    Spring MVC 消息转换器配置的误解澄清
    你提出的观点是 错误的。在 Spring Boot 开发中，不需要必须实现 WebMvcConfigurationSupport 才能让消息转换器生效。相反，Spring Boot 提供了更简单的配置方式，并且过度使用 WebMvcConfigurationSupport 可能会导致一些自动配置失效。
            1. Spring Boot 中消息转换器的默认配置
    Spring Boot 会自动配置消息转换器，默认情况下已经包含了：

    JSON 转换器（Jackson 或 Gson）
    XML 转换器
    字符串转换器
            表单数据转换器
    字节数组转换器

    这些转换器在 WebMvcAutoConfiguration 类中被自动注册，无需手动配置。
            2. 正确的自定义消息转换器方式
    在 Spring Boot 中，有两种推荐的方式添加自定义消息转换器：
    方式一：实现 WebMvcConfigurer 接口
            java
    @Configuration
    public class WebConfig implements WebMvcConfigurer {

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            // 添加自定义消息转换器（保留默认转换器）
            converters.add(new MyCustomMessageConverter());
}*/
}