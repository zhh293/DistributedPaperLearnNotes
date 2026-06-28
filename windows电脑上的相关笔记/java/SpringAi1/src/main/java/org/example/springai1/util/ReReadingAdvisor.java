package org.example.springai1.util;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;

/*
public class ReReadingAdvisor implements BaseAdvisor {

    @Override
    public AdvisedRequest before(AdvisedRequest request) {
        //todo 请求之前重写提示词
        String contents = request.toPrompt().getContents();
        PromptTemplate.builder()
                .template("{contents}")
                .build()
                .apply(contents);
        return null;
    }

    @Override
    public AdvisedResponse after(AdvisedResponse advisedResponse) {
        return null;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
*/
