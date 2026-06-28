package com.zhh.handsome.elkstudy.状态机应用;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 业务监听器
 * Handles business logic after state changes.
 */
@Component
public class BusinessListeners {

    @EventListener
    public void handlePaymentSuccess(OrderStateChangedEvent event) {
        if (event.getNewState() == OrderState.PAID) {
            System.out.println("【Listener】Payment success for Order " + event.getOrderId() 
                    + ". Sending SMS to user...");
            System.out.println("【Listener】Deducting stock for Order " + event.getOrderId() + "...");
        }
    }

    @EventListener
    public void handleShipping(OrderStateChangedEvent event) {
        if (event.getNewState() == OrderState.SHIPPED) {
            System.out.println("【Listener】Order " + event.getOrderId() 
                    + " shipped. Notifying logistics partner...");
        }
    }
}
