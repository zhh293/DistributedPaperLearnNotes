Tools（工具）
工具赋予 Agent 执行操作的能力，支持顺序执行、并行调用、动态选择和错误处理。

定义和使用工具
SearchTool 自定义工具示例
查看完整代码
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import java.util.function.BiFunction;

// 定义工具（示例：仅一个搜索工具）
public class SearchTool implements BiFunction<String, ToolContext, String> {
@Override
public String apply(String query, ToolContext context) {
// 实现搜索逻辑
return "搜索结果: " + query;
}
}

// 创建工具回调
ToolCallback searchTool = FunctionToolCallback.builder("search", new SearchTool())
.description("搜索工具")
.build();

// 在Agent中使用
ReactAgent agent = ReactAgent.builder()
.name("search_agent")
.model(chatModel)
.tools(searchTool)
.build();

工具错误处理
ToolErrorInterceptor 工具错误处理
查看完整代码
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;

public class ToolErrorInterceptor extends ToolInterceptor {
@Override
public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
try {
return handler.call(request);
} catch (Exception e) {
return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
"Tool failed: " + e.getMessage());
}
}

@Override
public String getName() {
return "ToolErrorInterceptor";
}
}

ReactAgent agent = ReactAgent.builder()
.name("my_agent")
.model(chatModel)
.interceptors(new ToolErrorInterceptor())
.build();


ReAct 循环示例：Agent 自动交替进行推理和工具调用，直到获得最终答案。

用户: 查询杭州天气并推荐活动
→ [推理] 需要查天气 → [行动] get_weather("杭州") → [观察] 晴，25°C
→ [推理] 需要推荐活动 → [行动] search("户外活动") → [观察] 西湖游玩...
→ [推理] 信息充足 → [行动] 生成答案