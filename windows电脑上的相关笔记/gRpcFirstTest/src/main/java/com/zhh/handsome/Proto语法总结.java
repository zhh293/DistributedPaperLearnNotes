/*
package com.zhh.handsome;

public class Proto语法总结 {
    你现在明确了需要一份聚焦 Java 语言的 Protocol Buffers（Proto）文件常用语法总结，重点围绕 Proto 文件在 Java 开发中的配置、类型映射、代码生成及使用规则，下面我会结合 Java 开发的实际场景，重新梳理并补充核心语法，确保内容贴合你的使用需求。
    一、Proto 文件基础配置（Java 专属）
    Proto 文件的基础配置直接决定生成的 Java 代码结构，这是 Java 开发中最核心的前置步骤。
            1. 版本声明（必须）
    Java 开发中优先使用 proto3（兼容性更好、语法更简洁），proto2 仅用于老项目兼容：
    protobuf
// 核心：声明 proto3 版本（Java 开发首选）
            syntax = "proto3";
2. 包名与 Java 包名（核心区分）
            package：Proto 层面的命名空间，避免 Proto 文件间的命名冲突；
    option java_package：生成的 Java 类的实际包名（必须符合 Java 包名规范，如 com.example.user）；
    option java_outer_classname：生成的 Java 外部类名（默认是 Proto 文件名首字母大写，建议显式指定，避免冲突）；
    option java_multiple_files：是否拆分生成多个 Java 类（推荐 true，否则所有消息 / 枚举都嵌套在外部类中，不便于使用）。
    protobuf
// Proto 命名空间（仅 Proto 层面）
package user.v1;

    // Java 核心配置（必配）
    option java_package = "com.example.user.v1"; // 生成的 Java 类包名
    option java_outer_classname = "UserProto";  // 外部类名（避免默认值冲突）
    option java_multiple_files = true;          // 拆分生成多个类（推荐）

    // 可选：生成 equals/hashCode 方法（Java 实用特性）
    option java_generate_equals_and_hash_code = true;
    // 可选：生成序列化相关方法（默认已开启，无需手动设置）
    option java_enable_deprecated_descriptor_accessor = false;
3. 导入文件（Java 场景）
    Java 开发中常用的导入场景：
    protobuf
// 导入自定义 Proto 文件（复用其他消息类型）
import "common/v1/BaseResponse.proto";

// 导入 Protobuf 官方提供的通用消息（Java 开发高频使用）
import "google/protobuf/empty.proto";    // 空请求/响应（如无参数接口）
import "google/protobuf/timestamp.proto";// 时间戳（替代 Java 的 Date）
import "google/protobuf/wrappers.proto"; // 包装类型（解决基础类型默认值问题）
    二、Proto 类型与 Java 类型精准映射（Java 开发核心）
    Proto 的标量类型、高级类型与 Java 类型的映射是 Java 开发的核心，必须精准掌握，避免类型转换问题。
            1. 标量类型（基础类型）
    Proto3 类型	描述	Java 对应类型	注意事项（Java 场景）
    int32	32 位有符号整数	int	超出范围会溢出，需用 int64
    int64	64 位有符号整数	long	Java 中需用 long 接收，避免拆箱 / 装箱问题
    uint32	32 位无符号整数	int	超出 int 范围时，Java 中实际存储为负数
    uint64	64 位无符号整数	long	超出 long 范围需用 ByteString 处理
    sint32	32 位有符号（压缩优化）	int	适合存储负数，比 int32 更节省空间
    sint64	64 位有符号（压缩优化）	long	同上，适合负数
    bool	布尔值	boolean	Java 原生 boolean，无默认值陷阱
    string	UTF-8 字符串	String	不能为空字符串？用 google.protobuf.StringValue
    bytes	字节数组	com.google.protobuf.ByteString	需转换为 byte []：bytes.toByteArray()
    float	32 位浮点数	float	精度要求高用 double
    double	64 位浮点数	double	Java 中优先使用
    Java 场景示例：
    protobuf
    message UserInfo {
        int64 id = 1;                // Java: long id
        string username = 2;         // Java: String username
        bool is_vip = 3;             // Java: boolean isVip
        double balance = 4;          // Java: double balance
        bytes avatar = 5;            // Java: ByteString avatar → 转 byte[]: avatar.toByteArray()
        google.protobuf.Timestamp create_time = 6; // Java: Timestamp createTime（时间戳）
    }
2. 包装类型（解决 Java 基础类型默认值问题）
    Proto3 中基础类型有默认值（如 int=0、bool=false），无法区分 “未赋值” 和 “值为默认值”，Java 开发中需用 Protobuf 官方提供的包装类型：
    Proto 包装类型	Java 对应类型	用途
    google.protobuf.Int32Value	Integer	可空 int
    google.protobuf.Int64Value	Long	可空 long
    google.protobuf.BoolValue	Boolean	可空 boolean
    google.protobuf.StringValue	String	可空 String（区分空串）
    google.protobuf.DoubleValue	Double	可空 double
    Java 场景示例：
    protobuf
import "google/protobuf/wrappers.proto";

    message UserProfile {
        // 可空年龄：Java 中为 Integer age（null 表示未赋值，0 表示明确值 0）
        google.protobuf.Int32Value age = 1;
        // 可空手机号：Java 中为 String phone（null 表示未填，"" 表示空串）
        google.protobuf.StringValue phone = 2;
    }
3. 字段规则（Java 场景）
    规则	Proto3 支持	Java 对应类型	说明
    singular	默认	单个类型（如 long）	未赋值时取默认值（Java 中基础类型非 null）
    repeated	支持	List<T>	对应 Java 的 List，生成 addXxx()/getXxxList() 方法
    Java 场景示例：
    protobuf
    message UserList {
        // Java: List<UserInfo> users（生成 getUsersList()/addAllUsers() 等方法）
        repeated UserInfo users = 1;
        // Java: List<String> tags
        repeated string tags = 2;
    }
    三、高级类型（Java 开发高频使用）
            1. 枚举（Enum）
    Proto 枚举在 Java 中生成标准 Enum 类，核心规则：
    枚举值必须从 0 开始（Java 中默认值为第一个枚举值）；
    枚举名 / 值名需符合 Java 命名规范（大驼峰 / 大写下划线）；
    支持别名（需开启 allow_alias）。
    protobuf
    enum UserStatus {
        option allow_alias = true; // 允许别名（Java 中枚举值相同）
        USER_STATUS_UNKNOWN = 0;   // Java: UserStatus.USER_STATUS_UNKNOWN（默认值）
        USER_STATUS_ACTIVE = 1;    // Java: UserStatus.USER_STATUS_ACTIVE
        USER_STATUS_NORMAL = 1;    // 别名：Java 中与 ACTIVE 等价
        USER_STATUS_BANNED = 2;    // Java: UserStatus.USER_STATUS_BANNED
        }

    message User {
        UserStatus status = 1; // Java: UserStatus status
    }
2. 映射类型（Map）
    Proto 的 Map 类型在 Java 中生成 Map<K, V> 接口（默认是 HashMap 实现）：
    protobuf
    message UserExt {
        // Java: Map<String, String> attrs（生成 getAttrsMap() 方法）
        map<string, string> ext_attrs = 1;
        // Java: Map<Long, UserInfo> followees
        map<int64, UserInfo> followees = 2;
    }
3. 嵌套消息（Java 类结构）
    Proto 嵌套消息在 Java 中生成静态内部类（开启 java_multiple_files = true 时，会拆分为独立类）：
    protobuf
    message User {
        int64 id = 1;

        // 嵌套消息：Java 中默认生成 static class Address（开启 multiple_files 则为独立类）
        message Address {
            string province = 1; // Java: String province
            string city = 2;     // Java: String city
        }

        // Java: List<Address> addresses
        repeated Address addresses = 2;
    }

    // 外部引用：Java 中直接用 User.Address（multiple_files=true 时为独立类 Address）
    message Order {
        User.Address shipping_address = 1;
    }
4. 保留字段（Java 兼容性）
    用于标记废弃字段，避免后续复用导致 Java 代码序列化 / 反序列化兼容问题：
    protobuf
    message User {
        // 保留字段编号：1、3 不能复用
        reserved 1, 3;
        // 保留字段名："age"、"phone" 不能复用
        reserved "age", "phone";

        int64 id = 2; // 合法字段
    }
    四、RPC 服务定义（Java + gRPC 场景）
    Java 开发中 Proto 常用于定义 gRPC 服务接口，生成对应的 Java 服务端 / 客户端代码：
    protobuf
    service UserService {
        // 普通 RPC：Java 生成 UserServiceGrpc.UserServiceBlockingStub#GetUser
        rpc GetUser(GetUserRequest) returns (GetUserResponse);

        // 服务端流式 RPC：Java 生成异步流式方法
        rpc GetUserStream(GetUserRequest) returns (stream UserInfo);
    }

    // 请求消息
    message GetUserRequest {
        int64 user_id = 1; // Java: long userId
    }

    // 响应消息
    message GetUserResponse {
        UserInfo user = 1;          // Java: UserInfo user
        int32 code = 2;             // Java: int code
        string message = 3;         // Java: String message
    }
    五、Java 中使用 Proto 的完整流程（实操）
            1. 依赖引入（Maven 示例）
    Java 项目需引入 Protobuf 核心依赖和插件（用于编译 .proto 文件）：
    xml
            <!-- pom.xml -->
<dependencies>
  <!-- Protobuf 核心依赖 -->
  <dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
    <version>3.25.3</version>
  </dependency>
  <!-- gRPC 依赖（如需定义 RPC 服务） -->
  <dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-protobuf</artifactId>
    <version>1.59.0</version>
  </dependency>
  <dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-stub</artifactId>
    <version>1.59.0</version>
  </dependency>
</dependencies>

<!-- 编译 Proto 文件的插件 -->
<build>
  <extensions>
    <extension>
      <groupId>kr.motd.maven</groupId>
      <artifactId>os-maven-plugin</artifactId>
      <version>1.7.0</version>
    </extension>
  </extensions>
  <plugins>
    <plugin>
      <groupId>org.xolstice.maven.plugins</groupId>
      <artifactId>protobuf-maven-plugin</artifactId>
      <version>0.6.1</version>
      <configuration>
        <protocArtifact>com.google.protobuf:protoc:3.25.3:exe:${os.detected.classifier}</protocArtifact>
        <pluginId>grpc-java</pluginId>
        <pluginArtifact>io.grpc:protoc-gen-grpc-java:1.59.0:exe:${os.detected.classifier}</pluginArtifact>
        <protoSourceRoot>src/main/proto</protoSourceRoot> <!-- Proto 文件目录 -->
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>compile</goal>
            <goal>compile-custom</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
            2. 编译 Proto 文件
    执行 Maven 命令，自动生成 Java 代码：
    bash
            运行
    mvn clean compile
    生成的代码位于 target/generated-sources/protobuf/ 目录下。
            3. Java 中使用生成的代码
    java
            运行
package com.example.user.v1;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import java.util.List;

    public class UserProtoDemo {
        public static void main(String[] args) {
            // 1. 构建嵌套消息（地址）
            UserInfo.Address address = UserInfo.Address.newBuilder()
                    .setProvince("广东省")
                    .setCity("深圳市")
                    .build();

            // 2. 构建时间戳
            Timestamp createTime = Timestamp.newBuilder()
                    .setSeconds(System.currentTimeMillis() / 1000)
                    .setNanos((int) (System.currentTimeMillis() % 1000 * 1000000))
                    .build();

            // 3. 构建核心消息（用户信息）
            UserInfo user = UserInfo.newBuilder()
                    .setId(1001L) // long 类型
                    .setUsername("张三")
                    .setIsVip(true)
                    .setBalance(99.9)
                    .setAvatar(ByteString.copyFrom(new byte[]{0x01, 0x02})) // bytes 转 ByteString
                    .setCreateTime(createTime)
                    .addAddresses(address) // 添加列表元素
                    .build();

            // 4. 读取字段值
            long userId = user.getId();
            String username = user.getUsername();
            List<UserInfo.Address> addresses = user.getAddressesList();

            // 5. 序列化/反序列化（Protobuf 核心能力）
            byte[] bytes = user.toByteArray(); // 序列化为字节数组
            UserInfo parsedUser = UserInfo.parseFrom(bytes); // 反序列化

            System.out.println("解析后的用户名：" + parsedUser.getUsername());
        }
    }
    总结（Java 开发核心要点）
    配置优先：java_package/java_outer_classname/java_multiple_files 是 Java 开发的必配项，直接决定代码结构和易用性；
    类型映射：Proto 的 int64 对应 Java 的 long，bytes 对应 ByteString（需转 byte []），包装类型解决基础类型默认值陷阱；
    代码生成：通过 Maven/Gradle 插件编译 Proto 文件，生成的 Builder 模式类是 Java 中操作消息的核心方式；
    兼容性：字段编号一旦发布不可修改，废弃字段用 reserved 标记，避免 Java 序列化 / 反序列化兼容问题；
    高频工具类：google.protobuf.Timestamp（替代 Date）、google.protobuf.StringValue（可空字符串）是 Java 开发的常用扩展类型。
}
*/
