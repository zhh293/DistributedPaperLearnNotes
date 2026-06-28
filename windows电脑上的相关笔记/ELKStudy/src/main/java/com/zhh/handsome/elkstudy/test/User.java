package com.zhh.handsome.elkstudy.test;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class User {
    private String name;
    private int age;
    private String sex;
    private String[] tags;
}
