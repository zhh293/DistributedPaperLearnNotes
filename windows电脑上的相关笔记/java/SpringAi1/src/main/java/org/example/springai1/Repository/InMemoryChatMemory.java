package org.example.springai1.Repository;

import org.example.springai1.util.MessageVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
@Service
public class InMemoryChatMemory  implements ChatHistoryReposity{
    private static  Map<String,List<String>> chatHistory=new HashMap<>();
    @Override
    public void save(String type, String chatId) {
       /* if(!chatHistory.containsKey( type)){
             chatHistory.put(type,List.of(chatId));
        }
        List<String>  chatIds=chatHistory.get(type);*/
        List<String> chatIds = chatHistory.computeIfAbsent(type, k -> new ArrayList<>());
        if (chatIds.contains(chatId)) {
            return;
        }
        chatIds.add(chatId);
        chatHistory.put(type, chatIds);
    }

    @Override
    public List<String> getChatIds(String type) {
        List<String> orDefault = chatHistory.getOrDefault(type, List.of());
        return orDefault;
    }


}
