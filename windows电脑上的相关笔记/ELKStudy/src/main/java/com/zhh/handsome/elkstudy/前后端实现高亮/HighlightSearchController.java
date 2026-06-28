package com.zhh.handsome.elkstudy.前后端实现高亮;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/es/highlight")
public class HighlightSearchController {

    @Autowired
    private HighlightSearchService highlightSearchService;

    /**
     * 带高亮的全文搜索
     */
    @GetMapping("/search/match/{indexName}/{fieldName}/{value}")
    public List<HighlightSearchResult> highlightMatchSearch(
            @PathVariable String indexName,
            @PathVariable String fieldName,
            @PathVariable String value,
            @RequestParam(required = false, defaultValue = "") String highlightFields) {
        try {
            List<String> fieldsToHighlight = parseHighlightFields(highlightFields, fieldName);
            return highlightSearchService.highlightMatchSearch(indexName, fieldName, value, fieldsToHighlight);
        } catch (IOException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * 带高亮的精确搜索
     */
    @GetMapping("/search/term/{indexName}/{fieldName}/{value}")
    public List<HighlightSearchResult> highlightTermSearch(
            @PathVariable String indexName,
            @PathVariable String fieldName,
            @PathVariable String value,
            @RequestParam(required = false, defaultValue = "") String highlightFields) {
        try {
            List<String> fieldsToHighlight = parseHighlightFields(highlightFields, fieldName);
            return highlightSearchService.highlightTermSearch(indexName, fieldName, value, fieldsToHighlight);
        } catch (IOException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * 带高亮的多字段搜索
     */
    @GetMapping("/search/multi/{indexName}/{value}")
    public List<HighlightSearchResult> highlightMultiMatchSearch(
            @PathVariable String indexName,
            @PathVariable String value,
            @RequestParam String searchFields, // 搜索字段，用逗号分隔
            @RequestParam(required = false, defaultValue = "") String highlightFields) { // 高亮字段，用逗号分隔
        try {
            List<String> searchFieldList = Arrays.asList(searchFields.split(","));
            List<String> highlightFieldList = parseHighlightFields(highlightFields, searchFieldList.toArray(new String[0]));
            return highlightSearchService.highlightMultiMatchSearch(indexName, searchFieldList, value, highlightFieldList);
        } catch (IOException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * 带高亮的分页搜索
     */
    @GetMapping("/search/paginated/{indexName}")
    public List<HighlightSearchResult> highlightPaginatedSearch(
            @PathVariable String indexName,
            @RequestParam(defaultValue = "*") String query,
            @RequestParam(required = false) String searchFields, // 搜索字段，用逗号分隔
            @RequestParam(required = false, defaultValue = "") String highlightFields, // 高亮字段，用逗号分隔
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            List<String> searchFieldList = Arrays.asList();
            if (searchFields != null && !searchFields.isEmpty()) {
                searchFieldList = Arrays.asList(searchFields.split(","));
            }
            
            List<String> highlightFieldList = parseHighlightFields(highlightFields, searchFieldList.toArray(new String[0]));
            
            return highlightSearchService.highlightPaginatedSearch(indexName, query, searchFieldList, highlightFieldList, page, size);
        } catch (IOException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * 解析高亮字段列表
     */
    private List<String> parseHighlightFields(String highlightFields, String... defaultFields) {
        if (highlightFields != null && !highlightFields.trim().isEmpty()) {
            return Arrays.asList(highlightFields.split(","));
        } else {
            if (defaultFields.length > 0) {
                return Arrays.asList(defaultFields);
            } else {
                // 如果没有默认字段，返回所有可能的字段
                return Arrays.asList("name", "sex", "tags");
            }
        }
    }
}