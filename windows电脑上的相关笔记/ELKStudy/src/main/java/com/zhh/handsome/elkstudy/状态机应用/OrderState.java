package com.zhh.handsome.elkstudy.状态机应用;

/**
 * 订单状态枚举
 * Enterprise-level state definition
 */
public enum OrderState {
    /**
     * 待支付 (初始状态)
     */
    CREATED,

    /**
     * 已支付
     */
    PAID,

    /**
     * 已发货
     */
    SHIPPED,

    /**
     * 已完成
     */
    COMPLETED,

    /**
     * 已取消
     */
    CANCELLED;
}
