import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Optional;

public class AgentInvokeDemo {
public static void main(String[] args) {
// 假设你已经初始化好 ReactAgent（包含模型、工具等配置）
ReactAgent agent = initAgent();

        // 1. 调用 invoke 获取全量状态
        Optional<OverAllState> result = agent.invoke("查询杭州天气并推荐户外活动");

        if (result.isPresent()) {
            OverAllState state = result.get();

            // 2. 获取核心状态：完整消息历史
            Optional<Object> messagesObj = state.value("messages");
            if (messagesObj.isPresent() && messagesObj.get() instanceof List<?>) {
                List<Message> messageList = (List<Message>) messagesObj.get();
                // 遍历消息历史，打印每一步（调试/审计核心）
                for (Message msg : messageList) {
                    System.out.println("【消息类型】：" + msg.getClass().getSimpleName());
                    System.out.println("【消息内容】：" + msg.getText());
                    System.out.println("------------------------");
                }
            }

            // 3. 获取工具调用记录（关键排障信息）
            Optional<Object> toolCallsObj = state.value("tool_calls");
            if (toolCallsObj.isPresent() && toolCallsObj.get() instanceof List<?>) {
                List<?> toolCallRecords = (List<?>) toolCallsObj.get();
                System.out.println("【工具调用次数】：" + toolCallRecords.size());
                // 可进一步解析每个工具调用的参数、结果、状态
            }

            // 4. 获取自定义状态（如果你在 Hooks 中添加了）
            Optional<Object> customData = state.value("answer_found");
            if (customData.isPresent()) {
                boolean answerFound = (Boolean) customData.get();
                System.out.println("【是否找到最终答案】：" + answerFound);
            }

            // 5. 获取会话 ID（关联 Memory）
            String threadId = state.threadId();
            System.out.println("【会话ID】：" + threadId);
        } else {
            System.out.println("Agent 执行无结果（可能异常）");
        }
    }

    // 初始化 Agent 的辅助方法（简化版）
    private static ReactAgent initAgent() {
        // 这里替换为你实际的 Agent 初始化逻辑（模型、工具、配置等）
        return ReactAgent.builder()
                .name("demo_agent")
                .model(initChatModel())
                .saver(new com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver())
                .build();
    }

    private static com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel initChatModel() {
        // 替换为你实际的模型初始化逻辑（配置 apiKey 等）
        com.alibaba.cloud.ai.dashscope.api.DashScopeApi dashScopeApi = com.alibaba.cloud.ai.dashscope.api.DashScopeApi.builder()
                .apiKey(System.getenv("AI_DASHSCOPE_API_KEY"))
                .build();
        return com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .build();
    }
}