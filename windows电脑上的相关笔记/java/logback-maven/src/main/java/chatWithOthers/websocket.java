package chatWithOthers;

import org.springframework.stereotype.Component;

import javax.servlet.http.HttpSession;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ServerEndpoint("/chat")

public class websocket {
   /* 说明

    全双工(Full Duplex):允许数据在两个方向上同时传输。

    半双工(HàlfDuplex):允许数据在两个方向上传输，但是同一个时间段内只允许一个

    方向上传输。
*/
/*详解websocket*/
   public static final Map<String, Session> sessions = new ConcurrentHashMap<>();
   private HttpSession session;
    /*
    *
    *
    * 建立websocket连接后，被调用
    * @param session
    *
    *
    * */
    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
         //1.将session进行保存

     this.session=(HttpSession) config.getUserProperties().get(HttpSession.class.getName());
     String username=this.session.getAttribute("username").toString();
     sessions.put(username, session);
     //2.广播消息，需要将登陆的所有用户推送给所有的用户


//     broadcastAllUsers();

    }
    private void broadcastAllUsers(String message) throws IOException {
     Set<Map.Entry<String, Session>> entries =
             sessions.entrySet();
    for (Map.Entry<String, Session> entry : entries) {
     //获取到所有用户对应的session对象
     Session session = entry.getValue();
     //发送消息
     session.getBasicRemote().sendText(message);
    }

    }
    @OnMessage
    public void onMessage(String message, Session session) {

    }
    @OnClose
    public void onClose(Session session) {

    }







}
