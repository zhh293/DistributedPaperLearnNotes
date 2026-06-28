package org.example.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class McpServerApplicationTests {

    @Test
    void contextLoads() {
    }

}





/*
1. 服务端需暴露「能力端点」，返回 MCP 元数据
Spring AI 的 MCP 服务器需要通过一个端点（如 /capabilities），返回服务支持的工具、提示模板、交互规则等元数据。Postman 会通过这个端点 “了解” 服务能做什么（比如支持哪些工具调用、如何构造提示）。
你需要在 Spring AI 服务中：
定义工具（Tools）：比如天气查询工具，指定工具名、参数、描述。
配置提示（Prompts）：预设 AI 生成响应或调用工具的提示模板。
暴露/capabilities端点：让 Postman 能拉取这些元数据。
        2. 补充服务端的「能力配置」示例
以下是服务端需返回的capabilities JSON 结构示例（需匹配你的业务逻辑）：
json
{
    "capabilities": {
    "tools": [
    {
        "name": "weatherTool", // 工具名称
            "description": "查询指定城市的天气", // 工具描述（Postman会显示）
            "parameters": [ // 工具所需参数
        { "name": "city", "type": "string", "required": true, "description": "城市名称" }
        ]
    }
    ],
    "prompts": {
        "weatherPrompt": "用户需要查询{{city}}的天气，请调用weatherTool工具，参数city为{{city}}。"
    },
    "interactionMode": "streaming", // 交互模式：流式（匹配你接口的SSE/Flux）
            "protocol": "sse" // 通信协议：SSE（或websocket，需与接口一致）
}
}
3. 代码层面：在 Spring AI 中配置并暴露能力
需在 Spring Boot 项目中添加 MCP 能力配置代码，示例：
java
        运行
import org.springframework.ai.mcp.McpServer;
import org.springframework.ai.tool.Tool;
import org.springframework.ai.tool.ToolParameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class McpCapabilitiesController {

    private final McpServer mcpServer;

    public McpCapabilitiesController(McpServer mcpServer) {
        this.mcpServer = mcpServer;
        // 注册工具（示例：天气工具）
        mcpServer.registerTool(Tool.builder()
                .name("weatherTool")
                .description("查询指定城市的天气")
                .parameters(List.of(
                        ToolParameter.builder()
                                .name("city")
                                .type("string")
                                .required(true)
                                .description("城市名称")
                                .build()
                ))
                .build());
        // 注册提示模板（示例：天气提示）
        mcpServer.registerPrompt("weatherPrompt",
                "用户需要查询{{city}}的天气，请调用weatherTool工具，参数city为{{city}}。");
    }

    // 暴露能力端点，供Postman拉取
    @GetMapping("/capabilities")
    public Object getCapabilities() {
        return mcpServer.getCapabilities();
    }
}
4. Postman 中重新加载能力
完成服务端配置后：
在 Postman 的 MCP 请求界面，将 URL 指向能力端点（如 http://localhost:8080/capabilities）。
点击「Load Capabilities」，Postman 会拉取并显示服务支持的工具、提示等。
之后再通过/api/ai/chat接口交互时，Postman 就能基于这些能力元数据，正确构造请求、展示工具调用选项。*/







