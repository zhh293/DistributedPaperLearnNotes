package org.example.springai1.递归顾问;

public class Demo1 {
   /* 文档里的核心场景，用「结构化输出验证」举通俗例子
    文档中提到的 StructuredOutputValidationAdvisor（结构化输出验证顾问）是最常用的递归顾问，我们用它实现一个真实场景：
    场景需求
    让 AI 根据用户提供的 “用户昵称”，生成符合以下规则的「用户信息 JSON」：
    必须包含 3 个字段：username（字符串）、age（18-60 的整数）、email（合法邮箱格式）；
    格式错误时，AI 自动修正，最多重试 3 次；
    修正时，AI 能知道自己错在哪（比如 “缺少 email 字段”“age 不是整数”）。

    步骤 1：定义 “结构化输出模板”（告诉 AI 要生成什么格式）
先定义一个 Java 类，对应我们需要的 JSON 结构（Spring AI 会自动根据这个类生成 JSON Schema，用来验证 AI 输出）：
java
运行
// 结构化输出的目标类型：用户信息
public class UserInfo {
    private String username; // 用户名（必填）
    private Integer age;     // 年龄（18-60整数，必填）
    private String email;    // 邮箱（合法格式，必填）

    // 必须有getter/setter（Spring AI需要反射解析字段）
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
步骤 2：创建「递归顾问」（结构化输出验证器）
用 Spring AI 内置的 StructuredOutputValidationAdvisor，配置 “验证规则” 和 “重试逻辑”：
java
运行
import org.springframework.ai.chat.advisor.StructuredOutputValidationAdvisor;

// 1. 创建结构化输出验证顾问（递归顾问的一种）
StructuredOutputValidationAdvisor validationAdvisor = StructuredOutputValidationAdvisor.builder()
    .outputType(UserInfo.class) // 告诉顾问：要验证的目标格式是UserInfo
    .maxRepeatAttempts(3)       // 最多重试3次（失败3次就停止）
    .advisorOrder(1000)         // 顾问执行顺序（数字越大越靠后，确保先验证再返回）
    .build();
步骤 3：把顾问加入 AI 调用链，发起请求
将递归顾问嵌入 ChatClient，让 AI 生成用户信息时，自动触发 “验证 - 重试” 逻辑：
java
运行
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.chat.client.ChatClient;

// 1. 初始化AI模型（比如OpenAI GPT-5，替换成你的API密钥）
OpenAiChatModel chatModel = new OpenAiChatModel(
    OpenAiChatOptions.builder()
        .apiKey("你的OpenAI API密钥")
        .model("gpt-5-mini")
        .build()
);

// 2. 构建ChatClient，加入递归顾问（验证器）
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(validationAdvisor) // 把“质检员”加入流水线
    .build();

// 3. 发起请求：让AI生成用户名为“小明”的UserInfo
String prompt = "生成用户名为'小明'的用户信息，严格按照JSON格式返回，不要多余文字";
UserInfo userInfo = chatClient.prompt()
    .user(prompt)
    .call()
    .entity(UserInfo.class); // 自动解析AI输出为UserInfo对象

// 4. 输出结果
System.out.println("用户名：" + userInfo.getUsername());
System.out.println("年龄：" + userInfo.getAge());
System.out.println("邮箱：" + userInfo.getEmail());
步骤 4：看「递归顾问」怎么工作（执行流程拆解）
假设 AI 第一次生成的输出有问题，递归顾问会自动触发 “返工”：
第一次 AI 调用：AI 可能生成缺失email的 JSON（不符合规则）：
json
{ "username": "小明", "age": 25 }
递归顾问验证：检测到 “缺少email字段”，验证失败；
自动重试：顾问自动修改提示词，加上错误原因，重新调用 AI：
plaintext
生成用户名为'小明'的用户信息，严格按照JSON格式返回，不要多余文字。
错误提示：缺少必填字段email（合法邮箱格式），请补充完整。
第二次 AI 调用：AI 可能生成age为字符串的 JSON（仍不符合规则）：
json
{ "username": "小明", "age": "25", "email": "xiaoming@example.com" }
递归顾问再次验证：检测到 “age必须是整数”，验证失败；
再次重试：顾问更新提示词，补充新的错误原因：
plaintext
生成用户名为'小明'的用户信息，严格按照JSON格式返回，不要多余文字。
错误提示：1. 缺少必填字段email（合法邮箱格式）；2. age必须是18-60的整数（不能是字符串）。请修正。
第三次 AI 调用：AI 生成符合所有规则的 JSON：
json
{ "username": "小明", "age": 25, "email": "xiaoming@example.com" }
验证通过：递归顾问停止循环，将结果解析为UserInfo对象返回。


    */
}
