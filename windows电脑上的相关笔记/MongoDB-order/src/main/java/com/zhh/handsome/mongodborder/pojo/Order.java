package com.zhh.handsome.mongodborder.pojo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "order")
public class Order implements Serializable {
    private String id;
    private String status;
    @JsonFormat(shape = JsonFormat.Shape.STRING,pattern = "yyyy-mm-dd HH:mm:ss",timezone = "GMT+8")
    private LocalDateTime orderTime;
    private String shipper;
    private String shipperAddress;
    private String shipperPhone;
    @JsonFormat(shape = JsonFormat.Shape.STRING,pattern = "yyyy-mm-dd HH:mm:ss",timezone = "GMT+8")
    private LocalDateTime shipTime;
    private String receiver;
    private String receiverAddress;
    private String receiverPhone;
    private List<Logistics> logistics;
}
