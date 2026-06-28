package org.example.springai1.Conmtroller;

import lombok.extern.slf4j.Slf4j;
import org.example.springai1.Repository.ChatHistoryReposity;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
@Slf4j
public class ServicceController {
    @Qualifier("serviceChatClient")
    @Autowired
    private ChatClient serviceChatClient;

    @Autowired
    private ChatHistoryReposity chatHistoryReposity;
    @GetMapping(value = "/service", produces = "text/html;charset=UTF-8")
    public String service(String  prompt,String chatId) {
        chatHistoryReposity.save("service",chatId);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        log.info("用户名：{}",authentication.getName());
        log.info("用户权限：{}",authentication.getAuthorities());
        return serviceChatClient.prompt()
                .user(prompt)
                .advisors(a->{
                    a.param(AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId);
                })
                .call()
                .content();
    }
}




/*


SecurityContextHolder.getContext().getAuthentication() 中保存的用户认证信息（Authentication 对象）的生命周期，取决于 SecurityContext 的存储机制 和 应用的会话管理策略，主要分为以下两种场景：
        1. 单个请求处理过程中（ThreadLocal 级别）
SecurityContextHolder 默认使用 MODE_THREADLOCAL 模式存储 SecurityContext（包含 Authentication），即 SecurityContext 是当前线程的局部变量。

生命周期：仅在当前请求的处理线程中有效。
当请求进入时，SecurityContextPersistenceFilter 会从 HttpSession 中加载 SecurityContext 并放入 ThreadLocal（供当前线程的所有组件访问，包括控制器、服务层等）。
当请求处理完成（响应返回后），SecurityContextPersistenceFilter 会将 SecurityContext 从 ThreadLocal 中移除（避免线程复用导致的信息泄露），并将其写回 HttpSession（供后续请求使用）。
        2. 跨请求的会话级别（HttpSession 级别）
跨请求时，SecurityContext（包含 Authentication）会被持久化到 HttpSession 中（由 SecurityContextPersistenceFilter 负责同步），因此其生命周期与 HttpSession 一致。

生命周期：从用户登录成功开始，到以下任一事件发生时结束：
用户主动登出：调用 SecurityContextLogoutHandler 会清除 HttpSession 中的 SecurityContext，并使 Authentication 失效。
会话过期：超过 server.servlet.session.timeout 配置的时间（默认 30 分钟，可自定义），HttpSession 被销毁，SecurityContext 随之消失。
会话被强制销毁：例如服务器重启、调用 session.invalidate() 方法等。
特殊场景：无状态认证（如 JWT）
如果应用使用 无状态认证（如 JWT 令牌），通常会配置 sessionCreationPolicy(SessionCreationPolicy.STATELESS)，此时：

不会创建 HttpSession，SecurityContext 不会被持久化。
每次请求都需要通过令牌重新构建 SecurityContext 和 Authentication，因此 Authentication 的生命周期 仅在当前请求处理线程中有效（请求结束后即被清除）。
总结
单个请求内：Authentication 随当前线程的 ThreadLocal 存在，请求结束后被清除（但会同步到 HttpSession 供后续请求使用）。
跨请求会话内：Authentication 随 HttpSession 存在，直到会话过期、用户登出或会话被销毁。
无状态模式：Authentication 仅在当前请求处理过程中存在，请求结束后即失效。





停止服务之后，springsecurity会将httpsession自动销毁，之后再次启动时即便cookie里面有sessionId，也获取不到对应的用户信息了对吧
是的，你的理解是正确的。在默认情况下（未配置 session 持久化的前提下），停止服务后再次启动，即使客户端 cookie 中仍保留旧的 sessionId，也无法获取到对应的用户信息，原因如下：
1. 默认情况下，HttpSession 存储在服务器内存中
Spring Security 依赖的 HttpSession 默认是 内存级别的会话存储（由 Servlet 容器管理，如 Tomcat）。当服务停止时：

服务器进程终止，内存中的所有数据（包括所有 HttpSession 对象及其存储的 SecurityContext、用户信息等）会被彻底释放。
此时，sessionId 对应的会话数据已经物理消失。
2. 重启服务后，旧 sessionId 无法匹配有效会话
客户端 cookie 中保存的 sessionId 只是一个 “标识”，其本身不包含任何用户数据。当服务重启后：

服务器接收请求时，会根据 cookie 中的 sessionId 查找对应的 HttpSession，但由于旧会话已随服务停止而销毁，服务器会判定该 sessionId 无效。
此时服务器会创建一个 新的空白 HttpSession（分配新的 sessionId），原有的用户认证信息（Authentication）自然无法恢复，用户需要重新登录。
特殊情况：若配置了 session 持久化
如果通过配置将 HttpSession 持久化到外部存储（如 Redis、数据库等，常见于分布式系统），则可能出现不同结果：

服务停止时，HttpSession 数据会被持久化到外部存储中。
重启服务后，若配置正确，服务器可以通过 cookie 中的 sessionId 从外部存储加载回原会话数据，用户可能无需重新登录。

但这是 额外配置 的结果（如使用 Spring Session + Redis），并非 Spring Security 的默认行为。
结论
在默认的内存会话存储模式下：

服务停止 → 所有 HttpSession 销毁 → 重启后，旧 sessionId 失效 → 无法获取用户信息，需重新登录。
这是 Web 应用的常规行为，目的是保证会话安全性（避免旧会话标识被滥用）。
*/




