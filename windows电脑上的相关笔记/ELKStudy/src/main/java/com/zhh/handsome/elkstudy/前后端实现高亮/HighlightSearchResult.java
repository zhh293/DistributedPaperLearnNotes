package com.zhh.handsome.elkstudy.前后端实现高亮;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 高亮搜索结果响应类
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HighlightSearchResult {
    @JsonProperty("user")
    private User user;

    @JsonProperty("highlights")
    private Map<String, Object> highlights;

    @JsonProperty("score")
    private Double score;
}