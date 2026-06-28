package org.example.redisdemo.Configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

public class WebSocketConfiguration {
    public static final String SOCKET_URL = "ws://localhost:8080/websocket";
    @Bean
    @ConditionalOnWebApplication
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
