package com.zhh.handsome.elkstudy.状态机应用;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体类
 * Represents the Order entity in the system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    /**
     * 订单ID
     */
    private String orderId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 当前状态
     */
    private OrderState state;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 版本号 (乐观锁)
     */
    @Builder.Default
    private Integer version = 0;
}
