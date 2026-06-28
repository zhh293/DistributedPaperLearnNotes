package RestTemplate;

public class Demo2 {
/*    处理集合和复杂请求
    对于返回集合的响应，可以使用 ParameterizedTypeReference 来处理泛型类型：

    java
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import java.util.List;

    public class CollectionExample {
        public static void main(String[] args) {
            RestTemplate restTemplate = new RestTemplate();
            String url = "https://api.example.com/users";

            // 使用 ParameterizedTypeReference 处理集合类型
            ResponseEntity<List<User>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<User>>() {}
            );

            List<User> users = response.getBody();
            users.forEach(user -> System.out.println(user.getName()));
        }
    }
    自定义配置
    可以通过 HttpComponentsClientHttpRequestFactory 自定义底层 HTTP 客户端：

    java
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

    public class CustomRestTemplateExample {
        public static void main(String[] args) {
            // 创建自定义 HTTP 客户端
            CloseableHttpClient httpClient = HttpClients.custom()
                    .setMaxConnTotal(100)
                    .setMaxConnPerRoute(20)
                    .build();

            // 创建自定义请求工厂
            HttpComponentsClientHttpRequestFactory requestFactory =
                    new HttpComponentsClientHttpRequestFactory();
            requestFactory.setHttpClient(httpClient);
            requestFactory.setConnectTimeout(3000); // 连接超时时间
            requestFactory.setReadTimeout(5000);    // 读取超时时间

            // 使用自定义请求工厂创建 RestTemplate
            RestTemplate restTemplate = new RestTemplate(requestFactory);

            // 使用配置好的 RestTemplate 发送请求
            String url = "https://api.example.com/users/1";
            User user = restTemplate.getForObject(url, User.class);
            System.out.println(user.getName());
        }
    }
    异常处理
    RestTemplate 将 HTTP 错误状态码映射为特定的异常：

    HttpClientErrorException (4xx 状态码)
    HttpServerErrorException (5xx 状态码)
    UnknownHttpStatusCodeException (未知状态码)
    ResourceAccessException (网络连接异常)

    java
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

    public class ExceptionHandlingExample {
        public static void main(String[] args) {
            RestTemplate restTemplate = new RestTemplate();
            String url = "https://api.example.com/users/999"; // 不存在的用户

            try {
                User user = restTemplate.getForObject(url, User.class);
            } catch (HttpClientErrorException.NotFound e) {
                System.out.println("用户不存在，错误信息: " + e.getStatusCode());
                System.out.println("响应体: " + e.getResponseBodyAsString());
            } catch (Exception e) {
                System.out.println("发生其他异常: " + e.getMessage());
            }
        }
    }
    添加拦截器
    可以添加拦截器来处理请求和响应，例如添加请求头或日志记录：

    java
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

    public class InterceptorExample {
        public static void main(String[] args) {
            RestTemplate restTemplate = new RestTemplate();

            // 添加拦截器
            restTemplate.getInterceptors().add(new ClientHttpRequestInterceptor() {
                @Override
                public ClientHttpResponse intercept(
                        HttpRequest request,
                        byte[] body,
                        ClientHttpRequestExecution execution) throws IOException {

                    // 请求前处理
                    System.out.println("Request URI: " + request.getURI());
                    System.out.println("Request Method: " + request.getMethod());

                    // 添加请求头
                    request.getHeaders().add("Authorization", "Bearer your_token_here");

                    // 执行请求
                    ClientHttpResponse response = execution.execute(request, body);

                    // 响应后处理
                    System.out.println("Response Status Code: " + response.getStatusCode());

                    return response;
                }
            });

            // 使用带拦截器的 RestTemplate
            String url = "https://api.example.com/users";
            User[] users = restTemplate.getForObject(url, User[].class);
        }
    }

    与*/
}
