package chatWithOthers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
@SpringBootApplication
@RestController
public class SSE {



    public static void main(String[] args) {
        SpringApplication.run(SSE.class, args);
    }
    /*SSE(server-sentevent):服务器发送事件

            SSE在服务器和客户端之间打开一个单向通道

    服务端响应的不再是一次性的数据包，而是text/event-stream类型的数据流信息

            服务器有数据变更时将数据流式传输到客户端*/
/*1. SSE 基础原理
    SSE 是一种基于 HTTP 长连接的技术，借助这种技术，服务器能够在客户端建立连接之后，主动向客户端推送数据。
    客户端只需通过 JavaScript 的EventSource接口就可以接收这些实时数据。
    和 WebSocket 有所不同的是，SSE 是单向通信，仅支持服务器向客户端发送数据。*/


    @PostMapping("/publisher/{id}")
    public void publisher(@RequestParam String message,@PathVariable("id") String id) {
        // 获取 SSEEmitter 对象
        SseEmitter subscribe = subscribe(id);
        if(Objects.isNull(subscribe)){
            return;
        }
        try {
            // 发送数据
            subscribe.send(message);
        } catch (Exception e) {
            // 如果发送失败，从集合中移除
            e.printStackTrace();
        }

    }

            private static final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
       @PostMapping("/subscribe/{id}")
       public SseEmitter subscribe(@PathVariable("id") String id) {


           SseEmitter sseEmitter = emitters.get(id);

           if (Objects.isNull(sseEmitter)){
               sseEmitter = new SseEmitter();
               // 添加回调
               sseEmitter.onCompletion(() -> {
                   emitters.remove(id);
                   System.out.println("onCompletion");
               });
               sseEmitter.onTimeout(() -> {
                   emitters.remove(id);
                   System.out.println("onTimeout");
               });
               emitters.put(id, sseEmitter);
           }


           return sseEmitter;
       }



}
