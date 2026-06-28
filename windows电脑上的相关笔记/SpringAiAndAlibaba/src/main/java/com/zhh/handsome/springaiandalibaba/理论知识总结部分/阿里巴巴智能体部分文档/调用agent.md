调用 Agent
基础调用
使用 call 方法获取最终响应：

Agent 基础调用示例
查看完整代码
import org.springframework.ai.chat.messages.AssistantMessage;

// 字符串输入
AssistantMessage response = agent.call("杭州的天气怎么样？");
System.out.println(response.getText());

// UserMessage 输入
UserMessage userMessage = new UserMessage("帮我分析这个问题");
AssistantMessage response = agent.call(userMessage);

// 多个消息
List<Message> messages = List.of(
new UserMessage("我想了解 Java 多线程"),
new UserMessage("特别是线程池的使用")
);
AssistantMessage response = agent.call(messages);

获取完整状态
使用 invoke 方法获取完整的执行状态：

使用 invoke 获取完整状态
查看完整代码
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.Optional;

Optional<OverAllState> result = agent.invoke("帮我写一首诗");

if (result.isPresent()) {
OverAllState state = result.get();

// 访问消息历史
Optional<Object> messages = state.value("messages");
List<Message> messageList = (List<Message>) messages.get();

// 访问自定义状态
Optional<Object> customData = state.value("custom_key");

System.out.println("完整状态：" + state);
}

使用配置
通过 RunnableConfig 传递运行时配置：

使用 RunnableConfig 传递配置
查看完整代码
import com.alibaba.cloud.ai.graph.RunnableConfig;

String threadId = "thread_123";
RunnableConfig runnableConfig = RunnableConfig.builder()
.threadId(threadId)
.addMetadata("key", "value")
.build();

AssistantMessage response = agent.call("你的问题", runnableConfig);

高级特性
结构化输出
在某些情况下，你可能希望 Agent 以特定格式返回输出。ReactAgent 提供了两种策略。

使用 outputType
通过 Java 类定义输出结构，Agent 会自动生成对应的 JSON Schema：

PoemOutput 结构化输出示例
查看完整代码
public class PoemOutput {
private String title;
private String content;
private String style;

// Getters and Setters
public String getTitle() { return title; }
public void setTitle(String title) { this.title = title; }

public String getContent() { return content; }
public void setContent(String content) { this.content = content; }

public String getStyle() { return style; }
public void setStyle(String style) { this.style = style; }
}

ReactAgent agent = ReactAgent.builder()
.name("poem_agent")
.model(chatModel)
.outputType(PoemOutput.class)
.saver(new MemorySaver())
.build();

AssistantMessage response = agent.call("写一首关于春天的诗");
// 输出会遵循 PoemOutput 的结构
System.out.println(response.getText());

使用 outputSchema
使用 BeanOutputConverter 生成输出 Schema，提供类型安全和自动 schema 生成：

使用 outputSchema 自定义输出格式
查看完整代码
import org.springframework.ai.converter.BeanOutputConverter;

// 定义输出类型
public static class TextAnalysisResult {
private String summary;
private List<String> keywords;
private String sentiment;
private Double confidence;

// Getters and Setters
public String getSummary() { return summary; }
public void setSummary(String summary) { this.summary = summary; }
public List<String> getKeywords() { return keywords; }
public void setKeywords(List<String> keywords) { this.keywords = keywords; }
public String getSentiment() { return sentiment; }
public void setSentiment(String sentiment) { this.sentiment = sentiment; }
public Double getConfidence() { return confidence; }
public void setConfidence(Double confidence) { this.confidence = confidence; }
}

// 使用 BeanOutputConverter 生成 outputSchema
BeanOutputConverter<TextAnalysisResult> outputConverter = new BeanOutputConverter<>(TextAnalysisResult.class);
String format = outputConverter.getFormat();

ReactAgent agent = ReactAgent.builder()
.name("analysis_agent")
.model(chatModel)
.outputSchema(format)
.saver(new MemorySaver())
.build();

AssistantMessage response = agent.call("分析这段文本：春天来了，万物复苏。");


选择建议：

outputType：类型安全，适合结构固定的场景（推荐）
outputSchema：使用 BeanOutputConverter 生成时提供类型安全，手动提供字符串时灵活性高，适合动态或复杂的输出格式























invoke 方法深度解析
1. invoke 方法的本质定位
   invoke 是 Spring AI Alibaba 为 ReactAgent 设计的全量状态获取方法，核心目的是返回 Agent 完整的执行链路信息 —— 不仅包含最终回答，还包括 ReAct 循环的每一步（推理、工具调用、中间思考、错误信息等）。
   对比你之前看到的 call 方法（仅返回最终的 AssistantMessage 响应），invoke 相当于给 Agent 的执行过程做了一次 “全量录像”，而 call 只给你 “最终结果截图”。

2. 方法核心信息
   维度	具体说明
   方法签名	Optional<OverAllState> invoke(String userInput)
   Optional<OverAllState> invoke(UserMessage message)
   Optional<OverAllState> invoke(List<Message> messages)
   返回值	Optional<OverAllState>：
- Optional 避免执行异常 / 无结果时返回 null，是 Java 安全编程规范；
- OverAllState 是 Agent 执行的 “全量状态容器”，存储从启动到结束的所有信息。
  核心设计目的	暴露 Agent 内部执行细节（推理过程、工具调用记录、消息历史、自定义状态等），用于调试、排障、审计、自定义中间逻辑。
3. OverAllState 核心能力（最关键的部分）
   OverAllState 是整个 Agent 执行过程的 “状态快照”，通过 state.value(String key) 可以获取核心信息，官方定义的稳定 Key 如下（无需联网，是 Spring AI Alibaba 内置的核心字段）：



核心 Key	对应值类型	含义说明
messages	List<Message>	完整的消息历史：包含用户输入、模型思考（Thinking）、工具调用请求、工具返回结果、最终响应等所有消息
tool_calls	List<ToolCallRecord>	工具调用记录：调用了哪些工具、入参、执行耗时、成功 / 失败状态、返回结果
model_calls	List<ModelCallRecord>	模型调用记录：模型调用次数、每次的请求参数（temperature/maxToken）、响应耗时、Token 消耗
error	Exception（可选）	执行过程中的异常信息（如果有）
thread_id	String	会话 ID（关联 Memory，用于多会话隔离）
自定义 Key	任意 Object	你通过 Hooks/Interceptors 手动添加的状态（如 answer_found、error_count）
除此之外，OverAllState 还有常用辅助 API：
state.threadId()：直接获取会话 ID（无需通过 value("thread_id")）；
state.metadata()：获取执行元数据（如总耗时、状态码）；
state.node()：获取当前执行节点（如 MODEL/TOOL/HOOK）。





实际应用场景（为什么需要 invoke）
调试排障：比如 Agent 没调用预期的工具，通过 invoke 查看 messages 中的模型思考过程，就能知道 “模型为什么没选择调用工具”；
审计合规：金融 / 政务场景需要记录 Agent 完整执行链路（用户提问→模型推理→工具调用→最终回答），满足合规要求；
成本优化：通过 model_calls 统计模型调用次数、Token 消耗，优化推理成本；
自定义逻辑：比如工具调用失败后，通过 tool_calls 中的失败信息自动重试，或根据模型思考调整最终响应。