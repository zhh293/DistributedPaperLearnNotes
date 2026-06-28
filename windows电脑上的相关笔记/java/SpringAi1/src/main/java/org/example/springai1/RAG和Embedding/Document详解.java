package org.example.springai1.RAG和Embedding;

public class Document详解 {
    /*Document 类在 Spring AI 中主要用于表示一段文本及其相关的元数据，常用于向量数据库、检索增强生成（RAG）等场景。它是文本分割、存储、检索等流程的核心数据结构。
    作用
    封装文本内容：存储需要处理或检索的原始文本。
    携带元数据：如文档ID、来源、标签等，便于后续检索和过滤。
    与向量数据库交互：作为写入、检索、删除等操作的基本单元。
    典型API与字段说明
（以 org.springframework.ai.document.Document 为例）

    public class Document implements Serializable {
        // 文本内容
        private final String content;
        // 元数据，通常是键值对
        private final Map<String, Object> metadata;

        // 构造方法
        public Document(String content) { ... }
        public Document(String content, Map<String, Object> metadata) { ... }

        // 获取文本内容
        public String getContent() { ... }

        // 获取元数据
        public Map<String, Object> getMetadata() { ... }

        // 获取指定key的元数据
        public Object getMetadata(String key) { ... }

        // 设置元数据（返回新对象，Document通常是不可变的）
        public Document withMetadata(String key, Object value) { ... }

        // equals、hashCode、toString等
    }
    content：文档的主体内容，通常是一段文本。
    metadata：附加信息，如 id、source、author、timestamp 等，便于文档的管理和检索。
    不可变设计：大多数实现是不可变的，修改元数据会返回新对象，保证线程安全。
    常用场景：
    文本分割后生成多个 Document 实例，批量写入向量数据库。
    检索时返回 Document 列表，供后续处理或生成答案。
    示例
    Document doc = new Document("这是文档内容", Map.of("id", "123", "source", "file.txt"));
    String text = doc.getContent(); // 获取内容
    Object id = doc.getMetadata("id"); // 获取元数据
    Document newDoc = doc.withMetadata("author", "张三"); // 添加新元数据
    Document 是 Spring AI 处理文本和元数据的基础载体，贯穿文本分割、存储、检索、生成等各个环节*/






    /*是不是想要把源文件切分并且转成document需要其他类和依赖
    是的，想要将源文件切分并转成 Document，通常需要以下内容：
    文本切分器类：如 TextSplitter，用于将大文本分割成小块。
    Spring AI 依赖：需要引入 spring-ai-core 或相关依赖，才能使用 Document 类。
    工具类：如文件读取工具，将文件内容读取为字符串。
    Map 工具：用于构建元数据（metadata）。
    常见依赖（pom.xml）示例：
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-core</artifactId>
    <version>0.8.0</version>
</dependency>
    常见流程：
    读取文件内容为字符串。
    用切分器分割为多段文本。
    每段文本生成一个 Document 实例，并附带元数据。
    这些步骤都需要额外的类和依赖支持。

    还有PagePdfDocumentReader类来读取Pdf文件转换成Document*/

}

