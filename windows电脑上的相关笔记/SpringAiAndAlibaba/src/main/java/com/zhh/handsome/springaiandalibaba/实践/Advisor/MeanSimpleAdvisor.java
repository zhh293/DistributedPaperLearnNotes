package com.zhh.handsome.springaiandalibaba.实践.Advisor;

import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.stereotype.Component;


@Component
public class MeanSimpleAdvisor implements CallAroundAdvisor {
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        // 步骤1：修改请求（给用户文本加前缀）
        String newUserText = "请用简洁的语言回答：" + advisedRequest.userText();
        AdvisedRequest newRequest = AdvisedRequest.from(advisedRequest)
                .userText(newUserText)
                .build();

        // 步骤2：继续执行下一个 Advisor（必须调，否则阻断）
        AdvisedResponse response = chain.nextAroundCall(newRequest);

        // 步骤3：可选：修改响应（比如过滤敏感词）
        // String newContent = response.getResult().getOutput().getContent().replace("敏感词", "***");
        // AdvisedResponse newResponse = AdvisedResponse.from(response)...

        // 步骤4：返回响应
        return response;
    }

    @Override
    public String getName() {
        return "自定义拦截器";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
