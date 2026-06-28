package com.zhh.handsome.springaiandalibaba.实践.Advisor;

import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;


@Component

public class Example implements StreamAroundAdvisor {

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        String s = advisedRequest.userText();
        System.out.println("拦截器开始执行");
        Flux<AdvisedResponse> advisedResponseFlux = chain.nextAroundStream(advisedRequest);
        advisedResponseFlux.subscribe(advisedResponse -> {
            System.out.println("拦截器结束执行");
        });
        return advisedResponseFlux;
    }

    @Override
    public String getName() {
        return "自定义流式拦截器";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
