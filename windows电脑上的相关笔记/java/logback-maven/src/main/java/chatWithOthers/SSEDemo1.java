package chatWithOthers;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/sse")
public class SSEDemo1 {
    /*
    * text/event-stream 媒体格式
    * 基于http的服务器单工通信技术，多应用于通知、日志、监控等场景
    * 服务器向客户端推送消息的通道
    * 工作原理
    * 客户端先订阅SSE推送消息的事件流
    * 要求返回的类型为text/event-stream
    * 服务器向客户端推送消息
    *
    *
    * */
    private static Map<Long, SseEmitter> clients = new ConcurrentHashMap<>();
    @GetMapping(value = "/demo1",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter demo1(){
        //SSE是服务单向通道，默认连接超时时间30秒，可以设置超时时间为0，表示不限制超时时间
        SseEmitter sseEmitter = new SseEmitter(0L);
        clients.put(Thread.currentThread().getId(), sseEmitter);
        //当通信完成时，移除
        sseEmitter.onCompletion(() -> clients.remove(Thread.currentThread().getId()));
        //发生异常处理
        sseEmitter.onError(throwable -> clients.remove(Thread.currentThread().getId()));
        return sseEmitter;
    }
    @GetMapping("/send")
    public void send(@RequestParam String message,@RequestParam Long id){

    }
}
