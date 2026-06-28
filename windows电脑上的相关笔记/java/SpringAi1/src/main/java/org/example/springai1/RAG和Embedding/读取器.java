package org.example.springai1.RAG和Embedding;

public class 读取器 {









/*

    Spring AI 提供了一系列文件读取器（File Reader），用于将不同格式的文件内容转换为统一的 Document 对象（包含文本内容和元数据），方便后续进行嵌入（Embedding）、向量存储或作为 AI 模型的输入。这些读取器是构建 RAG（检索增强生成）等应用的基础组件，支持多种常见文件格式。
    一、文件读取器的核心概念
    Spring AI 中所有文件读取器都遵循统一的设计：
    实现 ResourceReader 接口（核心接口，定义了从资源加载并转换为 Document 的方法）。
    输入为 Spring 的 Resource 对象（可表示本地文件、网络资源、类路径资源等）。
    输出为 List<Document>，每个 Document 包含 content（文本内容）和 metadata（元数据，如文件名、页码等）。


    */





   /* 二、常用文件读取器详解
1. 文本文件读取器（TextFileReader）
    作用：处理纯文本文件（如 .txt），直接读取文件内容。
    类名：org.springframework.ai.reader.TextFileReader
    依赖：无需额外依赖（Spring AI 核心模块已包含）
    使用示例：
    java
            运行
import org.springframework.ai.reader.TextFileReader;
import org.springframework.core.io.ClassPathResource;
import java.util.List;

    public class TextReaderExample {
        public static void main(String[] args) {
            // 加载类路径下的 text-file.txt
            ClassPathResource resource = new ClassPathResource("text-file.txt");

            // 创建文本文件读取器
            TextFileReader reader = new TextFileReader(resource);

            // 读取并转换为 Document 列表
            List<Document> documents = reader.read();

            // 输出内容
            for (Document doc : documents) {
                System.out.println("内容: " + doc.getContent());
                System.out.println("元数据: " + doc.getMetadata()); // 包含文件名等信息
            }
        }
    }
    特点：
    简单高效，直接读取文本内容，不处理格式。
    元数据包含 file_name、file_path 等基础信息。
    注意事项：
    仅支持纯文本，若文件包含特殊编码（如 GBK），需通过 TextFileReader 的构造函数指定编码：
    java
            运行
    TextFileReader reader = new TextFileReader(resource, "GBK"); // 指定编码
2. PDF 文件读取器（PdfReader）
    作用：处理 PDF 文件（.pdf），提取文本内容（支持分页提取）。
    类名：org.springframework.ai.reader.pdf.PdfReader
    依赖：需引入 PDF 处理库（底层基于 Apache PDFBox），Maven 依赖如下：
    xml
            <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pdf-document-reader</artifactId>
    <!-- 版本与 Spring AI 核心保持一致，如 0.8.0 -->
</dependency>
    使用示例：
    java
            运行
import org.springframework.ai.reader.pdf.PdfReader;
import org.springframework.core.io.FileSystemResource;
import java.util.List;

    public class PdfReaderExample {
        public static void main(String[] args) {
            // 加载本地 PDF 文件
            FileSystemResource resource = new FileSystemResource("document.pdf");

            // 创建 PDF 读取器（默认按页拆分）
            PdfReader reader = new PdfReader(resource);

            // 读取并转换为 Document 列表（每页一个 Document）
            List<Document> documents = reader.read();

            // 输出每页内容
            for (Document doc : documents) {
                System.out.println("页码: " + doc.getMetadata().get("page"));
                System.out.println("内容: " + doc.getContent());
            }
        }
    }
    高级配置：
    自定义分页策略：默认每页一个 Document，可通过 setPageExtractedTextFormatter 合并多页：
    java
            运行
// 合并前 3 页为一个 Document
reader.setPageExtractedTextFormatter((pageNumber, text) -> {
        if (pageNumber % 3 == 0) {
            return text + "\n--- 分页分隔符 ---";
        }
        return text;
    });
    忽略空白页：
    java
            运行
reader.setSkipBlankPages(true); // 跳过空白页
    特点：
    支持标准文本型 PDF（可复制的文本），不支持扫描件 PDF（需 OCR 处理，需额外集成 Tesseract 等工具）。
    元数据包含 page（页码）、file_name 等信息。
    注意事项：
    复杂格式 PDF（如多列、表格）可能导致文本提取错乱，需结合实际场景验证。
    大文件（如数百页）可能占用较多内存，建议分批处理。
            3. Markdown 文件读取器（MarkdownReader）
    作用：处理 Markdown 文件（.md），提取文本内容并保留基础结构（如标题、列表）。
    类名：org.springframework.ai.reader.markdown.MarkdownReader
    依赖：需引入 Markdown 处理库，Maven 依赖：
    xml
            <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-markdown-document-reader</artifactId>
</dependency>
    使用示例：
    java
            运行
import org.springframework.ai.reader.markdown.MarkdownReader;
import org.springframework.core.io.ClassPathResource;
import java.util.List;

    public class MarkdownReaderExample {
        public static void main(String[] args) {
            ClassPathResource resource = new ClassPathResource("guide.md");
            MarkdownReader reader = new MarkdownReader(resource);

            List<Document> documents = reader.read();
            for (Document doc : documents) {
                System.out.println("Markdown 内容: " + doc.getContent());
                // 元数据包含标题层级等信息
                System.out.println("标题层级: " + doc.getMetadata().get("headingLevel"));
            }
        }
    }
    特点：
    会解析 Markdown 语法（如 # 标题、- 列表），提取文本时保留结构逻辑（如标题会标注层级）。
    适合处理技术文档、博客文章等 Markdown 格式内容。
            4. CSV 文件读取器（CsvReader）
    作用：处理 CSV 文件（.csv），将表格数据转换为结构化文本。
    类名：org.springframework.ai.reader.csv.CsvReader
    依赖：需引入 CSV 处理库，Maven 依赖：
    xml
            <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-csv-document-reader</artifactId>
</dependency>
    使用示例：
    java
            运行
import org.springframework.ai.reader.csv.CsvReader;
import org.springframework.core.io.FileSystemResource;
import java.util.List;

    public class CsvReaderExample {
        public static void main(String[] args) {
            FileSystemResource resource = new FileSystemResource("data.csv");
            // 配置 CSV 读取器（指定分隔符、是否包含表头）
            CsvReader reader = new CsvReader(resource)
                    .setDelimiter(',') // 分隔符（默认逗号）
                    .setHasHeader(true); // 第一行为表头

            List<Document> documents = reader.read();
            for (Document doc : documents) {
                // 内容为"列名: 值"的结构化文本
                System.out.println("CSV 行内容: " + doc.getContent());
            }
        }
    }
    特点：
    将每行数据转换为 "列名：值" 的键值对文本，方便 AI 模型理解表格内容。
    支持自定义分隔符（如 ;）、编码、是否忽略空行等。
            5. Word 文档读取器（DocxReader）
    作用：处理 Word 文档（.docx，不支持旧版 .doc），提取文本内容。
    类名：org.springframework.ai.reader.word.DocxReader
    依赖：需引入 DOCX 处理库（基于 Apache POI），Maven 依赖：
    xml
            <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-word-document-reader</artifactId>
</dependency>
    使用示例：
    java
            运行
import org.springframework.ai.reader.word.DocxReader;
import org.springframework.core.io.ClassPathResource;
import java.util.List;

    public class DocxReaderExample {
        public static void main(String[] args) {
            ClassPathResource resource = new ClassPathResource("report.docx");
            DocxReader reader = new DocxReader(resource);

            List<Document> documents = reader.read();
            for (Document doc : documents) {
                System.out.println("Word 内容: " + doc.getContent());
                // 元数据包含段落编号、标题等信息
            }
        }
    }
    特点：
    支持提取段落、标题、列表等内容，忽略复杂格式（如图片、图表，需额外处理）。
    适合处理报告、文档等 .docx 格式文件。
    注意事项：
    不支持 .doc 格式（旧版 Word），需先转换为 .docx。
    三、通用配置与扩展
    元数据自定义：所有读取器都可通过 setMetadataCustomizer 自定义元数据：
    java
            运行
reader.setMetadataCustomizer(metadata -> {
        metadata.put("source", "内部文档");
        metadata.put("category", "技术手册");
    });
    大文件处理：对于超大文件（如 1GB+），可结合 ChunkingDocumentReader 进行分片处理：
    java
            运行
    // 将文档按 1000 字符分片
    ChunkingDocumentReader chunkingReader = new ChunkingDocumentReader(
            new PdfReader(resource),
            new TokenTextSplitter(1000, 0) // 按字符数分片
    );
    List<Document> chunks = chunkingReader.read(); // 分片后的文档列表
    自定义文件读取器：若需支持特殊格式（如 .epub、.ppt），可实现 ResourceReader 接口：
    java
            运行
    public class EpubReader implements ResourceReader {
        private final Resource resource;

        public EpubReader(Resource resource) {
            this.resource = resource;
        }

        @Override
        public List<Document> read() {
            // 自定义 EPUB 解析逻辑，返回 Document 列表
            List<Document> documents = new ArrayList<>();
            // ... 解析代码 ...
            return documents;
        }
    }
    四、总结
    Spring AI 的文件读取器覆盖了主流文件格式，核心价值在于将不同格式的文件转换为统一的 Document 对象，简化后续 AI 处理流程。选择读取器时需注意：
    文本 / Markdown：优先使用对应读取器，保留结构。
    PDF：区分文本型和扫描型（扫描型需额外 OCR）。
    表格 / CSV：使用 CsvReader 转换为结构化文本。
    办公文档：.docx 用 DocxReader，.doc 需先转换。
    实际使用中，建议结合分片器（如 TokenTextSplitter）处理长文档，并通过元数据自定义增强检索能力。*/
}
