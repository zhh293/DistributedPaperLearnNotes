package com.zhh.handsome;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class AMQP {

    /*要理解 AMQP，不用记复杂术语，我们先从一个生活类比入手，再拆它的核心作用 ——
    先想个问题：你怎么给朋友寄快递？
    你要寄一箱水果给外地朋友，得经过这几步：

    你（寄件人）把水果打包，写好朋友的地址（消息内容 + 收件信息）；
    你把包裹交给快递公司（比如顺丰），快递公司先把包裹放到本地仓库（暂存）；
    仓库分拣员（按地址分类）把包裹分到 “去朋友城市” 的运输车上；
    包裹到朋友城市的仓库后，快递员（派件人）把包裹送到朋友手里（收件人）。

    这里的 “快递公司 + 仓库 + 分拣员 + 运输规则”，其实就对应了 AMQP 要解决的问题 —— 只不过不是 “寄水果”，而是 “软件之间传消息”。
    那 AMQP 到底是什么？
    AMQP 的全称是 “高级消息队列协议”，但你可以简单理解成：
    它是一套 “软件之间传消息的通用规矩” —— 就像快递行业的 “全国统一操作标准”（比如面单格式、分拣流程、丢件赔偿规则）。

    没有这套规矩会怎么样？
    比如 A 软件（比如电商的 “下单系统”）想给 B 软件（比如 “库存系统”）发消息 “用户买了 1 件衣服，要减库存”，但 A 用的是 “自家格式”，B 用的是 “别家格式”，两者根本看不懂对方的消息；就算能看懂，万一 B 软件临时故障，消息就丢了（比如用户下单了，但库存没减，导致超卖）。

    而 AMQP 就是帮大家定好 “通用规矩”，让不同软件（哪怕用不同语言写的，比如 A 是 Java、B 是 Python）能安全、不丢消息地传数据。
    AMQP 里的 “关键角色”，对应快递场景一看就懂
    AMQP 的核心是 “4 个角色 + 1 个暂存区”，和快递流程完全能对应上，记起来很简单：

    AMQP 里的角色	对应快递场景	作用说明
    生产者（Producer）	寄件人（你）	主动发消息的软件（比如 “下单系统”，下单后主动发 “减库存” 的消息）。
    消费者（Consumer）	收件人（你朋友）	接收消息的软件（比如 “库存系统”，收到消息后执行 “减库存” 操作）。
    交换机（Exchange）	仓库分拣员	收到生产者的消息后，按规则 “分类”，把消息分到正确的 “队列” 里（比如 “减库存的消息都去库存队列”）。
    队列（Queue）	快递公司仓库货架	暂存消息的 “小仓库”—— 如果消费者暂时故障（比如库存系统在维护），消息就存在队列里，等消费者恢复了再取，不会丢。
    消息代理（Broker）	整个快递公司	就是 “交换机 + 队列” 的集合（比如 RabbitMQ、ActiveMQ 这些软件，就是实现了 AMQP 协议的 “消息代理”，相当于 “顺丰公司”）。
    AMQP 最核心的 3 个好处：解决软件传消息的 “痛点”
    还是用 “电商下单” 的实际场景，看 AMQP 怎么起作用：
            1. 保证消息 “不丢”（可靠传递）
    比如用户下单后，“下单系统” 发消息给 “库存系统”，但此时 “库存系统” 刚好宕机了 ——
    没有 AMQP：消息直接丢了，用户下单成功但库存没减，后续可能超卖；
    有 AMQP：消息会先存在 “库存队列” 里，等 “库存系统” 修好重启后，再从队列里把消息取出来执行减库存，绝对不丢。
            2. 软件之间 “互不依赖”（解耦）
    以前 “下单系统” 要直接调用 “库存系统” 的接口：如果 “库存系统” 改了接口地址，“下单系统” 就得跟着改；如果 “库存系统” 卡了，“下单系统” 也会跟着卡（用户下单页面加载半天）。

    有了 AMQP 后：“下单系统” 只需要把消息发给 “消息代理”（比如 RabbitMQ），就完事了，根本不用管 “库存系统” 在哪、好不好用；“库存系统” 自己从队列里取消息处理 —— 两者互不干扰，就算一方改了，另一方也不用动。
            3. 消息能 “精准分类”（灵活路由）
    比如 “下单系统” 发的消息不止 “减库存”，还有 “生成物流单”“给用户发短信通知”——
    AMQP 的 “交换机” 就像分拣员：把 “减库存” 的消息分到 “库存队列”，“生成物流单” 的消息分到 “物流队列”，“发短信” 的消息分到 “短信队列”，最后让 “库存系统”“物流系统”“短信系统” 各自取自己队列的消息，不会乱。
    总结：AMQP 到底是个啥？
    一句话说清：
    AMQP 是给 “软件传消息” 定的 “通用规矩”，它通过 “交换机 + 队列” 的机制，保证消息能安全、不丢、精准地从一个软件传到另一个软件，就像快递行业的统一标准，让不同快递公司能配合，把包裹稳稳送到家。

    日常我们听到的 RabbitMQ、Apache Qpid 这些 “消息中间件”，其实就是 “实现了 AMQP 协议的软件”—— 相当于 “按 AMQP 规矩运营的快递公司”。*/




    //原始初始化

    /*// 1.建立连接

    //        InputStreamReader streamReader=new InputStreamReader(new FileInputStream(""), Charset.forName("GBK"));
    ConnectionFactory factory = new ConnectionFactory();
    // 1.1.设置连接参数，分别是：主机名、端口号、vhost、用户名、密码
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setVirtualHost("/");
        factory.setUsername("itcast");
        factory.setPassword("123321");
    // 1.2.建立连接
    Connection connection = factory.newConnection();

    // 2.创建通道Channel
    Channel channel = connection.createChannel();

    // 3.创建队列
    String queueName = "hello";
        channel.queueDeclare(queueName, false, false, false, null);

    // 4.发送消息
    String message = "hello, rabbitmq!";
        channel.basicPublish("", queueName, null, message.getBytes());
        System.out.println("发送消息成功：【" + message + "】");
        *//*List<Integer> collect = Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 1, 1)
                .distinct()
                .collect(Collectors.toList());
        System.out.println(collect);*//*
    // 5.关闭通道和连接
        channel.close();
        connection.close();

}*/


   /* public static void main(String[] args) throws IOException, TimeoutException {
        // 1.建立连接
        ConnectionFactory factory = new ConnectionFactory();
        // 1.1.设置连接参数，分别是：主机名、端口号、vhost、用户名、密码
        factory.setHost("192.168.150.101");
        factory.setPort(5672);
        factory.setVirtualHost("/");
        factory.setUsername("itcast");
        factory.setPassword("123321");
        // 1.2.建立连接
        Connection connection = factory.newConnection();

        // 2.创建通道Channel
        Channel channel = connection.createChannel();

        // 3.创建队列
        String queueName = "simple.queue";
        channel.queueDeclare(queueName, false, false, false, null);

        // 4.订阅消息
        channel.basicConsume(queueName, true, new DefaultConsumer(channel){
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope,
                                       AMQP.BasicProperties properties, byte[] body) throws IOException {
                // 5.处理消息
                String message = new String(body);
                System.out.println("接收到消息：【" + message + "】");
            }
        });
        System.out.println("等待接收消息。。。。");
    }*/














    /*
    spring:
    rabbitmq:
    host: localhost
    port: 5672
    username: itcast
    password: 123321
    virtual-host: /
            */


/*
    在 Spring AMQP 中，创建队列（Queue）、交换机（Exchange）、绑定关系（Binding）有两种常用方式：声明式 Bean 定义和注解式声明，两种方式都能自动将组件创建到 RabbitMQ 服务器上，无需手动在 RabbitMQ 控制台操作。
    方式一：声明式 Bean 定义（推荐用于复杂配置）
    通过在配置类中定义Queue、Exchange、Binding类型的 Bean，Spring AMQP 会通过RabbitAdmin自动将这些组件注册到 RabbitMQ。

    适合场景：需要详细配置队列 / 交换机属性（如持久化、过期时间、死信队列等）、或配置关系复杂的场景。
    示例代码：
    java
            运行
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

    @Configuration
    public class RabbitMqConfig {

        // 1. 定义队列
        @Bean
        public Queue myQueue() {
            */
/*
             * 参数说明：
             * name: 队列名称
             * durable: 是否持久化（true=RabbitMQ重启后队列仍存在）
             * exclusive: 是否排他（true=仅当前连接可见，连接关闭后删除）
             * autoDelete: 是否自动删除（当最后一个消费者断开后自动删除）
             *//*

            return QueueBuilder.durable("my.queue") // 持久化队列
                    .withArgument("x-message-ttl", 60000) // 消息默认过期时间60秒
                    .withArgument("x-dead-letter-exchange", "dead.letter.exchange") // 死信交换机
                    .build();
        }

        // 2. 定义交换机（以Direct交换机为例，还有Topic、Fanout等类型）
        @Bean
        public DirectExchange myExchange() {
            */
/*
             * 参数说明：
             * name: 交换机名称
             * durable: 是否持久化
             * autoDelete: 是否自动删除
             *//*

            return ExchangeBuilder.directExchange("my.exchange")
                    .durable(true)
                    .build();
        }

        // 3. 定义绑定关系（将队列和交换机通过路由键绑定）
        @Bean
        public Binding myBinding(Queue myQueue, DirectExchange myExchange) {
            // 将队列myQueue与交换机myExchange通过路由键"my.routing.key"绑定
            return BindingBuilder.bind(myQueue).to(myExchange).with("my.routing.key");
        }
    }

    原理：Spring 容器启动时，RabbitAdmin会扫描所有Queue、Exchange、Binding类型的 Bean，自动调用 RabbitMQ 的 API 创建这些组件（如果不存在）。
    方式二：注解式声明（推荐用于简单场景）
    在使用@RabbitListener注解监听消息时，可以直接通过@Queue、@Exchange、@Binding注解声明队列、交换机和绑定关系，更简洁。

    适合场景：简单的队列配置，无需复杂属性（如临时队列、简单绑定）。
    示例代码：
    java
            运行
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

    @Component
    public class MyMessageListener {

        // 监听消息时，同时声明队列、交换机和绑定关系
        @RabbitListener(bindings = @QueueBinding(
                value = @Queue(
                        name = "my.queue", // 队列名称
                        durable = "true"  // 持久化（默认false）
                ),
                exchange = @Exchange(
                        name = "my.exchange", // 交换机名称
                        type = "direct",      // 交换机类型（direct/topic/fanout）
                        durable = "true"      // 持久化
                ),
                key = "my.routing.key"  // 路由键
        ))
        public void handleMessage(String message) {
            System.out.println("收到消息：" + message);
        }
    }

    特点：

    当首次启动监听时，Spring 会自动创建注解中声明的队列、交换机和绑定关系。
    若需配置复杂属性（如死信队列、过期时间），仍需通过@Queue的arguments参数指定（格式：arguments = {"x-message-ttl=60000"}）。
    注意事项
    组件重复创建问题：
    若 RabbitMQ 中已存在同名队列 / 交换机，Spring AMQP 会检查本地配置与服务器上的属性是否一致。如果不一致（如本地配置durable=true，但服务器上是durable=false），会抛出异常，需手动删除服务器上的旧组件后重新创建。
    RabbitAdmin 的自动配置：
    在 Spring Boot 中，引入spring-boot-starter-amqp后，会自动配置RabbitAdmin，无需额外代码。非 Spring Boot 项目需手动注册RabbitAdmin的 Bean。
    临时队列：
    若不需要持久化队列（如临时任务），可省略name和durable，Spring 会自动生成一个随机名称的临时队列（连接关闭后自动删除）：
    java
            运行
    @Queue() // 等价于 name="" + durable=false + autoDelete=true

*/







}
