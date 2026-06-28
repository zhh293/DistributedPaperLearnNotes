package chatWithOthers;

import org.springframework.web.socket.server.standard.ServerEndpointExporter;

import javax.servlet.http.HttpSession;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;

public class GetHttpSessionConfigurator  extends ServerEndpointConfig.Configurator {
    public void modifyHandShake(ServerEndpointConfig serverEndpointConfig, HandshakeRequest request, HandshakeResponse response){
         HttpSession httpSession = (HttpSession) request.getHttpSession();
         //将httpsession对象保存起来
         serverEndpointConfig.getUserProperties().put(HttpSession.class.getName(), httpSession);
    }

}
