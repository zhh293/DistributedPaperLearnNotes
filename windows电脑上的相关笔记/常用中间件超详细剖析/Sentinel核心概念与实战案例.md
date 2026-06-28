# Sentinel核心概念与实战案例

本文包含 Sentinel 的核心概念讲解 + 可直接运行的代码案例 + 控制台配置步骤 + 实际效果验证，基于 Spring Boot + Spring Cloud Alibaba Sentinel 实现，适合新手快速上手理解并落地。

# 前置准备（基础项目搭建）

所有案例均基于此基础项目，先完成环境搭建，再进行后续案例实践。

## 1. pom.xml 依赖配置

```xml

<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.7.12</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>sentinel-demo</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>sentinel-demo</name>
    <description>Sentinel案例演示</description>
    
    <properties>
        <java.version>1.8</java.version>
        <spring-cloud-alibaba.version>2021.0.5.0</spring-cloud-alibaba.version>
    </properties>
    
    <dependencies>
        <!-- Spring Web（提供接口能力） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <!-- Sentinel核心依赖 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
        </dependency>
        
        <!-- Sentinel控制台通信依赖 -->
        <dependency>
            <groupId>com.alibaba.csp</groupId>
            <artifactId>sentinel-transport-simple-http</artifactId>
            <version>1.8.6</version>
        </dependency>
    </dependencies>
    
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring-cloud-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

## 2. application.yml 配置（连接Sentinel控制台）

```yaml

server:
  port: 8080 # 项目端口

spring:
  application:
    name: sentinel-demo # 服务名（控制台显示名称）
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080 # Sentinel控制台地址
        port: 8719 # 客户端与控制台通信端口（默认8719）
```

## 3. 启动类

```java

package com.example.sentineldemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SentinelDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(SentinelDemoApplication.class, args);
    }
}
```

## 4. 启动 Sentinel 控制台

1. **下载控制台jar包**：从 Sentinel官网 下载 `sentinel-dashboard-1.8.6.jar`

2. **启动控制台**：执行以下命令
        `java -jar sentinel-dashboard-1.8.6.jar --server.port=8080`

3. **登录控制台**：访问 `http://localhost:8080`，默认账号/密码：`sentinel/sentinel`

# 案例1：核心概念——「资源」（Sentinel 保护的对象）

## 场景定义

将 `/user/getById` 接口标记为 Sentinel 保护的**资源**，使控制台能够识别该保护对象。

## 代码实现（UserController）

```java

package com.example.sentineldemo.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    /**
     * 核心注解：@SentinelResource(value = "userGetById") 标记接口为资源
     * 资源名：userGetById（控制台识别的唯一标识）
     */
    @GetMapping("/user/getById/{id}")
    @SentinelResource(value = "userGetById")
    public String getUserById(@PathVariable Long id) {
        // 模拟业务逻辑：查询用户
        return "成功查询用户，ID：" + id;
    }
}
```

## 操作步骤 & 效果验证

1. 启动项目，访问 `http://localhost:8080/user/getById/1`（**必须访问一次**，Sentinel 懒加载识别资源）

2. 打开 Sentinel 控制台 → 左侧菜单「簇点链路」→ 可看到 `userGetById` 资源

核心结论：资源是 Sentinel 保护的接口/方法，通过 `@SentinelResource` 标记，是所有规则的基础。

# 案例2：「流控规则」—— 限制接口QPS（最常用场景）

## 场景定义

给 `userGetById` 资源配置 **QPS=2** 规则：每秒最多处理2个请求，超出请求直接返回降级提示。

## 代码实现（新增降级处理方法）

修改 `UserController`，添加流控降级方法：

```java

package com.example.sentineldemo.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {
    @GetMapping("/user/getById/{id}")
    @SentinelResource(
            value = "userGetById",
            blockHandler = "userGetByIdBlockHandler" // 流控触发时的降级方法
    )
    public String getUserById(@PathVariable Long id) {
        return "成功查询用户，ID：" + id;
    }

    /**
     * 流控降级方法规范：
     * 1. 参数与原方法一致，末尾添加 BlockException 异常参数
     * 2. 返回值与原方法一致
     */
    public String userGetByIdBlockHandler(Long id, BlockException e) {
        return "请求太频繁啦！每秒最多2个请求，请稍后再试";
    }
}
```

## 控制台配置流控规则

1. 控制台 → 左侧「流控规则」→ 「新增流控规则」

2. 配置项填写：

|配置项|填写值|配置说明|
|---|---|---|
|资源名|userGetById|要保护的资源（与代码一致）|
|限流类型|QPS|按每秒请求数限流|
|阈值类型|单机阈值|单实例的限流阈值|
|阈值|2|每秒最多处理2个请求|
|流控模式|直接|针对当前资源直接限流|
|流控效果|快速失败|超出请求直接返回降级提示|
1. 点击「新增」，规则立即生效

## 效果验证

快速连续访问 `http://localhost:8080/user/getById/1`：

- 每秒≤2次请求：返回 `成功查询用户，ID：1`

- 每秒>2次请求：返回 `请求太频繁啦！每秒最多2个请求，请稍后再试`

核心结论：流控规则通过限制QPS/并发数，防止接口被高流量冲垮。

# 案例3：「熔断降级规则」—— 下游服务异常时切断调用

## 场景定义

模拟订单服务调用支付服务，当支付服务异常率>50%时，触发熔断（10秒内不再调用下游，直接返回降级值）。

## 代码实现（OrderController）

```java

package com.example.sentineldemo.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    /**
     * 模拟订单调用支付服务
     * fallback：异常/熔断时的降级方法
     */
    @GetMapping("/order/create/{orderId}")
    @SentinelResource(
            value = "payCreate",
            fallback = "payCreateFallback"
    )
    public String createOrder(@PathVariable Long orderId) {
        // 模拟支付服务50%概率异常
        if (Math.random() > 0.5) {
            throw new RuntimeException("支付服务连接超时！");
        }
        return "订单" + orderId + "创建成功，已调用支付服务";
    }

    /**
     * 熔断降级方法：参数和返回值与原方法一致
     */
    public String payCreateFallback(Long orderId) {
        return "订单" + orderId + "：支付服务暂时不可用，已自动降级（返回默认值）";
    }
}
```

## 控制台配置熔断规则

1. 访问 `http://localhost:8080/order/create/1001`（让控制台识别 `payCreate` 资源）

2. 控制台 → 左侧「熔断降级规则」→ 「新增」

3. 配置项填写：

|配置项|填写值|配置说明|
|---|---|---|
|资源名|payCreate|要保护的资源|
|熔断策略|异常比例|按异常请求占比触发熔断|
|异常比例阈值|0.5|异常率>50%触发熔断|
|熔断时长|10|熔断后10秒内不调用下游|
|最小请求数|5|至少5个请求后才判断异常率|
|统计时长|10|统计最近10秒内的异常情况|
## 效果验证

快速访问 `http://localhost:8080/order/create/1001` 至少5次：

- **熔断前**：50%概率返回成功，50%概率抛异常

- **熔断后**：异常率>50%时，10秒内访问直接返回降级提示，不再调用下游逻辑

- **熔断恢复**：10秒后进入半开状态，允许少量请求尝试调用，异常率降低则恢复正常

核心结论：熔断降级通过暂时切断异常下游调用，避免异常扩散导致服务雪崩，保障本服务稳定。

# 案例4：「热点规则」—— 对参数精准限流

## 场景定义

给 `/goods/getById/{goodsId}` 接口配置热点规则：普通商品ID（如1001）每秒最多5个请求，热点商品ID（如10086）每秒最多1个请求（精准限流）。

## 代码实现（GoodsController）

```java

package com.example.sentineldemo.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoodsController {
    /**
     * 热点规则核心：接口必须有参数（goodsId）
     */
    @GetMapping("/goods/getById/{goodsId}")
    @SentinelResource(
            value = "goodsGetById",
            blockHandler = "goodsGetByIdBlockHandler"
    )
    public String getGoodsById(@PathVariable Long goodsId) {
        return "成功查询商品，ID：" + goodsId;
    }

    public String goodsGetByIdBlockHandler(Long goodsId, BlockException e) {
        return "商品" + goodsId + "请求太频繁，请稍后再试";
    }
}
```

## 控制台配置热点规则

1. 访问 `http://localhost:8080/goods/getById/1001`（让控制台识别 `goodsGetById` 资源）

2. 控制台 → 左侧「热点规则」→ 「新增」

3. 配置项填写：

|配置项|填写值|配置说明|
|---|---|---|
|资源名|goodsGetById|要保护的资源|
|参数索引|0|第一个参数（goodsId）|
|限流模式|QPS|按每秒请求数限流|
|单机阈值|5|普通商品ID的默认阈值|
|例外项|10086=1|热点ID 10086阈值设为1|
## 效果验证

- 访问 `http://localhost:8080/goods/getById/1001`（普通ID）：每秒≤5次返回成功，每秒>5次返回降级提示

- 访问 `http://localhost:8080/goods/getById/10086`（热点ID）：每秒>1次就返回降级提示（限流更严格）

核心结论：热点规则针对资源的特定参数值精准限流，避免热点参数请求压垮资源，不影响其他正常参数请求。

# 案例5：「授权规则」—— IP白名单（接口权限控制）

## 场景定义

只允许本地IP（127.0.0.1）访问 `/pay/create` 接口，其他IP访问直接拒绝。

## 步骤1：自定义请求来源解析（获取请求IP）

新建配置类，告诉Sentinel如何获取请求的来源（这里用IP作为来源标识）：

```java

package com.example.sentineldemo.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc.callback.RequestOriginParser;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 * 解析请求的来源（这里用IP作为来源标识）
 */
@Component
public class IpRequestOriginParser implements RequestOriginParser {
    @Override
    public String parseOrigin(HttpServletRequest request) {
        // 返回请求的IP地址（作为授权规则的“来源标识”）
        return request.getRemoteAddr();
    }
}
```

## 步骤2：支付接口代码（PayController）

```java

package com.example.sentineldemo.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PayController {
    @GetMapping("/pay/create")
    @SentinelResource(
            value = "payCreate",
            blockHandler = "payCreateBlockHandler"
    )
    public String createPay() {
        return "支付接口调用成功（仅本地IP可访问）";
    }

    public String payCreateBlockHandler(BlockException e) {
        return "无访问权限！仅本地IP可调用支付接口";
    }
}
```

## 步骤3：控制台配置授权规则

1. 访问 `http://localhost:8080/pay/create`（让控制台识别 `payCreate` 资源）

2. 控制台 → 左侧「授权规则」→ 「新增」

3. 配置项填写：

|配置项|填写值|配置说明|
|---|---|---|
|资源名|payCreate|要保护的资源|
|授权类型|白名单|只允许白名单中的来源访问|
|授权规则|127.0.0.1|允许访问的IP地址|
## 效果验证

- 本地访问 `http://localhost:8080/pay/create`（IP=127.0.0.1）：返回 `支付接口调用成功（仅本地IP可访问）`

- 用手机/另一台电脑访问（IP非127.0.0.1）：返回 `无访问权限！仅本地IP可调用支付接口`

核心结论：授权规则通过IP/客户端的黑白名单实现接口权限控制，防止核心接口被非法调用。

# 案例6：「系统规则」—— 服务级别的兜底保护

## 场景定义

配置「CPU使用率阈值=80%」的系统规则：当服务CPU使用率超过80%时，所有接口都会被限流，避免服务宕机。

## 代码实现

无需修改任何业务代码（系统规则是全局的，针对整个服务）。

## 控制台配置系统规则

1. 控制台 → 左侧「系统规则」→ 「新增」

2. 配置项填写：

|配置项|填写值|配置说明|
|---|---|---|
|CPU使用率阈值|80|CPU>80%触发全局限流|
## 效果验证（本地测试）

1. 用工具（如JMeter）压测项目，使CPU使用率超过80%

2. 访问任何接口（`/user/getById/1`、`/pay/create` 等）：都会返回降级提示

3. 停止压测，CPU使用率回落至80%以下：接口恢复正常

核心结论：系统规则是对整个服务的兜底保护，监控CPU、负载等系统级指标，防止服务因资源耗尽整体宕机。

# 核心总结

|概念|通俗理解|核心作用|案例效果|
|---|---|---|---|
|资源|要保护的接口/方法|所有规则的基础|控制台簇点链路能看到标记的资源|
|流控规则|给资源设流量上限（QPS/并发数）|防接口过载|超出QPS的请求返回降级提示|
|熔断降级规则|下游异常时切断调用|防服务雪崩|异常率高时，10秒内直接返回降级值|
|热点规则|对参数值精准限流|防热点参数压垮资源|热点ID限流更严格|
|授权规则|IP/客户端的黑白名单|防接口滥用|非白名单IP访问被拒绝|
|系统规则|服务级别的资源保护（CPU/负载）|防服务整体宕机|CPU过高时全局限流|
所有规则的核心目标只有一个：**让服务在高流量/异常场景下，不是直接崩溃，而是“优雅降级”，始终保持可用**。
