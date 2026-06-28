package com.zhh.handsome;

public class 消息转换器 {
}



/*在 Spring AMQP 中，消息转换器（MessageConverter） 是连接 Java 对象与 RabbitMQ 消息（字节流）的 “翻译官”，负责完成两个核心操作：

序列化：发送消息时，将 Java 对象转换为 RabbitMQ 能传输的字节数组（byte[]）。
反序列化：接收消息时，将 RabbitMQ 的字节数组转换为 Java 对象。
为什么需要消息转换器？
RabbitMQ 本身只处理字节流（消息体是byte[]类型），但开发者更希望直接发送 / 接收 Java 对象（如User、Order等），而非手动处理字节数组。消息转换器正是为了简化这一过程，避免重复的序列化 / 反序列化代码。
核心接口与默认实现
Spring AMQP 的消息转换功能基于MessageConverter接口，其核心方法如下：

java
        运行
// 序列化：Java对象 → Message（包含字节数组和属性）
Message toMessage(Object object, MessageProperties messageProperties) throws MessageConversionException;

// 反序列化：Message → Java对象
Object fromMessage(Message message) throws MessageConversionException;

Spring AMQP 提供了多种默认实现，最常用的有两种：
        1. SimpleMessageConverter（默认转换器）
这是 Spring AMQP 默认使用的转换器，支持以下类型的转换：

String：直接转为字节数组（默认 UTF-8 编码）。
byte[]：直接作为消息体。
Serializable 对象：通过 Java 原生的序列化机制（ObjectOutputStream）转换为字节数组。

示例：
发送一个User对象（实现了Serializable接口）：

java
        运行
// 生产者发送
User user = new User(1, "张三");
rabbitTemplate.convertAndSend("exchange", "routingKey", user);

// 消费者接收（自动反序列化为User对象）
@RabbitListener(queues = "queue")
public void handleUser(User user) {
    System.out.println("收到用户：" + user.getName());
}

缺点：

依赖 Java 原生序列化，性能较差，且序列化后的字节数组体积大。
兼容性差：修改类结构（如增减字段）可能导致反序列化失败。
跨语言支持差：非 Java 语言（如 Python、Go）难以解析 Java 序列化的字节流。
        2. Jackson2JsonMessageConverter（推荐）
以 JSON 格式进行序列化 / 反序列化，解决了SimpleMessageConverter的痛点，是生产环境的首选。

优势：

序列化后为 JSON 字符串，可读性强（便于调试）。
体积小，性能优于 Java 原生序列化。
跨语言兼容性好（几乎所有语言都支持 JSON）。
对类结构修改的兼容性更强（可忽略未知字段）。
如何使用 Jackson2JsonMessageConverter？
步骤 1：添加依赖
需要引入 Jackson 的依赖（Spring Boot 项目可直接添加）：

xml
        <dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
步骤 2：配置转换器
通过@Bean定义Jackson2JsonMessageConverter，并替换RabbitTemplate的默认转换器：

java
        运行
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    // 配置消息转换器：使用Jackson2JsonMessageConverter
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // 配置RabbitTemplate，指定使用JSON转换器
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter()); // 设置转换器
        return rabbitTemplate;
    }

    // 配置消费者的监听容器工厂（确保消费者也使用相同的转换器）
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter()); // 消费者也需要用JSON转换器
        return factory;
    }
}
步骤 3：使用示例
发送和接收一个Order对象（无需实现Serializable）：

java
        运行
// 定义Order类（普通POJO）
public class Order {
    private Long id;
    private String productName;
    private BigDecimal amount;
    // 省略getter、setter、构造器
}

// 生产者发送Order对象
@Service
public class OrderProducer {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void sendOrder(Order order) {
        // 直接发送Order对象，转换器会自动序列化为JSON
        rabbitTemplate.convertAndSend("order.exchange", "order.create", order);
    }
}

// 消费者接收Order对象（自动反序列化为Order）
@Service
public class OrderConsumer {
    @RabbitListener(queues = "order.queue")
    public void handleOrder(Order order) {
        System.out.println("收到订单：" + order.getId() + "，商品：" + order.getProductName());
    }
}

效果：
发送的消息体是 JSON 字符串（而非二进制），例如：

json
{"id":1,"productName":"手机","amount":3999.00}
高级配置：自定义 Jackson 序列化规则
Jackson2JsonMessageConverter基于 Jackson 的ObjectMapper工作，可通过自定义ObjectMapper调整序列化规则（如日期格式、空值处理等）。

示例：配置日期格式为yyyy-MM-dd HH:mm:ss，忽略空字段：

java
        运行
@Bean
public Jackson2JsonMessageConverter jsonMessageConverter() {
    // 自定义ObjectMapper
    ObjectMapper objectMapper = new ObjectMapper();
    // 日期格式
    objectMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
    // 忽略空字段
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    // 支持Java 8时间类型（LocalDateTime等）
    objectMapper.registerModule(new JavaTimeModule());

    // 使用自定义的ObjectMapper创建转换器
    return new Jackson2JsonMessageConverter(objectMapper);
}*/




















