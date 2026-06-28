package com.zhh.handsome.elkstudy.状态机应用;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 订单状态机核心逻辑
 * Manages state transitions and validations.
 */
@Component
public class OrderStateMachine {

    /**
     * 状态流转表: CurrentState -> Event -> NextState
     */
    private final Map<OrderState, Map<OrderEvent, OrderState>> transitionTable = new HashMap<>();

    @PostConstruct
    public void init() {
        // Initialize valid transitions
        
        // CREATED -> PAY -> PAID
        addTransition(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID);
        // CREATED -> CANCEL -> CANCELLED
        addTransition(OrderState.CREATED, OrderEvent.CANCEL, OrderState.CANCELLED);

        // PAID -> SHIP -> SHIPPED
        addTransition(OrderState.PAID, OrderEvent.SHIP, OrderState.SHIPPED);
        // PAID -> CANCEL -> CANCELLED (Refund scenario)
        addTransition(OrderState.PAID, OrderEvent.CANCEL, OrderState.CANCELLED);

        // SHIPPED -> COMPLETE -> COMPLETED
        addTransition(OrderState.SHIPPED, OrderEvent.COMPLETE, OrderState.COMPLETED);
        
        // SHIPPED -> CANCEL -> Rejected (or specialized logic, usually can't cancel after ship without return)
        // For this demo, we assume strict forward flow, so no cancel after ship.
    }

    private void addTransition(OrderState from, OrderEvent event, OrderState to) {
        transitionTable.computeIfAbsent(from, k -> new HashMap<>()).put(event, to);
    }

    /**
     * 获取下一个状态
     * @param currentState 当前状态
     * @param event 触发事件
     * @return 下一个状态，如果不可流转则返回 empty
     */
    public Optional<OrderState> getNextState(OrderState currentState, OrderEvent event) {
        Map<OrderEvent, OrderState> allowedEvents = transitionTable.get(currentState);
        if (allowedEvents == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(allowedEvents.get(event));
    }
}
