package com.zhh.handsome.elkstudy.状态机应用;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单仓储层 (Mock实现)
 * Normally this would extend JpaRepository or MyBatis Mapper.
 * Here we use a ConcurrentHashMap to simulate database operations.
 */
@Repository
public class OrderRepository {

    // 模拟数据库存储
    private final Map<String, Order> orderStore = new ConcurrentHashMap<>();

    public void insert(Order order) {
        orderStore.put(order.getOrderId(), order);
        System.out.println("【DB Log】Inserted new order: " + order.getOrderId());
    }

    /**
     * 带乐观锁的更新
     * UPDATE order SET state = ?, version = version + 1 WHERE id = ? AND version =
     * ?
     */
    public boolean updateStateWithVersion(String orderId, OrderState newState, Integer currentVersion) {
        return orderStore.computeIfPresent(orderId, (key, existingOrder) -> {
            if (existingOrder.getVersion().equals(currentVersion)) {
                System.out.println("【DB Log】Optimistic Lock Check Passed. Updating version "
                        + currentVersion + " -> " + (currentVersion + 1));
                existingOrder.setState(newState);
                existingOrder.setVersion(currentVersion + 1);
                return existingOrder;
            } else {
                System.err.println("【DB Log】Optimistic Lock Failed! Expected version "
                        + currentVersion + " but got " + existingOrder.getVersion());
                return existingOrder; // Do not update
            }
        }).getVersion().equals(currentVersion + 1);
    }

    /**
     * 根据ID查找订单
     * 
     * @param orderId 订单ID
     * @return 订单对象
     */
    public Order findById(String orderId) {
        System.out.println("【DB Log】Querying order: " + orderId);
        return orderStore.get(orderId);
    }
}
