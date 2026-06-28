package com.zhh.handsome.elkstudy.前后端实现高亮;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/")
public class HomeController {

    @Autowired
    private HighlightSearchService highlightSearchService;

    @GetMapping("/")
    public String home(Model model) {
        return "index"; // 返回templates下的index.html，如果没有则会查找static下的
    }

    @GetMapping("/test-highlight")
    public String testHighlight(Model model) {
        return "highlight-search";
    }
}