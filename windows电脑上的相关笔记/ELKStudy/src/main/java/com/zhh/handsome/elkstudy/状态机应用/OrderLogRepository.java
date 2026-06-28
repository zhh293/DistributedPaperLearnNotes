package com.zhh.handsome.elkstudy.状态机应用;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单日志仓储
 */
@Repository
public class OrderLogRepository {
    
    // In-memory storage for logs
    private final List<OrderLog> logStore = new CopyOnWriteArrayList<>();

    public void save(OrderLog log) {
        logStore.add(log);
        System.out.println("【Audit Log】Saved log: Order[" + log.getOrderId() + "] " 
                + log.getOldState() + " -> " + log.getNewState() + " by " + log.getAction());
    }

    public List<OrderLog> findByOrderId(String orderId) {
        List<OrderLog> result = new ArrayList<>();
        for (OrderLog log : logStore) {
            if (log.getOrderId().equals(orderId)) {
                result.add(log);
            }
        }
        return result;
    }
}
