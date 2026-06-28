package com.zhh.handsome.mongodborder.pojo;

import cn.hutool.extra.spring.SpringUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Logistics implements Serializable {
    private String orderId;
    private String operation;
    private String operator;
    @JsonFormat(shape = JsonFormat.Shape.STRING,pattern = "yyyy-mm-dd HH:mm:ss",timezone = "GMT+8")
    private LocalDateTime operateTime;
    private String address;
    private String details;
}
