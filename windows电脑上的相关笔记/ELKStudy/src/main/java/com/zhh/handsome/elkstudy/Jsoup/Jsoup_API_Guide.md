# Jsoup常用API和关键类总结

## 1. 概述

Jsoup是一个功能强大且易于使用的Java HTML解析库，它提供了一套类似于jQuery的API，可以通过DOM、CSS选择器以及类似于jQuery的操作方法来取出和操作数据。Jsoup能够直接解析某个URL地址、HTML文本内容，并提供了非常省力的API来处理HTML文档。

## 2. 核心依赖

```xml
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.17.2</version>
</dependency>
```

## 3. 关键类和接口

### 3.1 org.jsoup.Jsoup
这是Jsoup库的主要入口类，提供了静态方法用于解析HTML、连接URL等操作。

**主要方法：**
- `parse(String html)` - 从HTML字符串解析文档
- `parse(File in, String charsetName)` - 从文件解析文档
- `connect(String url)` - 连接到URL并获取Connection对象
- `parseBodyFragment(String bodyHtml)` - 解析HTML片段

### 3.2 org.jsoup.nodes.Document
表示一个完整的HTML文档，继承自Element类。它是整个HTML页面的根节点。

**主要方法：**
- `title()` - 获取文档标题
- `head()` - 获取head元素
- `body()` - 获取body元素
- `select(String cssQuery)` - 使用CSS选择器查找元素

### 3.3 org.jsoup.nodes.Element
表示一个HTML元素，如`<div>`、`<p>`、`<a>`等。

**主要方法：**
- `attr(String attributeKey)` - 获取属性值
- `attr(String attributeKey, String attributeValue)` - 设置属性值
- `text()` - 获取元素文本内容
- `html()` - 获取元素内部HTML
- `outerHtml()` - 获取元素及其内部HTML
- `tagName()` - 获取标签名
- `children()` - 获取子元素
- `parent()` - 获取父元素
- `select(String cssQuery)` - 使用CSS选择器查找元素
- `hasClass(String className)` - 检查是否包含指定CSS类
- `addClass(String className)` - 添加CSS类
- `removeClass(String className)` - 移除CSS类

### 3.4 org.jsoup.select.Elements
Element对象的集合，提供了对多个元素进行批量操作的方法。

**主要方法：**
- `size()` - 获取元素数量
- `get(int index)` - 获取指定位置的元素
- `first()` - 获取第一个元素
- `last()` - 获取最后一个元素
- `eachAttr(String attributeKey)` - 获取所有元素的指定属性值

### 3.5 org.jsoup.Connection
用于连接到URL并执行HTTP请求。

**主要方法：**
- `header(String name, String value)` - 设置请求头
- `cookie(String name, String value)` - 设置Cookie
- `timeout(int millis)` - 设置超时时间
- `data(String key, String value)` - 设置POST数据
- `get()` - 执行GET请求
- `post()` - 执行POST请求

## 4. CSS选择器语法

Jsoup支持强大的CSS选择器语法：

### 4.1 基本选择器
- `tag` - 选择指定标签的所有元素
- `#id` - 选择指定id的元素
- `.class` - 选择指定class的元素
- `*` - 选择所有元素

### 4.2 组合选择器
- `el#id` - 选择指定标签和id的元素
- `el.class` - 选择指定标签和class的元素
- `el[attr]` - 选择包含指定属性的元素
- `el[attr=value]` - 选择指定属性值的元素

### 4.3 层次选择器
- `el el` - 选择后代元素
- `el > el` - 选择子元素
- `el ~ el` - 选择兄弟元素
- `el + el` - 选择相邻元素

## 5. 常用API示例

### 5.1 解析HTML字符串
```java
String html = "<html><head><title>First parse</title></head>"
  + "<body><p>Parsed HTML into a doc.</p></body></html>";
Document doc = Jsoup.parse(html);
```

### 5.2 从URL解析
```java
Document doc = Jsoup.connect("http://example.com/")
  .userAgent("Mozilla")
  .timeout(5000)
  .get();
```

### 5.3 从文件解析
```java
File input = new File("path/to/file.html");
Document doc = Jsoup.parse(input, "UTF-8", "http://example.com/");
```

### 5.4 使用CSS选择器
```java
Elements links = doc.select("a[href]"); // 包含href属性的a标签
Elements pngs = doc.select("img[src$=.png]"); // src属性以.png结尾的图片
Element masthead = doc.selectFirst("div.masthead"); // 第一个div.masthead元素
```

### 5.5 操作元素
```java
Element link = doc.selectFirst("a");
String linkHref = link.attr("href"); // 获取href属性
String linkText = link.text(); // 获取文本内容
link.attr("rel", "nofollow"); // 设置属性
```

## 6. 高级功能

### 6.1 数据清理（XSS防护）
```java
String unsafe = "<p><a href='http://example.com/' onclick='steal()'>Link</a></p>";
String safe = Jsoup.clean(unsafe, Whitelist.basic());
```

### 6.2 修改HTML结构
```java
doc.select("div.sidebar").remove(); // 删除元素
doc.select("a").attr("target", "_blank"); // 修改属性
```

### 6.3 遍历文档
```java
for (Element link : doc.select("a")) {
    String linkHref = link.attr("href");
    String linkText = link.text();
}
```

## 7. 最佳实践

1. **安全考虑**：使用Whitelist清理用户输入的HTML，防止XSS攻击
2. **性能优化**：对于大量文档解析，复用Connection对象
3. **异常处理**：捕获IOException和其他可能的异常
4. **编码处理**：确保正确设置字符编码
5. **资源管理**：及时关闭连接和流

## 8. 常见用途

- 网页数据抓取和爬虫
- HTML内容清理和净化
- HTML模板处理
- 网站监控和检测
- 文档格式转换
- 安全过滤HTML内容