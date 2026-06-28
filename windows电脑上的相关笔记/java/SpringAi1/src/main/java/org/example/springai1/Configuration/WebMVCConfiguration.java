package org.example.springai1.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.springai1.Json.JacksonObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMVCConfiguration implements WebMvcConfigurer {

   @Override
     public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    //消息转化器
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
       MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
       converter.setObjectMapper(new JacksonObjectMapper());
       converters.add(converter);
    }

}
/*
. addCorsMappings(CorsRegistry registry)
作用： 这是 Spring 提供的接口方法，用于注册和配置跨域资源共享（CORS）规则。
参数说明：
registry: 是一个 CorsRegistry 对象，用于注册 CORS 映射规则。
调用时机： 当你的类实现 WebMvcConfigurer 接口并重写该方法时，Spring Boot 在启动时会自动加载这些跨域配置。

        2. registry.addMapping("/**")
作用： 定义需要启用跨域支持的 URL 路径模式。
参数说明：
        "/**" 表示匹配所有请求路径，包括子路径。

        可选扩展：
如果只想对特定 API 启用跨域，例如 /api/**，可以改为：
    registry.addMapping("/api/**")
     .allowedOrigins("*")
作用： 指定允许访问的请求来源（Origin），即哪些域名、协议或端口可以发起跨域请求。
参数说明：
"*" 表示允许所有来源。
注意：
使用通配符 "*" 可能存在安全风险，建议在生产环境中明确指定允许的来源，例如：
    .allowedOrigins("http://example.com", "http://anotherdomain.com")
     .allowedMethods("*")
作用： 指定允许使用的 HTTP 方法（如 GET、POST、PUT 等）。
参数说明：
"*" 表示允许所有 HTTP 方法。
安全性建议：
根据实际需求限制为仅需的方法，例如：
    .allowedMethods("GET", "POST")
    .allowedHeaders("*")
作用： 指定允许的请求头（HTTP Headers）。
参数说明：
"*" 表示允许所有请求头。
常见请求头：
Content-Type, Authorization, Accept 等。
建议：
与 allowedMethods 类似，在生产环境中应尽量限制为具体需要的请求头，以增强安全性。
 */
