package com.zhh.handsome.elkstudy.前后端实现高亮;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户实体类（与集成SpringBoot包中的类相同）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class User {
    @JsonProperty("name")
    private String name;

    @JsonProperty("age")
    private Integer age;

    @JsonProperty("sex")
    private String sex;

    @JsonProperty("tags")
    private String[] tags;
}