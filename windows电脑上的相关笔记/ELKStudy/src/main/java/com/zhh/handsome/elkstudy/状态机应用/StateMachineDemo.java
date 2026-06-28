package com.zhh.handsome.elkstudy.状态机应用;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 状态机演示入口
 * Demonstration of the Order State Machine.
 */
public class StateMachineDemo {

    // Simple Mock for EventPublisher to simulate Spring behavior in main()
    static class MockEventPublisher implements ApplicationEventPublisher {
        private final BusinessListeners businessListeners;

        public MockEventPublisher(BusinessListeners businessListeners) {
            this.businessListeners = businessListeners;
        }

        @Override
        public void publishEvent(Object event) {
            if (event instanceof OrderStateChangedEvent) {
                OrderStateChangedEvent e = (OrderStateChangedEvent) event;
                // Manually route to listeners
                businessListeners.handlePaymentSuccess(e);
                businessListeners.handleShipping(e);
            }
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            publishEvent((Object) event);
        }
    }

    public static void main(String[] args) {
        // 1. 初始化组件 (Simulating Spring Dependency Injection)
        OrderRepository orderRepository = new OrderRepository();
        OrderLogRepository orderLogRepository = new OrderLogRepository();
        OrderStateMachine stateMachine = new OrderStateMachine();
        stateMachine.init();

        // Mock Listeners & Publisher
        BusinessListeners businessListeners = new BusinessListeners();
        ApplicationEventPublisher eventPublisher = new MockEventPublisher(businessListeners);

        OrderService orderService = new OrderService(
                orderRepository,
                stateMachine,
                orderLogRepository,
                eventPublisher);

        System.out.println("====== Enterprise Order State Machine Demo (Enhanced) ======");

        // 2. 正常流程演示 (With Logs & Listeners)
        System.out.println("\n--- Scenario 1: Happy Path with Listeners & Logs ---");
        try {
            Order order = orderService.createOrder("user_001", new BigDecimal("99.99"));
            String orderId = order.getOrderId();

            orderService.pay(orderId); // Should trigger Payment Listener
            orderService.ship(orderId); // Should trigger Shipping Listener
            orderService.complete(orderId);

            System.out.println("Final State: " + orderRepository.findById(orderId).getState());
            System.out.println("Final Version: " + orderRepository.findById(orderId).getVersion());

            System.out.println("Audit Logs for Order " + orderId + ":");
            orderLogRepository.findByOrderId(orderId)
                    .forEach(log -> System.out.println("  - " + log.getOldState() + " -> " + log.getNewState()
                            + " via " + log.getAction() + " at " + log.getCreateTime()));

        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3. 乐观锁并发演示
        System.out.println("\n--- Scenario 2: Optimistic Locking (Concurrency) ---");
        try {
            Order order = orderService.createOrder("user_002", new BigDecimal("299.00"));
            String orderId = order.getOrderId();

            // Simulate concurrent thread modifying the version behind the scenes
            System.out.println(">> Simulating concurrent modification...");
            Order concurrentOrder = orderRepository.findById(orderId);
            concurrentOrder.setVersion(999); // Manually tamper version

            System.out.println(">> Attempting to pay...");
            orderService.pay(orderId); // Should fail

        } catch (RuntimeException e) {
            System.err.println("Expected Concurrency Error: " + e.getMessage());
        }
    }
}
