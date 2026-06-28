package org.example.springai1.RAG和Embedding;

public class 向量数据库 {


/*
    所有的向量库都遵循着几个基本的操作规范，比如增删改查等。Spring AI 通过 VectorStore 接口来抽象这些操作，方便我们对接不同的向量数据库实现。

    public interface VectorStore extends DocumentWriter
    {
        default String getName() {
            return this.getClass().getSimpleName();
        }
    }
    void add(List<Document> documents);

    void delete(List<String> idList);

    void delete(Filter.Expression filterExpression);

    default void delete(String filterExpression) { ... };

    List<Document> similaritySearch(String query);

    List<Document> similaritySearch(SearchRequest request);

    default <T> Optional<T> getNativeClient() {
        return Optional.empty();
    }
        */


    //哈哈啊哈哈哈

    /// 777777777777777777777，8888888888888888888，9999999999999







   /* 要理解向量数据库和它在 Spring AI 中的用法，我们可以从 “生活化类比” 切入，再逐步过渡到技术细节和实际操作 —— 全程避开复杂公式，只讲 “是什么、为什么用、怎么用”。
    一、先搞懂：什么是向量数据库？
            1. 第一步：理解 “向量”—— 给文字 / 图片 / 声音贴 “数字标签”
    你可以把 “向量” 理解成描述事物 “语义特征” 的数字列表。比如：
    描述 “咖啡”：用 [苦的程度，热的程度，颜色深浅，含咖啡因与否] 这 4 个特征，转化成数字就是 [0.8, 0.9, 0.7, 0.95]（数字越接近 1，特征越明显）；
    描述 “奶茶”：特征列表相同，数字就是 [0.3, 0.6, 0.5, 0.1]（没那么苦，没咖啡那么热，咖啡因少）；
    描述 “如何缓解头痛”：文字的语义特征转化成数字，可能是 [0.1, 0.8, 0.7, 0.6, 0.9]（这组数字代表 “健康问题→缓解方法” 的语义）。
    简单说：向量 = 把 “不可量化的语义 / 特征” 变成 “可计算的数字”，这个过程叫 “嵌入（Embedding）”，靠 “嵌入模型”（如 OpenAI 的 text-embedding-3-small）完成。
            2. 第二步：向量数据库 —— 存 “数字标签”+ 快速找 “相似的”
    向量数据库本质是专门存储 “向量（数字列表）” 的数据库，但核心能力不是 “存”，而是 “快速找相似”。
    举个生活化例子：你手机里有 1000 张照片，想找 “和你上周拍的海边日落最像的 10 张”—— 传统数据库做不到（因为照片是像素，没法直接比 “像不像”），但向量数据库可以：
    先把每张照片转成向量（描述 “日落的橙红色调、海平面角度、云朵形状” 的数字列表）；
    当你要找相似照片时，把 “上周日落照片” 也转成向量；
    向量数据库 1 秒内算出 “这张向量” 和库里 1000 个向量的 “相似度”（数字越近，照片越像），返回最像的 10 张。


            3. 向量数据库 vs 传统数据库（MySQL/Redis）：核心差异
    对比维度	传统数据库（MySQL）	向量数据库（Pinecone/Weaviate）
    存什么	文本、数字、日期（如 “头痛缓解方法”）	向量（如 [0    .1, 0.8, 0.7, 0.6, 0.9]）
    怎么查	关键词匹配（如 “LIKE '% 头痛 %'”）	语义相似匹配（找 “语义接近” 的向量）
    适合场景	查 “精确内容”（如 “用户 ID=123 的订单”）	查 “相似内容”（如 “和‘头痛’语义近的回答”）*/




    //使用方法
    /*@Configuration
    public class RagConfig {

        @Value("${app.chunk.size:500}")
        private int chunkSize;

        @Value("${app.chunk.overlap:50}")
        private int chunkOverlap;

        *//**
         * 文本分割器配置
         *//*
        @Bean
        public TextSplitter textSplitter() {
            return new TokenTextSplitter(chunkSize, chunkOverlap);
        }

        *//**
         * RAG提示模板配置
         *//*
        @Bean
        public PromptTemplate ragPromptTemplate() {
            String template = """
            你是一个知识问答专家，请基于以下提供的参考文档内容回答用户的问题。
            请确保你的回答完全基于提供的文档信息，不要编造内容。
            如果文档中没有相关信息，请明确说明无法回答该问题。

            参考文档:
            {documents}

            用户问题:
            {question}

            回答:
            """;
            return new PromptTemplate(template);
        }
    }*/







/*
    @RestController
    @RequestMapping("/api/knowledge")
    @RequiredArgsConstructor
    public class KnowledgeBaseController {

        private final DocumentProcessingService documentProcessingService;
        private final VectorStoreService vectorStoreService;
        private final RagService ragService;

        */
/**
         * 上传文档到知识库
         *//*

        @PostMapping("/documents")
        public ResponseEntity<ApiResponse<String>> uploadDocument(@ModelAttribute DocumentRequest request) {
            try {
                // 处理文档并分割
                List<Document> documents = documentProcessingService.processDocument(
                        request.getFile(),
                        request.getName(),
                        request.getType(),
                        request.getDescription()
                );

                // 存储到向量数据库
                vectorStoreService.addDocuments(documents);

                return ResponseEntity.ok(ApiResponse.success(
                        "文档上传成功，已添加 " + documents.size() + " 个片段到知识库"));
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.fail("文档上传失败: " + e.getMessage()));
            }
        }

        */
/**
         * 清空知识库
         *//*

        @DeleteMapping("/documents")
        public ResponseEntity<ApiResponse<String>> clearKnowledgeBase() {
            try {
                vectorStoreService.clearAllDocuments();
                return ResponseEntity.ok(ApiResponse.success("知识库已清空"));
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.fail("清空知识库失败: " + e.getMessage()));
            }
        }

        */
/**
         * 基于知识库回答问题
         *//*

        @PostMapping("/query")
        public ResponseEntity<ApiResponse<String>> queryKnowledgeBase(@RequestBody QueryRequest request) {
            try {
                String answer;
                if (request.getTopK() != null && request.getTopK() > 0) {
                    answer = ragService.answerWithRag(request.getQuestion(), request.getTopK());
                } else {
                    answer = ragService.answerWithRag(request.getQuestion());
                }
                return ResponseEntity.ok(ApiResponse.success(answer));
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.fail("查询失败: " + e.getMessage()));
            }
        }

        */
/**
         * 基于特定类型的文档回答问题
         *//*

        @PostMapping("/query/type/{documentType}")
        public ResponseEntity<ApiResponse<String>> queryByDocumentType(
                @PathVariable String documentType,
                @RequestBody QueryRequest request) {
            try {
                String answer = ragService.answerWithRagAndFilter(request.getQuestion(), documentType);
                return ResponseEntity.ok(ApiResponse.success(answer));
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.fail("查询失败: " + e.getMessage()));
            }
        }
    }
*/





/*

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "操作成功", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}




    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public class DocumentRequest {

        @NotBlank(message = "文档名称不能为空")
        private String name;

        @NotBlank(message = "文档类型不能为空")
        private String type;

        @NotNull(message = "文档文件不能为空")
        private MultipartFile file;

        private String description;
    }




    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public class QueryRequest {

        @NotBlank(message = "查询内容不能为空")
        private String question;

        private Integer topK;
    }
*/


}
