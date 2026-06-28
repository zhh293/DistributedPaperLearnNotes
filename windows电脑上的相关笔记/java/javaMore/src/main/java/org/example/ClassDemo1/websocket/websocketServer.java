package org.example.ClassDemo1.websocket;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ServerEndpoint("/ws/{sid}")
public class websocketServer {
    private static Map<String, Session> sessionMap = new HashMap<>();
    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        sessionMap.put(session.getId(), session);
    }
    @OnMessage
    public void onMessage(String message,@PathParam("sid") String sid) {
       System.out.println(message);
    }
    @OnClose
    public void onClose(Session session, @PathParam("sid") String sid) {
        sessionMap.remove(sid);
    }
    public void sendToAll(String message) {
        Collection<Session> sessions = sessionMap.values();
        for (Session session : sessions) {
            try{
                session.getAsyncRemote().sendText(message);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }


}
