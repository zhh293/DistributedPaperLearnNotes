package com.zhh.handsome.elkstudy.Jsoup;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;

/**
 * Jsoup使用示例Demo
 * 演示Jsoup的核心功能和常用API
 */
public class Demo {
    public static void main(String[] args) {
        try {
            // 1. 从HTML字符串解析文档
            demonstrateParsingFromString();
            
            // 2. 使用CSS选择器
            demonstrateCssSelectors();
            
            // 3. 从URL解析（注释掉以避免网络请求）
            // demonstrateParsingFromUrl();
            
            // 4. 操作元素
            demonstrateElementManipulation();
            
            // 5. 数据提取示例
            demonstrateDataExtraction();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 演示从HTML字符串解析文档
     */
    public static void demonstrateParsingFromString() {
        System.out.println("=== 1. 从HTML字符串解析 ===");
        
        String html = "<!DOCTYPE html>" +
                "<html>" +
                "<head><title>Jsoup示例页面</title></head>" +
                "<body>" +
                "<h1 id='main-title' class='title'>欢迎使用Jsoup</h1>" +
                "<div class='content'>" +
                "<p>这是一个示例段落。</p>" +
                "<a href='https://example.com' class='link external'>外部链接</a>" +
                "<a href='/internal' class='link internal'>内部链接</a>" +
                "<ul>" +
                "<li class='item'>项目1</li>" +
                "<li class='item'>项目2</li>" +
                "<li class='item'>项目3</li>" +
                "</ul>" +
                "</div>" +
                "<img src='image.jpg' alt='示例图片' width='200' height='100'>" +
                "</body>" +
                "</html>";

        Document doc = Jsoup.parse(html);
        
        // 获取文档标题
        System.out.println("文档标题: " + doc.title());
        
        // 获取h1元素
        Element title = doc.getElementById("main-title");
        System.out.println("标题内容: " + title.text());
        System.out.println("标题ID: " + title.id());
        System.out.println("标题Class: " + title.className());
        
        System.out.println();
    }

    /**
     * 演示CSS选择器的使用
     */
    public static void demonstrateCssSelectors() {
        System.out.println("=== 2. CSS选择器演示 ===");
        
        String html = "<html><body>" +
                "<div class='container'>" +
                "<h2 class='heading'>标题1</h2>" +
                "<p class='content'>段落1</p>" +
                "<p class='content special'>特殊段落</p>" +
                "<a href='https://example1.com' class='link'>链接1</a>" +
                "<a href='https://example2.com' class='link'>链接2</a>" +
                "<input type='text' name='username' value='admin'>" +
                "<input type='password' name='password' value='secret'>" +
                "</div>" +
                "</body></html>";

        Document doc = Jsoup.parse(html);

        // 选择所有class为'content'的p标签
        Elements contentParagraphs = doc.select("p.content");
        System.out.println("内容段落数量: " + contentParagraphs.size());
        for (Element p : contentParagraphs) {
            System.out.println("  - " + p.text());
        }

        // 选择所有链接
        Elements links = doc.select("a[href]");
        System.out.println("链接数量: " + links.size());
        for (Element link : links) {
            System.out.println("  - " + link.attr("href") + " (" + link.text() + ")");
        }

        // 选择所有input元素
        Elements inputs = doc.select("input");
        System.out.println("输入框数量: " + inputs.size());
        for (Element input : inputs) {
            System.out.println("  - 类型: " + input.attr("type") + ", 名称: " + input.attr("name") + ", 值: " + input.attr("value"));
        }

        // 选择特殊类的元素
        Elements specialElements = doc.select(".special");
        System.out.println("特殊元素: " + (specialElements.isEmpty() ? "无" : specialElements.first().text()));

        System.out.println();
    }

    /**
     * 演示元素操作
     */
    public static void demonstrateElementManipulation() {
        System.out.println("=== 3. 元素操作演示 ===");
        
        String html = "<html><body>" +
                "<div id='content' class='main'>" +
                "<h1>原始标题</h1>" +
                "<p>原始段落内容</p>" +
                "</div>" +
                "</body></html>";

        Document doc = Jsoup.parse(html);
        Element div = doc.getElementById("content");
        
        // 修改元素内容
        Element heading = div.selectFirst("h1");
        heading.text("修改后的标题");
        
        // 添加CSS类
        div.addClass("updated");
        
        // 设置属性
        div.attr("data-updated", "true");
        
        // 添加新元素
        Element newPara = doc.createElement("p");
        newPara.text("新增的段落");
        div.appendChild(newPara);
        
        System.out.println("修改后的HTML:");
        System.out.println(doc.html());
        
        System.out.println();
    }

    /**
     * 演示数据提取
     */
    public static void demonstrateDataExtraction() {
        System.out.println("=== 4. 数据提取演示 ===");
        
        String html = "<html><body>" +
                "<article class='post'>" +
                "<header>" +
                "<h1 class='title'>文章标题</h1>" +
                "<span class='author'>作者：张三</span>" +
                "<time class='date' datetime='2023-12-01'>2023-12-01</time>" +
                "</header>" +
                "<div class='content'>" +
                "<p>这是文章的第一段内容。</p>" +
                "<p>这是文章的第二段内容。</p>" +
                "<ul class='tags'>" +
                "<li class='tag'>标签1</li>" +
                "<li class='tag'>标签2</li>" +
                "<li class='tag'>标签3</li>" +
                "</ul>" +
                "</div>" +
                "<footer>" +
                "<span class='views'>浏览量：1234</span>" +
                "</footer>" +
                "</article>" +
                "</body></html>";

        Document doc = Jsoup.parse(html);
        
        // 提取文章信息
        String title = doc.selectFirst("h1.title").text();
        String author = doc.selectFirst("span.author").text();
        String date = doc.selectFirst("time.date").text();
        String views = doc.selectFirst("span.views").text();
        
        System.out.println("文章标题: " + title);
        System.out.println("作者: " + author);
        System.out.println("日期: " + date);
        System.out.println("浏览量: " + views);
        
        // 提取所有段落
        Elements paragraphs = doc.select("div.content > p");
        System.out.println("段落数量: " + paragraphs.size());
        for (int i = 0; i < paragraphs.size(); i++) {
            System.out.println("  段落" + (i+1) + ": " + paragraphs.get(i).text());
        }
        
        // 提取所有标签
        Elements tags = doc.select("ul.tags > li.tag");
        System.out.println("标签: " + tags.stream()
                .map(Element::text)
                .reduce((a, b) -> a + ", " + b).orElse("无"));
        
        System.out.println();
    }

    /**
     * 演示从URL解析（需要网络连接）
     */
    public static void demonstrateParsingFromUrl() {
        System.out.println("=== 5. 从URL解析演示 ===");
        try {
            // 这里使用一个示例URL，实际使用时请替换为有效的URL
            Document doc = Jsoup.connect("https://httpbin.org/html")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(5000)
                    .get();
            
            System.out.println("页面标题: " + doc.title());
            System.out.println("前三个段落:");
            Elements paragraphs = doc.select("p");
            for (int i = 0; i < Math.min(3, paragraphs.size()); i++) {
                System.out.println("  " + paragraphs.get(i).text());
            }
        } catch (IOException e) {
            System.out.println("无法连接到URL: " + e.getMessage());
        }
        
        System.out.println();
    }
}