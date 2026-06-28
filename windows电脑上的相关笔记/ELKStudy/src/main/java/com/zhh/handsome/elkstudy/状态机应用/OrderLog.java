package com.zhh.handsome.elkstudy.状态机应用;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单变更日志
 * Audit Log for order state changes.
 */
@Data
@Builder
public class OrderLog {
    private String logId;
    private String orderId;
    private OrderState oldState;
    private OrderState newState;
    private OrderEvent action;
    private String operator; // 操作人
    private LocalDateTime createTime;
    private String remark;
}
