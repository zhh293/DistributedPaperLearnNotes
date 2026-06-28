Interceptors（拦截器）
Interceptors 提供更细粒度的控制，可以拦截和修改模型调用和工具执行。



import com.alibaba.cloud.ai.graph.agent.interceptor.*;

// ModelInterceptor - 内容安全检查
public class GuardrailInterceptor extends ModelInterceptor {
@Override
public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
// 前置：检查输入
if (containsSensitiveContent(request.getMessages())) {
return ModelResponse.of(AssistantMessage.builder().content("检测到不适当的内容").build());
}

      // 执行调用
      ModelResponse response = handler.call(request);

      // 后置：检查输出
      return sanitizeIfNeeded(response);
}
}

// ToolInterceptor - 监控和错误处理
public class ToolMonitoringInterceptor extends ToolInterceptor {
@Override
public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
long startTime = System.currentTimeMillis();
try {
ToolCallResponse response = handler.call(request);
logSuccess(request, System.currentTimeMillis() - startTime);
return response;
} catch (Exception e) {
logError(request, e, System.currentTimeMillis() - startTime);
return ToolCallResponse.error(request.getToolCall(),
"工具执行遇到问题，请稍后重试");
}
}
}

// 组合使用
ReactAgent agent = ReactAgent.builder()
.name("my_agent")
.model(chatModel)
.interceptors(new GuardrailInterceptor(), new LoggingInterceptor(), new ToolMonitoringInterceptor())
.saver(new MemorySaver())
.build();


常见用途：

ModelInterceptor：内容安全、动态提示、日志记录、性能监控
ToolInterceptor：错误重试、权限检查、结果缓存、审计日志

























控制与流式输出
迭代控制
通过 Hooks 控制 Agent 的执行迭代，防止无限循环或过度成本。

使用 ModelCallLimitHook 限制模型调用次数
查看完整代码
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

// 使用内置的 ModelCallLimitHook 限制模型调用次数
ReactAgent agent = ReactAgent.builder()
.name("my_agent")
.model(chatModel)
.hooks(ModelCallLimitHook.builder().runLimit(5).build())  // 限制最多调用 5 次
.saver(new MemorySaver())
.build();


自定义停止条件 Hook
查看完整代码
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import org.springframework.ai.chat.messages.AssistantMessage;

// 自定义停止条件：基于状态判断是否继续
@HookPositions({HookPosition.BEFORE_MODEL})
public class CustomStopConditionHook extends ModelHook {

@Override
public String getName() {
return "custom_stop_condition";
}

@Override
public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
// 检查是否找到答案，展示使用 OverAllState
boolean answerFound = (Boolean) state.value("answer_found").orElse(false);
// 检查错误次数，展示使用 RunnableConfig
int errorCount = (Integer) config.context().get("error_count").orElse(0);

      // 找到答案或错误过多时停止
      if (answerFound || errorCount > 3) {
          List<Message> messages = new ArrayList<>();
          messages.add(new AssistantMessage(
              answerFound ? "已找到答案，Agent 执行完成。"
                          : "错误次数过多 (" + errorCount + ")，Agent 执行终止。"
          ));
          // the messages will be appended to the original message list context.
          return CompletableFuture.completedFuture(Map.of("messages", messages));
      }

      return CompletableFuture.completedFuture(Map.of());
}

}

// 使用自定义停止条件
ReactAgent agent = ReactAgent.builder()
.name("my_agent")
.model(chatModel)
.hooks(new CustomStopConditionHook())
.saver(new MemorySaver())
.build();


流式输出
在 Agent 场景中，流式输出的核心是处理 StreamingOutput 类型。无论是模型推理、工具调用还是 Hook 节点，输出都统一为这个类型。

使用 OutputType 区分输出类型：

通过 OutputType 枚举可以区分不同节点的输出，以及判断是流式增量内容还是完成输出：

OutputType	说明
AGENT_MODEL_STREAMING	模型推理的流式增量内容
AGENT_MODEL_FINISHED	模型推理完成，可获取全量内容
AGENT_TOOL_STREAMING	工具调用的流式增量内容
AGENT_TOOL_FINISHED	工具调用完成
AGENT_HOOK_STREAMING	Hook 节点的流式增量内容
AGENT_HOOK_FINISHED	Hook 节点完成