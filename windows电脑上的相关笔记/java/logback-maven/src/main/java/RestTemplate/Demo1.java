package RestTemplate;

public class Demo1 {
/*    RestTemplate 详解
    RestTemplate 是 Spring Framework 提供的一个同步 HTTP 客户端，用于简化与 RESTful 服务的通信。它提供了高度封装的 API，使得发送 HTTP 请求和处理响应变得非常简单。在 Spring Boot 应用中，RestTemplate 是进行服务间通信的首选工具之一。
    主要特点
    简化 HTTP 通信：提供了 getForObject、postForObject 等便捷方法，无需手动处理 HTTP 连接和响应解析。
    强类型转换：可以直接将响应转换为 Java 对象，无需手动解析 JSON 或 XML。
    支持多种 HTTP 方法：GET、POST、PUT、DELETE、HEAD、OPTIONS 等。
    自定义请求处理：可以通过 Interceptor 自定义请求头、日志记录等。
    异常处理：提供了统一的异常处理机制，将 HTTP 状态码映射为特定的异常。
    基本用法
    以下是 RestTemplate 的基本使用流程：

    java
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

    public class RestTemplateExample {
        public static void main(String[] args) {
            // 创建 RestTemplate 实例
            RestTemplate restTemplate = new RestTemplate();

            // 1. GET 请求示例 - 获取单个资源
            String url = "https://api.example.com/users/1";
            // 直接返回响应体并转换为 User 类
            User user = restTemplate.getForObject(url, User.class);
            System.out.println("User: " + user.getName());

            // 或者获取完整响应，包含状态码和头信息
            ResponseEntity<User> response = restTemplate.getForEntity(url, User.class);
            System.out.println("Status code: " + response.getStatusCode());
            System.out.println("User: " + response.getBody().getName());

            // 2. POST 请求示例 - 创建资源
            String createUrl = "https://api.example.com/users";
            User newUser = new User("John Doe", "john@example.com");
            // 发送 POST 请求并获取响应
            User createdUser = restTemplate.postForObject(createUrl, newUser, User.class);
            System.out.println("Created User ID: " + createdUser.getId());

            // 3. PUT 请求示例 - 更新资源
            String updateUrl = "https://api.example.com/users/1";
            User updatedUser = new User("Updated Name", "updated@example.com");
            updatedUser.setId(1L);
            restTemplate.put(updateUrl, updatedUser);

            // 4. DELETE 请求示例 - 删除资源
            String deleteUrl = "https://api.example.com/users/1";
            restTemplate.delete(deleteUrl);
        }
    }

    // 示例实体类
    class User {
        private Long id;
        private String name;
        private String email;

        // 构造方法、Getter 和 Setter 省略
    }*/
}
