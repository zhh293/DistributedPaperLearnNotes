Memory（记忆）
Agent 通过状态自动维护对话历史。使用 MemorySaver 配置持久化存储。

Memory 配置示例
查看完整代码
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.constant.SaverEnum;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

// 配置内存存储
ReactAgent agent = ReactAgent.builder()
.name("chat_agent")
.model(chatModel)
.saver(new MemorySaver())
.build();

// 使用 thread_id 维护对话上下文
RunnableConfig config = RunnableConfig.builder()
.threadId("user_123")
.build();

agent.call("我叫张三", config);
agent.call("我叫什么名字？", config);  // 输出: "你叫张三"

生产环境：使用 RedisSaver、MongoSaver 等持久化存储替代 MemorySaver。