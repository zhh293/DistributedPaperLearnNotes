package com.zhh.handsome.elkstudy.状态机应用;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 订单状态变更事件
 * Decouples state changes from business actions.
 */
@Getter
public class OrderStateChangedEvent extends ApplicationEvent {
    private final String orderId;
    private final OrderState oldState;
    private final OrderState newState;

    public OrderStateChangedEvent(Object source, String orderId, OrderState oldState, OrderState newState) {
        super(source);
        this.orderId = orderId;
        this.oldState = oldState;
        this.newState = newState;
    }
}
