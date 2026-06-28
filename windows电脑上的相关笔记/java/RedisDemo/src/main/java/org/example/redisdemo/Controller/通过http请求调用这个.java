package org.example.redisdemo.Controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Slf4j
public class 通过http请求调用这个 {
    /*@Autowired
    private 实时语音识别 语音识别;
    @Autowired
    private APIWebsocket websocket;
    @RequestMapping(value = "/call",method = RequestMethod.GET)
    public String call() throws Exception {
        new Thread(() -> {
            try {
                websocket.connect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        return "success";
    }*/
}
