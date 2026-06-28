package org.example.springai1.Repository;

import org.example.springai1.util.MessageVO;
import org.springframework.stereotype.Service;

import java.util.List;
@Service
public interface ChatHistoryReposity {
    void save(String type,String chatId);
    List<String> getChatIds(String type);

}
