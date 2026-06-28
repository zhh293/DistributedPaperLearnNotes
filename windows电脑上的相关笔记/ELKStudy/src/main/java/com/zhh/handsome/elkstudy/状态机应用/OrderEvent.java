package com.zhh.handsome.elkstudy.状态机应用;

/**
 * 订单事件枚举
 * Trigger events for state transitions
 */
public enum OrderEvent {
    /**
     * 支付
     */
    PAY,
    
    /**
     * 发货
     */
    SHIP,
    
    /**
     * 确认收货/完成
     */
    COMPLETE,
    
    /**
     * 取消
     */
    CANCEL;
}
