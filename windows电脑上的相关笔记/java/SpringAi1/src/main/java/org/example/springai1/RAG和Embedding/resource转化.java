package org.example.springai1.RAG和Embedding;

public class resource转化 {







/*
    在 Spring 框架中，Resource（Spring 核心资源抽象）和 MultipartFile（Spring Web 中处理文件上传的接口）虽然用途不同，但可以通过一定方式相互转换。以下是具体分析和实现方法：
    一、概念区分
    Resource：Spring 核心模块的资源抽象接口（org.springframework.core.io.Resource），用于统一访问各种资源（本地文件、类路径资源、网络资源等），提供 getInputStream()、getFilename() 等方法。
    MultipartFile：Spring Web 模块的接口（org.springframework.web.multipart.MultipartFile），用于处理 HTTP 上传的文件，包含文件内容、文件名、 contentType 等信息，提供 getInputStream()、transferTo(File) 等方法。
    二、相互转换实现
1. MultipartFile 转 Resource
    MultipartFile 转 Resource 较为直接，核心是通过 MultipartFile 的输入流或临时文件创建 Resource 实现类。
    常用方式：
    java
            运行
import org.springframework.core.io.Resource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;

    public class MultipartToResource {

        // 方式1：基于输入流创建 InputStreamResource（简单但需注意流关闭）
        public static Resource convertToInputStreamResource(MultipartFile multipartFile) throws IOException {
            return new InputStreamResource(multipartFile.getInputStream()) {
                // 重写 getFilename() 方法，否则默认返回 null
                @Override
                public String getFilename() {
                    return multipartFile.getOriginalFilename();
                }
            };
        }

        // 方式2：先保存为临时文件，再包装为 FileSystemResource（更稳定，适合大文件）
        public static Resource convertToFileSystemResource(MultipartFile multipartFile) throws IOException {
            // 创建临时文件
            File tempFile = File.createTempFile("multipart-", multipartFile.getOriginalFilename());
            // 将 MultipartFile 内容转存到临时文件
            multipartFile.transferTo(tempFile);
            // 包装为 FileSystemResource
            return new FileSystemResource(tempFile);
        }
    }
    注意事项：
    InputStreamResource 依赖于原始输入流，需确保流在使用期间不被关闭（Spring 通常会自动处理，但手动操作时需注意）。
    FileSystemResource 基于临时文件，需在使用后手动删除临时文件（可通过 File.deleteOnExit() 确保 JVM 退出时清理）。
            2. Resource 转 MultipartFile
    Resource 转 MultipartFile 需借助 MultipartFile 的实现类（Spring 未提供默认实现，通常使用 MockMultipartFile 或自定义实现）。
    常用方式（基于 MockMultipartFile）：MockMultipartFile 是 Spring Test 模块提供的 MultipartFile 实现，常用于测试，但也可在生产环境使用。
    java
            运行
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;

    public class ResourceToMultipart {

        public static MultipartFile convert(Resource resource) throws IOException {
            try (InputStream inputStream = resource.getInputStream()) {
                // 参数：字段名、文件名、contentType、输入流
                return new MockMultipartFile(
                        "file", // 表单字段名（可自定义）
                        resource.getFilename(), // 文件名
                        null, // contentType 可为 null，会自动推断
                        inputStream
                );
            }
        }
    }
    依赖说明：MockMultipartFile 位于 spring-test 依赖中，需引入：
    xml
            <dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-test</artifactId>
    <version>你的Spring版本</version>
    <!-- 生产环境无需 scope=test -->
            </dependency>*/


}
