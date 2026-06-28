package com.zhh.handsome.mongodborder.controller;

import com.zhh.handsome.mongodborder.pojo.Order;
import com.zhh.handsome.mongodborder.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("order")
public class OrderController {
    @Autowired
    private OrderService orderService;

    @PostMapping("/add")
    public String addOrder(@RequestBody Order order) {
        orderService.addOrder(order);
        return "success";
    }
}
