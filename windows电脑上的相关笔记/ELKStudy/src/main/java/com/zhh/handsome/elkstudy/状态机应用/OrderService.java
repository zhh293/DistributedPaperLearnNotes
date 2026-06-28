package com.zhh.handsome.elkstudy.状态机应用;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 订单服务 (Refactored)
 * Integrates Optimistic Locking, Audit Logging, and Event Publishing.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStateMachine orderStateMachine;
    private final OrderLogRepository orderLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 创建订单
     */
    public Order createOrder(String userId, BigDecimal amount) {
        Order order = Order.builder()
                .orderId(UUID.randomUUID().toString())
                .userId(userId)
                .amount(amount)
                .state(OrderState.CREATED) // Initial State
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        log.info("Creating Order: {}", order);
        orderRepository.insert(order);
        // Log creation
        logStateChange(order, null, OrderState.CREATED, null);
        return order;
    }

    public Order pay(String orderId) {
        return handleEvent(orderId, OrderEvent.PAY);
    }

    public Order ship(String orderId) {
        return handleEvent(orderId, OrderEvent.SHIP);
    }

    public Order complete(String orderId) {
        return handleEvent(orderId, OrderEvent.COMPLETE);
    }

    public Order cancel(String orderId) {
        return handleEvent(orderId, OrderEvent.CANCEL);
    }

    /**
     * 统一处理状态流转
     */
    private Order handleEvent(String orderId, OrderEvent event) {
        // 1. 获取订单
        Order order = orderRepository.findById(orderId);
        if (order == null) {
            throw new RuntimeException("Order not found: " + orderId);
        }

        // 2. 检查状态流转是否合法
        OrderState currentState = order.getState();
        OrderState nextState = orderStateMachine.getNextState(currentState, event)
                .orElseThrow(() -> new RuntimeException(
                        String.format("Invalid transition: Cannot trigger %s from %s", event, currentState)));

        // 3. 乐观锁更新 (Attempt to update with CAS)
        // In a real DB, this is one SQL. Here we simulate it.
        boolean success = orderRepository.updateStateWithVersion(orderId, nextState, order.getVersion());

        if (!success) {
            throw new RuntimeException(
                    "Concurrency Error: Order state has been changed by another transaction. Please retry.");
        }

        // 4. 记录变更日志 (Audit Log)
        logStateChange(order, currentState, nextState, event);

        // 5. 发送事件 (Decouple business logic)
        // Now that the state is committed, notify listeners
        eventPublisher.publishEvent(new OrderStateChangedEvent(this, orderId, currentState, nextState));

        // Return updated order (fetched fresh or manually updated object)
        return orderRepository.findById(orderId);
    }

    private void logStateChange(Order order, OrderState oldState, OrderState newState, OrderEvent event) {
        OrderLog log = OrderLog.builder()
                .logId(UUID.randomUUID().toString())
                .orderId(order.getOrderId())
                .oldState(oldState)
                .newState(newState)
                .action(event)
                .operator("SYSTEM") // In real app, get from SecurityContext
                .createTime(LocalDateTime.now())
                .build();
        orderLogRepository.save(log);
    }
}
