package org.example;

public class 乐观锁2 {
  /*  MyBatis 代码配置（以 MyBatis - Plus 为例，最常用）
    核心思路：通过 MyBatis 拦截器自动处理版本号，不用手动写 version 条件。

    加依赖（如果用 MyBatis - Plus）
    非必需，但 MyBatis - Plus 能简化配置，直接用官方插件。
    Maven 依赖（Spring Boot 项目）：
    xml
            <dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-boot-starter</artifactId>
    <version>最新稳定版</version>
</dependency>

    配置乐观锁拦截器
    写一个配置类，注册 OptimisticLockerInnerInterceptor 插件，让 MyBatis 自动处理版本逻辑。
    java
    @Configuration
    public class MyBatisConfig {
        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor() {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            // 添加乐观锁拦截器
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            return interceptor;
        }
    }

    实体类标记版本字段
    在对应实体类（比如 Product ）里，给 version 字段加 @Version 注解，告诉 MyBatis - Plus 这是乐观锁版本字段。
    java
    @Data
    public class Product {
        private Long id;
        private Integer stock; // 库存
        // 关键：标记版本字段
        @Version
        private Integer version;
    }

    三、业务层操作（核心流程）
    步骤：查数据 → 改数据 → 保存 ，MyBatis 自动帮你加版本条件。

    查询数据（获取当前版本）
    用 selectById （或其他查询方法）查数据，此时会把数据库里的 version 一起查出来，赋值到实体类。
    java
    // 1. 查询商品数据（同时获取当前 version）
    Product product = productMapper.selectById(1L);
// 假设查到：id=1, stock=100, version=2

    修改业务数据
    直接改实体类的业务字段（比如扣减库存），不用手动改 version 。
    java
// 2. 扣减库存（业务操作）
product.setStock(product.getStock() - 1); // stock 变为 99


    执行更新（自动带版本条件）
    调用 updateById ，MyBatis - Plus 会自动拼接 version 条件，执行类似：
    UPDATE product SET stock=99, version=3 WHERE id=1 AND version=2
    java
    // 3. 执行更新（MyBatis 自动处理 version 条件）
    int rows = productMapper.updateById(product);
if (rows == 0) {
        // 更新失败：说明有其他线程改过数据，version 已变化
        // 这里可重试、提示用户等，比如重新查询最新数据再操作
        System.out.println("并发冲突，更新失败");
    } else {
        // 更新成功：version 已 +1（数据库里 version 变为 3）
        System.out.println("更新成功");
    }


    四、核心逻辑总结（一句话懂流程）
    查数据：把数据库当前 version 拿到实体类。
    改数据：只改业务字段，不管 version 。
    更数据：MyBatis 自动加 WHERE version=旧版本 条件，且更新后 version+1 。
    冲突时：更新返回 0 行，说明有其他线程改过，需处理（重试 / 提示）。*/
}
