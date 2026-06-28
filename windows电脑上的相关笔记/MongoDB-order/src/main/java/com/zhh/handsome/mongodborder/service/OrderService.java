package com.zhh.handsome.mongodborder.service;

import cn.hutool.core.lang.generator.SnowflakeGenerator;
import cn.hutool.core.util.IdUtil;
import com.zhh.handsome.mongodborder.pojo.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class OrderService {
    @Autowired
    private MongoTemplate mongoTemplate;


    public void addOrder(Order order) {
        //订单编号根据雪花算法生成
        String s = IdUtil.getSnowflake(1, 1).nextIdStr();
        order.setId(s);
        //设置订单状态
        order.setStatus("已下单");
        //设置下单时间
        order.setOrderTime(LocalDateTime.now());
        order.setShipTime(LocalDateTime.now());
        //设置发货时间
        //添加订单到mongodb
        mongoTemplate.insert(order, "order");
        Order order1 = new Order();
        Order order2 = new Order();
        Order order3 = new Order();
        List<Order> list = Arrays.asList(order, order1, order2, order3);
        mongoTemplate.insertAll(list);

        mongoTemplate.findById(order.getId(), Order.class);


        Query id = Query.query(Criteria.where("id").is(order.getId()));
        mongoTemplate.find(id, Order.class);

        
    }
}
