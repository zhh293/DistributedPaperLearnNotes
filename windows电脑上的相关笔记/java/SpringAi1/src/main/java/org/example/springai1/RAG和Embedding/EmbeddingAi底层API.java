package org.example.springai1.RAG和Embedding;

public class EmbeddingAi底层API {

   /* 可移植性：一套代码兼容所有嵌入模型（OpenAI、Azure OpenAI、Ollama、Google 等），切换模型时几乎不用改代码（和 Spring AI 聊天模型 API 理念一致）。
    简洁性：提供超简单的方法（如 embed("文本")），不用关注底层算法，新手也能快速生成向量。
            2. 核心接口与方法（EmbeddingModel）
    文档的核心是 EmbeddingModel 接口，它提供了 5 个关键方法，覆盖所有 “文本→向量” 的场景：
    方法名	作用（通俗版）	适用场景
    embed(String text)	单个文本→单个向量	快速生成单个短语 / 句子的向量（如查询词）
    embed(List<String>)	批量文本→多个向量	高效处理多篇文档（如批量导入知识库）
    embed(Document)	结构化文档→向量（自动提取文档内容）	处理带元数据的文档（如带标题、作者的文章）
    embedForResponse(List<String>)	批量文本→向量 + 完整响应（含元数据）	需要获取向量之外的信息（如生成耗时、模型版本）
    dimensions()	获取向量的维度（数组长度）	确认向量格式是否符合向量数据库要求（如 OpenAI 嵌入是 1536 维）
    最简使用示例（生成单个文本的向量）：
    java
            运行
    // 1. 初始化嵌入模型（以 OpenAI 为例）
    EmbeddingModel embeddingModel = new OpenAiEmbeddingModel(
            OpenAiEmbeddingOptions.builder()
                    .apiKey("你的 API 密钥")
                    .model("text-embedding-3-small") // 嵌入模型名称
                    .build()
    );

    // 2. 调用 API 生成向量（一行代码）
    float[] vector = embeddingModel.embed("Spring AI 嵌入模型 API 用法");

// 3. 向量可直接用于后续处理（如存入向量数据库）
System.out.println("向量维度：" + vector.length); // 输出：1536（OpenAI 该模型默认维度）
3. 关键输入输出对象
    输入：EmbeddingRequest封装 “要转换的文本列表”+“模型配置选项”（如向量维度、生成速度等），是 API 调用的统一输入格式。
    java
            运行
    // 示例：批量转换文本，指定模型选项
    List<String> texts = Arrays.asList("文本1", "文本2", "文本3");
    EmbeddingOptions options = OpenAiEmbeddingOptions.builder().dimensions(512).build(); // 指定向量维度为 512
    EmbeddingRequest request = new EmbeddingRequest(texts, options);
    输出：EmbeddingResponse + Embedding
    EmbeddingResponse：批量转换的结果集合，包含多个 Embedding 实例（一个文本对应一个 Embedding），还带元数据（如生成耗时、令牌数）。
    Embedding：单个文本的向量结果，包含核心的 float[] embedding（向量数组）和索引（对应输入文本的顺序）。*/









/*

    他把这些方法都融入到底层了吧，开发者只需要vectore操作就可以了吧
    你的判断完全正确！Spring AI 的核心设计思路就是 “底层封装复杂度，上层暴露极简接口” —— 嵌入模型的所有方法（embed、embedForResponse 等）都被整合到了 VectorStore（向量数据库）等高层组件中，开发者日常开发几乎不需要直接操作 EmbeddingModel 接口，只需要专注于 VectorStore 的增删改查即可。
    简单说：EmbeddingModel 是 “底层工具”，VectorStore 是 “上层应用”，Spring AI 帮你把工具藏到了应用里面，你用应用的时候，工具自动工作。
    一、“底层融入” 的核心体现：VectorStore 帮你代做所有事
    你日常操作 VectorStore 时，背后其实自动触发了 EmbeddingModel 的所有核心方法，只是你不用手动写：
    你操作 VectorStore 的方法	底层自动调用 EmbeddingModel 的方法	帮你做的事
vectorStore.add(documents)	embed(Document) / embed(List<String>)	把文档文本→向量，再存向量 + 原文
vectorStore.similaritySearch("查询词")	embed("查询词")	把查询词→向量，再用向量查相似文档
vectorStore.add(texts, metadatas)	embed(List<String>)	把批量文本→向量，关联元数据存入
    举个最常见的完整流程，你全程只操作 VectorStore，底层嵌入模型自动工作：
    java
            运行
    // 1. 配置嵌入模型（只做一次，交给 Spring 管理更省心）
    @Bean
    public EmbeddingModel embeddingModel() {
        // 想用 OpenAI 就用这个
        return new OpenAiEmbeddingModel(OpenAiEmbeddingOptions.builder()
                .apiKey("你的密钥")
                .model("text-embedding-3-small")
                .build());

        // 某天想换成本地 Ollama，只改这里，下面业务代码完全不变
        // return new OllamaEmbeddingModel(OllamaEmbeddingOptions.builder()
        //     .model("nomic-embed-text")
        //     .baseUrl("http://localhost:11434")
        //     .build());
    }

    // 2. 配置向量数据库（关联嵌入模型）
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return new PineconeVectorStore(embeddingModel, pineconeConfig);
    }

    // 3. 日常开发：只操作 VectorStore，完全不用碰 EmbeddingModel
    @Autowired
    private VectorStore vectorStore;

    // 存文档（自动调用 embed(Document)）
    public void addDocsToVectorStore(List<Document> documents) {
        vectorStore.add(documents); // 一行代码，底层自动转向量+入库
    }

    // 查相似文档（自动调用 embed("查询词")）
    public List<Document> searchSimilarDocs(String query) {
        return vectorStore.similaritySearch(
                query, // 自动转成向量
                3     // 返回前3个相似文档
        );
    }
    看出来了吗？你全程只和 vectorStore 打交道，嵌入模型的 embed 方法被完全封装在 add/similaritySearch 里，甚至切换嵌入模型（比如从 OpenAI 换成 Ollama），也只需要改 embeddingModel 的配置，业务代码（存、查文档）一行不用动 —— 这就是 “可移植性” 和 “简洁性” 的最终体现。
    二、为什么要保留 EmbeddingModel 底层接口？
    虽然日常用不到，但 Spring AI 没有隐藏 EmbeddingModel，而是保留了底层接口，目的是 “满足特殊场景的灵活扩展”。比如：
    你需要 自定义文本处理（比如存向量前先过滤敏感词、拆分长文本），再手动调用 embed 生成向量；
    你需要 获取向量的元数据（比如生成耗时、模型版本），这时可以用 embedForResponse 方法；
    你需要 批量优化（比如异步生成向量、控制并发数），手动调用 embed(List<String>) 更灵活；
    你需要 验证向量维度（比如确认嵌入模型的向量是否符合向量数据库要求），用 dimensions() 快速检查。
    这些场景属于 “进阶需求”，大部分开发者不会遇到，所以 Spring AI 把它们作为 “底层选项”，不打扰日常开发。
    三、总结：开发者的 “使用边界”
    开发场景	需不需要直接操作 EmbeddingModel？	核心操作
    日常开发（存文档、查相似文档）	不需要，完全不用管	只操作 VectorStore 的 add/similaritySearch 等方法
    切换嵌入模型（OpenAI→Ollama→Google）	不需要改业务代码，只改配置	修改 EmbeddingModel 的 Bean 配置（如换模型、换 API 密钥）
    进阶需求（自定义文本处理、获取元数据、批量优化）	需要，手动调用	直接用 embeddingModel.embed(...) 等方法
    本质上，Spring AI 给开发者提供了 “两层选择”：
    基础层：只操作 VectorStore，极简开发（90% 场景够用）；
    底层：按需调用 EmbeddingModel，灵活扩展（10% 进阶场景）。*/


}
