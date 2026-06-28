package org.example.springai1.Conmtroller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
@Slf4j
public class RAGContrlooer {
    @Autowired
    private VectorStore vectorStore;
    @Qualifier("ollamaChatModel1")
    private ChatClient chatClient;
    @RequestMapping(value = "/rag",method = RequestMethod.GET)
    public String rag(String input) {
        String content = chatClient.prompt()
                .user(input)
                .advisors(new QuestionAnswerAdvisor(vectorStore))
                .call()
                .content();
        return content;
    }
}
