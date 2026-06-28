package org.example.springai1.util;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

@Data
public class MessageVO {
    private String role;
    private String content;
    public MessageVO(Message  message) {
        switch (message.getMessageType()){
            case USER:
                this.role = "user";
                break;
            case ASSISTANT:
                this.role = "assistant";
                break;
                case SYSTEM:
                this.role = "system";
                break;
            case TOOL:
                this.role = "function";
                break;
            default:
                this.role = "";
                break;
        }
        this.content = message.getText();
    }
}
