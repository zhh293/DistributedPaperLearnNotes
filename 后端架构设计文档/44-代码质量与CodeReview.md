# 代码质量与 Code Review 架构设计

## 一、问题背景

### 1.1 代码质量的战略价值

代码质量是软件工程的基石。在大型互联网平台中，代码质量直接影响系统的稳定性、可维护性和团队协作效率。低质量的代码带来的代价是指数级增长的：

- **线上故障频发**：代码缺陷导致的生产事故占比超过 60%
- **维护成本激增**：修复一个生产环境 Bug 的成本是开发阶段的 10-100 倍
- **技术债务累积**：短期"快速交付"导致长期架构腐化
- **团队效率下降**：新人上手困难，代码可读性差导致沟通成本高

### 1.2 代码质量的多维度挑战

```
┌─────────────────────────────────────────────────────────────┐
│                   代码质量挑战全景                              │
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ 规范层面      │  │ 工程层面      │  │ 组织层面             │ │
│  │              │  │              │  │                     │ │
│  │ - 编码规范不统一│  │ - 测试覆盖率低 │  │ - CR流于形式         │ │
│  │ - 命名不规范   │  │ - 架构设计缺陷 │  │ - 缺乏认证体系       │ │
│  │ - 异常处理不当  │  │ - 性能隐患    │  │ - 新人指导不足       │ │
│  │ - 安全漏洞     │  │ - 依赖管理混乱 │  │ - 规范执行力弱       │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ AI 辅助层面   │  │ 度量层面      │  │ 工具层面             │ │
│  │              │  │              │  │                     │ │
│  │ - 代码幻觉    │  │ - 缺少量化指标 │  │ - 静态扫描规则过时    │ │
│  │ - 技术债引入   │  │ - 主观评价多   │  │ - 工具链不完整       │ │
│  │ - 知识库质量差  │  │ - 反馈闭环缺失 │  │ - 规则与业务脱节     │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 行业现状

当前代码质量管理面临几个突出矛盾：

1. **效率与质量的矛盾**：业务快速迭代要求快速交付，但质量保障需要时间
2. **规范与创新的矛盾**：过度严格的规范可能扼杀创新，过度宽松则质量失控
3. **人工与自动化的矛盾**：人工 CR 深度好但效率低，自动化工具覆盖广但深度不足
4. **AI 辅助的新挑战**：AI 生成代码带来效率提升的同时，也引入了新的质量风险

---

## 二、整体架构设计

### 2.1 代码质量保障体系全景

```
┌─────────────────────────────────────────────────────────────────┐
│                    代码质量保障体系                                │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  编码阶段 (Shift Left)                    │   │
│  │  IDE 实时检查 │ Lint │ 编码规范 │ AI 辅助编码              │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  提交阶段 (Pre-commit)                    │   │
│  │  Git Hooks │ 本地静态检查 │ 格式化 │ 单测                  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  审核阶段 (Code Review)                    │   │
│  │  人工 CR │ AI CR │ 认证 Reviewer │ 质量门禁               │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  集成阶段 (CI Pipeline)                    │   │
│  │  静态扫描 │ 单测 │ 集成测试 │ 安全扫描 │ 覆盖率检查         │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  运行阶段 (Production)                     │   │
│  │  线上监控 │ 错误追踪 │ 性能分析 │ 故障复盘                  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  反馈闭环 (Feedback Loop)                  │   │
│  │  规则迭代 │ 规范更新 │ 知识沉淀 │ 培训体系                  │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 AI Code Review 系统架构

AI Code Review 系统由四个协同工作的 Agent 组成，以规则库（Rules Database）为中心枢纽：

```
┌─────────────────────────────────────────────────────────────────┐
│                    AI Code Review 系统架构                        │
│                                                                 │
│  ┌──────────────────┐          ┌──────────────────┐            │
│  │  Agent 1:         │          │  Agent 2:         │            │
│  │  代码库提取 Agent   │          │  PR评论提取 Agent  │            │
│  │                   │          │                   │            │
│  │ 扫描仓库源码       │          │ 挖掘历史PR评论     │            │
│  │ 按维度提取模式      │          │ 筛选高价值评论     │            │
│  │ ripgrep频率验证    │          │ 生成规则建议       │            │
│  │ 上限30条规则       │          │ 批量处理30条/批    │            │
│  └────────┬─────────┘          └────────┬─────────┘            │
│           │                              │                      │
│           └──────────┬───────────────────┘                      │
│                      ▼                                          │
│           ┌──────────────────────┐                              │
│           │   Rules Database     │                              │
│           │   (规则库 - 中心枢纽)  │                              │
│           │                      │                              │
│           │  所有4个Agent共享      │                              │
│           │  通过反馈闭环持续演进   │                              │
│           └──────────┬───────────┘                              │
│                      │                                          │
│           ┌──────────┼──────────┐                               │
│           ▼                     ▼                               │
│  ┌──────────────────┐  ┌──────────────────┐                    │
│  │  Agent 3:         │  │  Agent 4:         │                    │
│  │  代码审查 Agent    │  │  反馈审查 Agent    │                    │
│  │                   │  │                   │                    │
│  │ 双Scanner架构:    │  │ 意图分析:          │                    │
│  │  A=Bug+安全       │  │  反驳/接受/疑问    │                    │
│  │  B=规则匹配       │  │                   │                    │
│  │ + Merger + Verifier│ │ >=3条类似反驳      │                    │
│  │ ~10min/PR         │  │  -> 触发规则修订    │                    │
│  │ 支持增量Push审查   │  │                   │                    │
│  └──────────────────┘  │ 修复检测:          │                    │
│                        │  从后续Push diff    │                    │
│                        │  判断是否已修复     │                    │
│                        └──────────────────┘                    │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 Code Review 组织体系

```
┌─────────────────────────────────────────────────────────────────┐
│                 Code Review 组织体系                              │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    标准委员会                              │   │
│  │  制定编码规范 │ 更新规则集 │ 年度规范迭代                    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  CR 委员会 (< 5人)                        │   │
│  │  审核 CR 质量 │ 抽检 │ AI 辅助评估 │ 培训指导               │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │          认证 Reviewer (占开发者 20%-30%)                  │   │
│  │  通过编码规范考试 + CR规范考试 + 历史CR记录                   │   │
│  │  负责审核PR │ 有合入权限 │ 定期参与培训                      │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              普通开发者 (Developer)                        │   │
│  │  自测后提PR │ 接受认证Reviewer审核 │ 响应Review评论          │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 三、核心链路设计

### 3.1 Agent 1: 代码库提取 Agent

代码库提取 Agent 负责扫描仓库源码，按多个维度发现隐式编码模式，提炼为规则。

```java
/**
 * Agent 1: 代码库提取 Agent
 * 
 * 功能: 扫描仓库源码,按维度发现隐式编码模式
 * 维度: 网络调用/组件使用/异常处理/日志规范/安全实践 等
 * 
 * 核心流程:
 * 1. 扫描源码,提取代码模式
 * 2. 使用 ripgrep 验证模式出现频率
 * 3. 高频模式提炼为规则 (上限30条)
 */
public class CodebaseExtractionAgent {

    private final PatternExtractor patternExtractor;
    private final FrequencyValidator frequencyValidator;
    private final RulesDatabase rulesDatabase;

    /**
     * 代码扫描维度定义
     */
    public enum ScanDimension {
        NETWORK("网络调用", "HTTP客户端配置、超时设置、重试策略"),
        COMPONENT("组件使用", "Spring Bean使用、缓存操作、消息队列"),
        ERROR_HANDLING("异常处理", "异常捕获、错误码、降级策略"),
        LOGGING("日志规范", "日志级别、结构化日志、敏感信息"),
        SECURITY("安全实践", "SQL注入防护、XSS防护、权限校验"),
        CONCURRENCY("并发编程", "线程池使用、锁机制、原子操作"),
        DATABASE("数据库操作", "SQL规范、事务管理、连接池"),
        API_DESIGN("API设计", "接口命名、参数校验、返回值规范");

        private final String name;
        private final String description;

        ScanDimension(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    /**
     * 执行代码库扫描
     */
    public List<CodePattern> scanCodebase(String repoPath) {
        log.info("开始代码库扫描: repo={}", repoPath);

        List<CodePattern> allPatterns = new ArrayList<>();

        // 按每个维度扫描
        for (ScanDimension dimension : ScanDimension.values()) {
            List<CodePattern> dimensionPatterns = scanByDimension(repoPath, dimension);
            allPatterns.addAll(dimensionPatterns);
            log.info("维度 {} 发现 {} 个模式", dimension.name, dimensionPatterns.size());
        }

        // 使用 ripgrep 验证频率
        List<CodePattern> validatedPatterns = validateFrequency(repoPath, allPatterns);

        // 筛选高频模式,上限30条
        List<CodePattern> topPatterns = validatedPatterns.stream()
            .sorted(Comparator.comparingInt(CodePattern::getFrequency).reversed())
            .limit(30)
            .collect(Collectors.toList());

        log.info("代码库扫描完成: 总模式={}, 验证通过={}, 最终规则={}",
            allPatterns.size(), validatedPatterns.size(), topPatterns.size());

        return topPatterns;
    }

    /**
     * 按维度扫描代码模式
     */
    private List<CodePattern> scanByDimension(String repoPath, ScanDimension dimension) {
        List<CodePattern> patterns = new ArrayList<>();

        switch (dimension) {
            case NETWORK:
                patterns.addAll(scanNetworkPatterns(repoPath));
                break;
            case ERROR_HANDLING:
                patterns.addAll(scanErrorHandlingPatterns(repoPath));
                break;
            case SECURITY:
                patterns.addAll(scanSecurityPatterns(repoPath));
                break;
            case CONCURRENCY:
                patterns.addAll(scanConcurrencyPatterns(repoPath));
                break;
            case DATABASE:
                patterns.addAll(scanDatabasePatterns(repoPath));
                break;
            default:
                patterns.addAll(scanGenericPatterns(repoPath, dimension));
        }

        return patterns;
    }

    /**
     * 网络调用模式扫描
     */
    private List<CodePattern> scanNetworkPatterns(String repoPath) {
        List<CodePattern> patterns = new ArrayList<>();

        // 模式1: HTTP 超时配置检查
        patterns.add(CodePattern.builder()
            .patternId("NET_001")
            .dimension(ScanDimension.NETWORK)
            .name("HTTP连接必须设置超时")
            .description("所有HTTP客户端连接必须显式设置连接超时和读取超时")
            .goodExample(
                "RestTemplate restTemplate = new RestTemplate();\n" +
                "HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();\n" +
                "factory.setConnectTimeout(3000);\n" +
                "factory.setReadTimeout(5000);\n" +
                "restTemplate.setRequestFactory(factory);")
            .badExample(
                "// 未设置超时,可能导致线程hang住\n" +
                "RestTemplate restTemplate = new RestTemplate();\n" +
                "String result = restTemplate.getForObject(url, String.class);")
            .searchPattern("new RestTemplate\\(\\)")
            .severity(RuleSeverity.P0)
            .build());

        // 模式2: 重试策略配置
        patterns.add(CodePattern.builder()
            .patternId("NET_002")
            .dimension(ScanDimension.NETWORK)
            .name("外部调用应配置重试策略")
            .description("调用外部服务时应配置合理的重试次数和退避策略")
            .searchPattern("@Retryable|RetryTemplate|retry\\(")
            .severity(RuleSeverity.P1)
            .build());

        return patterns;
    }

    /**
     * 异常处理模式扫描
     */
    private List<CodePattern> scanErrorHandlingPatterns(String repoPath) {
        List<CodePattern> patterns = new ArrayList<>();

        // 模式: 禁止吞掉异常
        patterns.add(CodePattern.builder()
            .patternId("ERR_001")
            .dimension(ScanDimension.ERROR_HANDLING)
            .name("禁止空catch块吞掉异常")
            .description("catch块中必须有日志记录或重新抛出异常")
            .badExample(
                "try {\n" +
                "    riskyOperation();\n" +
                "} catch (Exception e) {\n" +
                "    // 空catch块,异常被吞掉\n" +
                "}")
            .goodExample(
                "try {\n" +
                "    riskyOperation();\n" +
                "} catch (Exception e) {\n" +
                "    log.error(\"操作失败: param={}\", param, e);\n" +
                "    throw new BusinessException(ErrorCode.OPERATION_FAILED, e);\n" +
                "}")
            .searchPattern("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
            .severity(RuleSeverity.P0)
            .build());

        // 模式: 避免catch Exception
        patterns.add(CodePattern.builder()
            .patternId("ERR_002")
            .dimension(ScanDimension.ERROR_HANDLING)
            .name("避免直接catch Exception")
            .description("应catch具体的异常类型,而非笼统的Exception")
            .searchPattern("catch\\s*\\(\\s*Exception\\s+")
            .severity(RuleSeverity.P1)
            .build());

        return patterns;
    }

    /**
     * 使用 ripgrep 验证模式出现频率
     */
    private List<CodePattern> validateFrequency(String repoPath, List<CodePattern> patterns) {
        List<CodePattern> validated = new ArrayList<>();

        for (CodePattern pattern : patterns) {
            if (pattern.getSearchPattern() == null) continue;

            try {
                // 使用 ripgrep 统计匹配次数
                ProcessBuilder pb = new ProcessBuilder(
                    "rg", "--count-matches", "--type", "java",
                    pattern.getSearchPattern(), repoPath);
                pb.redirectErrorStream(true);

                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    int frequency = parseFrequencyFromRgOutput(output);
                    pattern.setFrequency(frequency);

                    // 频率 >= 3 认为是有效模式
                    if (frequency >= 3) {
                        validated.add(pattern);
                        log.debug("模式验证通过: id={}, freq={}", pattern.getPatternId(), frequency);
                    }
                }
            } catch (Exception e) {
                log.warn("ripgrep验证失败: pattern={}", pattern.getPatternId(), e);
            }
        }

        return validated;
    }

    private int parseFrequencyFromRgOutput(String output) {
        return Arrays.stream(output.split("\n"))
            .filter(line -> !line.isEmpty())
            .mapToInt(line -> {
                String[] parts = line.split(":");
                return parts.length >= 2 ? Integer.parseInt(parts[parts.length - 1].trim()) : 0;
            })
            .sum();
    }
}
```

### 3.2 Agent 2: PR 评论提取 Agent

```java
/**
 * Agent 2: PR 评论提取 Agent
 * 
 * 功能: 挖掘历史PR评论,筛选高价值评论,提炼为规则
 * 
 * 核心流程:
 * 1. 获取历史PR评论 (3000+条)
 * 2. 使用轻量模型(Haiku级)初筛 (200-400条高价值)
 * 3. P0规则需要5+条独立评论佐证
 * 4. 批量处理: 每批30条评论交给Agent处理
 */
public class PRCommentExtractionAgent {

    private final GitPlatformClient gitClient;
    private final LLMClient llmClient;
    private final RulesDatabase rulesDatabase;

    /**
     * 提取历史PR评论并转化为规则
     */
    public List<ReviewRule> extractRulesFromPRComments(String repoId) {
        log.info("开始PR评论提取: repo={}", repoId);

        // Step 1: 获取历史PR评论
        List<PRComment> allComments = fetchHistoricalComments(repoId);
        log.info("获取到历史评论: {} 条", allComments.size());

        // Step 2: 使用轻量模型筛选高价值评论
        List<PRComment> highValueComments = filterHighValueComments(allComments);
        log.info("筛选高价值评论: {} 条", highValueComments.size());

        // Step 3: 批量处理,提炼规则
        List<ReviewRule> rules = batchExtractRules(highValueComments);
        log.info("提炼出规则: {} 条", rules.size());

        // Step 4: P0规则验证 - 需要5+条独立评论佐证
        List<ReviewRule> validatedRules = validateP0Rules(rules, highValueComments);

        return validatedRules;
    }

    /**
     * 获取历史PR评论
     */
    private List<PRComment> fetchHistoricalComments(String repoId) {
        // 获取最近6个月的PR评论
        Date since = Date.from(LocalDate.now().minusMonths(6)
            .atStartOfDay(ZoneId.systemDefault()).toInstant());

        List<PRComment> comments = new ArrayList<>();
        int page = 1;

        while (true) {
            List<PRComment> batch = gitClient.getPRComments(repoId, since, page, 100);
            if (batch.isEmpty()) break;

            comments.addAll(batch);
            page++;

            if (comments.size() >= 3000) break; // 最多处理3000条
        }

        return comments;
    }

    /**
     * 使用轻量模型(Haiku级)筛选高价值评论
     * 过滤掉: LGTM、格式化建议、typo修复等低价值评论
     */
    private List<PRComment> filterHighValueComments(List<PRComment> comments) {
        List<PRComment> highValue = new ArrayList<>();

        // 分批处理,每批50条
        int batchSize = 50;
        for (int i = 0; i < comments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, comments.size());
            List<PRComment> batch = comments.subList(i, end);

            String prompt = buildFilterPrompt(batch);

            // 使用轻量模型快速分类
            LLMResponse response = llmClient.call(
                LLMRequest.builder()
                    .model("haiku")  // 轻量级模型,速度快成本低
                    .prompt(prompt)
                    .maxTokens(2000)
                    .build());

            List<Integer> highValueIndices = parseFilterResult(response.getContent());
            for (int idx : highValueIndices) {
                if (idx < batch.size()) {
                    highValue.add(batch.get(idx));
                }
            }
        }

        return highValue;
    }

    /**
     * 构建筛选提示词
     */
    private String buildFilterPrompt(List<PRComment> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是代码审查评论列表,请识别出有实质性技术价值的评论。\n");
        sb.append("排除: LGTM、格式修改、简单typo等低价值评论。\n");
        sb.append("保留: 涉及Bug、安全隐患、性能问题、设计缺陷的评论。\n\n");

        for (int i = 0; i < batch.size(); i++) {
            PRComment comment = batch.get(i);
            sb.append(String.format("[%d] %s\n  文件: %s, 行: %d\n  评论: %s\n\n",
                i, comment.getAuthor(), comment.getFilePath(),
                comment.getLineNumber(), comment.getBody()));
        }

        sb.append("请返回高价值评论的编号列表(JSON数组格式):");
        return sb.toString();
    }

    /**
     * 批量提取规则 - 每批30条评论
     */
    private List<ReviewRule> batchExtractRules(List<PRComment> comments) {
        List<ReviewRule> allRules = new ArrayList<>();

        int batchSize = 30;
        for (int i = 0; i < comments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, comments.size());
            List<PRComment> batch = comments.subList(i, end);

            String prompt = buildRuleExtractionPrompt(batch);

            // 使用中等模型提取规则
            LLMResponse response = llmClient.call(
                LLMRequest.builder()
                    .model("sonnet")  // 中等模型,平衡质量和成本
                    .prompt(prompt)
                    .maxTokens(4000)
                    .build());

            List<ReviewRule> batchRules = parseRuleExtractionResult(response.getContent());
            allRules.addAll(batchRules);
        }

        // 去重合并相似规则
        return deduplicateRules(allRules);
    }

    /**
     * P0 规则验证: 需要5+条独立评论佐证
     */
    private List<ReviewRule> validateP0Rules(List<ReviewRule> rules,
                                              List<PRComment> comments) {
        List<ReviewRule> validated = new ArrayList<>();

        for (ReviewRule rule : rules) {
            if (rule.getSeverity() == RuleSeverity.P0) {
                // 统计支持该规则的独立评论数
                long supportCount = comments.stream()
                    .filter(c -> isCommentSupportingRule(c, rule))
                    .map(PRComment::getAuthor)  // 按作者去重(独立评论)
                    .distinct()
                    .count();

                if (supportCount >= 5) {
                    rule.setSupportingCommentCount((int) supportCount);
                    validated.add(rule);
                    log.info("P0规则验证通过: rule={}, 支持评论={}条",
                        rule.getRuleId(), supportCount);
                } else {
                    // 降级为 P1
                    rule.setSeverity(RuleSeverity.P1);
                    validated.add(rule);
                    log.info("P0规则降级为P1: rule={}, 支持评论={}条(不足5条)",
                        rule.getRuleId(), supportCount);
                }
            } else {
                validated.add(rule);
            }
        }

        return validated;
    }

    private boolean isCommentSupportingRule(PRComment comment, ReviewRule rule) {
        // 语义相似度检查 - 评论内容与规则描述是否相关
        double similarity = computeSemanticSimilarity(comment.getBody(), rule.getDescription());
        return similarity > 0.7; // 相似度阈值
    }
}
```

### 3.3 Agent 3: 代码审查 Agent

```java
/**
 * Agent 3: 代码审查 Agent
 * 
 * 架构: 双Scanner + Merger + Verifier
 * Scanner A: Bug + 安全扫描
 * Scanner B: 规则匹配扫描
 * Merger: 合并去重两个Scanner的结果
 * Verifier: 验证发现的真实性,减少误报
 * 
 * 性能: 约10分钟/PR
 * 特性: 支持增量Push审查
 */
public class CodeReviewAgent {

    /**
     * 双Scanner架构
     */
    private final BugSecurityScanner scannerA;
    private final RuleBasedScanner scannerB;
    private final ReviewMerger merger;
    private final ReviewVerifier verifier;

    /**
     * 执行PR代码审查
     */
    public CodeReviewResult reviewPR(PRInfo pr) {
        long startTime = System.currentTimeMillis();
        log.info("开始PR审查: pr={}, files={}", pr.getPrNumber(), pr.getChangedFiles().size());

        // 获取PR变更内容
        List<FileDiff> diffs = getPRDiffs(pr);

        // 并行执行双Scanner
        CompletableFuture<List<ReviewFinding>> scannerAFuture =
            CompletableFuture.supplyAsync(() -> scannerA.scan(diffs));
        CompletableFuture<List<ReviewFinding>> scannerBFuture =
            CompletableFuture.supplyAsync(() -> scannerB.scan(diffs));

        List<ReviewFinding> findingsA;
        List<ReviewFinding> findingsB;

        try {
            findingsA = scannerAFuture.get(5, TimeUnit.MINUTES);
            findingsB = scannerBFuture.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("Scanner执行异常", e);
            throw new ReviewException("Scanner执行超时或异常", e);
        }

        log.info("Scanner完成: A发现{}个问题, B发现{}个问题",
            findingsA.size(), findingsB.size());

        // Merger: 合并去重
        List<ReviewFinding> mergedFindings = merger.merge(findingsA, findingsB);
        log.info("合并去重后: {}个问题", mergedFindings.size());

        // Verifier: 验证真实性,减少误报
        List<ReviewFinding> verifiedFindings = verifier.verify(mergedFindings, diffs);
        log.info("验证后: {}个问题", verifiedFindings.size());

        long duration = System.currentTimeMillis() - startTime;
        log.info("PR审查完成: pr={}, 发现{}个问题, 耗时{}ms",
            pr.getPrNumber(), verifiedFindings.size(), duration);

        return CodeReviewResult.builder()
            .prNumber(pr.getPrNumber())
            .findings(verifiedFindings)
            .duration(duration)
            .scannerACount(findingsA.size())
            .scannerBCount(findingsB.size())
            .mergedCount(mergedFindings.size())
            .verifiedCount(verifiedFindings.size())
            .build();
    }

    /**
     * Scanner A: Bug + 安全扫描
     * 使用高级模型发现潜在Bug和安全漏洞
     */
    public static class BugSecurityScanner {

        private final LLMClient llmClient;

        public List<ReviewFinding> scan(List<FileDiff> diffs) {
            List<ReviewFinding> findings = new ArrayList<>();

            for (FileDiff diff : diffs) {
                if (!isJavaFile(diff.getFilePath())) continue;

                String prompt = buildBugScanPrompt(diff);

                // 使用高级模型(发现类任务需要最强模型)
                LLMResponse response = llmClient.call(
                    LLMRequest.builder()
                        .model("opus")  // 高级模型用于发现Bug
                        .prompt(prompt)
                        .maxTokens(3000)
                        .build());

                List<ReviewFinding> fileFindings = parseFindings(response.getContent(), diff);
                findings.addAll(fileFindings);
            }

            return findings;
        }

        private String buildBugScanPrompt(FileDiff diff) {
            return String.format(
                "请审查以下Java代码变更,重点关注:\n" +
                "1. 潜在Bug: 空指针、数组越界、资源泄露、并发问题\n" +
                "2. 安全漏洞: SQL注入、XSS、敏感信息泄露、权限校验缺失\n" +
                "3. 逻辑错误: 边界条件、状态机错误、数据竞争\n\n" +
                "文件: %s\n\n" +
                "变更内容:\n%s\n\n" +
                "请以JSON格式返回发现的问题,包含:\n" +
                "- line: 行号\n" +
                "- severity: P0/P1/P2\n" +
                "- category: BUG/SECURITY\n" +
                "- message: 问题描述\n" +
                "- suggestion: 修复建议",
                diff.getFilePath(), diff.getPatchContent());
        }
    }

    /**
     * Scanner B: 规则匹配扫描
     * 基于规则库进行模式匹配
     */
    public static class RuleBasedScanner {

        private final RulesDatabase rulesDatabase;
        private final LLMClient llmClient;

        public List<ReviewFinding> scan(List<FileDiff> diffs) {
            List<ReviewFinding> findings = new ArrayList<>();

            // 加载当前生效的规则
            List<ReviewRule> activeRules = rulesDatabase.getActiveRules();
            log.info("加载规则: {}条", activeRules.size());

            for (FileDiff diff : diffs) {
                if (!isJavaFile(diff.getFilePath())) continue;

                // 将规则和代码一起发送给模型
                String prompt = buildRuleMatchPrompt(diff, activeRules);

                // 使用中等模型(规则匹配不需要最强模型)
                LLMResponse response = llmClient.call(
                    LLMRequest.builder()
                        .model("sonnet")
                        .prompt(prompt)
                        .maxTokens(3000)
                        .build());

                List<ReviewFinding> fileFindings = parseFindings(response.getContent(), diff);
                fileFindings.forEach(f -> f.setSource("RULE_BASED"));
                findings.addAll(fileFindings);
            }

            return findings;
        }

        private String buildRuleMatchPrompt(FileDiff diff, List<ReviewRule> rules) {
            StringBuilder sb = new StringBuilder();
            sb.append("请根据以下规则集审查代码变更:\n\n");

            sb.append("=== 规则集 ===\n");
            for (ReviewRule rule : rules) {
                sb.append(String.format("[%s] %s (%s)\n  说明: %s\n",
                    rule.getRuleId(), rule.getName(),
                    rule.getSeverity(), rule.getDescription()));
                if (rule.getBadExample() != null) {
                    sb.append(String.format("  反例: %s\n", rule.getBadExample()));
                }
                sb.append("\n");
            }

            sb.append("=== 代码变更 ===\n");
            sb.append("文件: ").append(diff.getFilePath()).append("\n");
            sb.append(diff.getPatchContent()).append("\n");

            sb.append("\n请返回违反规则的发现(JSON格式):");
            return sb.toString();
        }
    }

    /**
     * Merger: 合并两个Scanner的结果
     */
    public static class ReviewMerger {

        /**
         * 合并去重
         * 两个Scanner可能发现同一个问题,需要去重
         */
        public List<ReviewFinding> merge(List<ReviewFinding> findingsA,
                                          List<ReviewFinding> findingsB) {
            Map<String, ReviewFinding> merged = new LinkedHashMap<>();

            // 先添加Scanner A的结果 (Bug+安全优先级更高)
            for (ReviewFinding finding : findingsA) {
                String key = generateDeduplicationKey(finding);
                merged.put(key, finding);
            }

            // 添加Scanner B的结果,如果已存在则合并信息
            for (ReviewFinding finding : findingsB) {
                String key = generateDeduplicationKey(finding);
                if (merged.containsKey(key)) {
                    // 合并: 保留更高严重级别
                    ReviewFinding existing = merged.get(key);
                    if (finding.getSeverity().ordinal() < existing.getSeverity().ordinal()) {
                        existing.setSeverity(finding.getSeverity());
                    }
                    existing.addAdditionalContext(finding.getMessage());
                } else {
                    merged.put(key, finding);
                }
            }

            return new ArrayList<>(merged.values());
        }

        private String generateDeduplicationKey(ReviewFinding finding) {
            return finding.getFilePath() + ":" + finding.getLineNumber() + ":" +
                   finding.getCategory();
        }
    }

    /**
     * Verifier: 验证发现的真实性
     * 使用中等模型二次验证,减少误报
     */
    public static class ReviewVerifier {

        private final LLMClient llmClient;

        public List<ReviewFinding> verify(List<ReviewFinding> findings,
                                           List<FileDiff> diffs) {
            List<ReviewFinding> verified = new ArrayList<>();

            for (ReviewFinding finding : findings) {
                // 获取完整上下文
                String fullContext = getFullContext(finding, diffs);

                String prompt = String.format(
                    "请验证以下代码审查发现是否为真实问题:\n\n" +
                    "发现: %s\n" +
                    "严重级别: %s\n" +
                    "文件: %s, 行: %d\n\n" +
                    "完整代码上下文:\n%s\n\n" +
                    "请判断这是否是一个真实的问题(不是误报)。\n" +
                    "返回JSON: {\"isReal\": true/false, \"confidence\": 0.0-1.0, \"reason\": \"...\"}",
                    finding.getMessage(), finding.getSeverity(),
                    finding.getFilePath(), finding.getLineNumber(),
                    fullContext);

                LLMResponse response = llmClient.call(
                    LLMRequest.builder()
                        .model("sonnet")  // 验证用中等模型
                        .prompt(prompt)
                        .maxTokens(500)
                        .build());

                VerificationResult result = parseVerificationResult(response.getContent());

                if (result.isReal() && result.getConfidence() > 0.7) {
                    finding.setVerified(true);
                    finding.setConfidence(result.getConfidence());
                    verified.add(finding);
                } else {
                    log.debug("发现被验证为误报: file={}, line={}, reason={}",
                        finding.getFilePath(), finding.getLineNumber(), result.getReason());
                }
            }

            return verified;
        }
    }

    /**
     * 增量Push审查
     * 支持开发者在PR中Push新代码时只审查增量变更
     */
    public CodeReviewResult incrementalReview(PRInfo pr, String previousCommit,
                                                String newCommit) {
        log.info("增量审查: pr={}, from={}, to={}", pr.getPrNumber(),
            previousCommit.substring(0, 8), newCommit.substring(0, 8));

        // 获取两个commit之间的增量diff
        List<FileDiff> incrementalDiffs = getIncrementalDiffs(
            pr.getRepoId(), previousCommit, newCommit);

        // 只审查增量变更
        return reviewPR(pr.withDiffs(incrementalDiffs));
    }
}
```

### 3.4 Agent 4: 反馈审查 Agent

```java
/**
 * Agent 4: 反馈审查 Agent
 * 
 * 功能:
 * 1. 分析开发者对审查评论的回复意图 (反驳/接受/疑问)
 * 2. 当>=3条类似反驳累积时,触发规则修订提案
 * 3. 从后续Push diff中检测问题是否已被修复
 */
public class FeedbackReviewAgent {

    private final LLMClient llmClient;
    private final RulesDatabase rulesDatabase;
    private final FeedbackRepository feedbackRepo;

    /**
     * 反馈意图分类
     */
    public enum FeedbackIntent {
        REBUTTAL("反驳", "开发者认为审查意见不正确或不适用"),
        ACCEPT("接受", "开发者接受审查意见,将进行修改"),
        QUESTION("疑问", "开发者对审查意见有疑问,需要澄清");

        private final String label;
        private final String description;

        FeedbackIntent(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    /**
     * 分析反馈意图
     */
    public FeedbackAnalysisResult analyzeFeedback(ReviewFeedback feedback) {
        String prompt = String.format(
            "请分析以下代码审查交互中开发者的回复意图:\n\n" +
            "审查评论: %s\n" +
            "开发者回复: %s\n\n" +
            "请判断开发者的意图:\n" +
            "- REBUTTAL: 反驳审查意见\n" +
            "- ACCEPT: 接受审查意见\n" +
            "- QUESTION: 对审查意见有疑问\n\n" +
            "返回JSON: {\"intent\": \"...\", \"confidence\": 0.0-1.0, \"reason\": \"...\"}",
            feedback.getReviewComment(), feedback.getDeveloperReply());

        // 使用轻量模型做意图分类
        LLMResponse response = llmClient.call(
            LLMRequest.builder()
                .model("haiku")
                .prompt(prompt)
                .maxTokens(300)
                .build());

        FeedbackAnalysisResult result = parseFeedbackResult(response.getContent());

        // 持久化反馈分析结果
        feedbackRepo.save(FeedbackRecord.builder()
            .feedbackId(feedback.getFeedbackId())
            .ruleId(feedback.getRuleId())
            .intent(result.getIntent())
            .confidence(result.getConfidence())
            .timestamp(new Date())
            .build());

        return result;
    }

    /**
     * 反驳累积检查 - 触发规则修订
     * 当同一规则累积>=3条反驳时,自动提议修改规则
     */
    @Scheduled(cron = "0 0 10 * * ?")  // 每天10点检查
    public void checkRebuttalAccumulation() {
        // 查询所有规则的反驳统计
        Map<String, Long> rebuttalCounts = feedbackRepo.countRebuttalsByRule(
            Duration.ofDays(30));

        for (Map.Entry<String, Long> entry : rebuttalCounts.entrySet()) {
            String ruleId = entry.getKey();
            long count = entry.getValue();

            if (count >= 3) {
                log.info("规则反驳累积: ruleId={}, count={}, 触发规则修订提案",
                    ruleId, count);

                // 收集反驳理由
                List<FeedbackRecord> rebuttals = feedbackRepo.findRebuttalsByRule(ruleId);
                
                // 生成规则修订提案
                RuleRevisionProposal proposal = generateRevisionProposal(ruleId, rebuttals);
                
                // 通知规则维护者
                notifyRuleOwner(ruleId, proposal);
            }
        }
    }

    /**
     * 生成规则修订提案
     */
    private RuleRevisionProposal generateRevisionProposal(String ruleId,
                                                            List<FeedbackRecord> rebuttals) {
        ReviewRule currentRule = rulesDatabase.getRule(ruleId);

        // 收集反驳理由
        String rebuttalSummary = rebuttals.stream()
            .map(r -> "- " + r.getRebuttalReason())
            .collect(Collectors.joining("\n"));

        String prompt = String.format(
            "代码审查规则 [%s: %s] 收到了多次反驳。\n\n" +
            "当前规则描述: %s\n\n" +
            "开发者反驳理由汇总:\n%s\n\n" +
            "请分析这些反驳是否合理,并提出规则修订建议:\n" +
            "1. 是否应该修改规则? (YES/NO)\n" +
            "2. 如果YES,修改建议是什么?\n" +
            "3. 修改后的规则描述\n" +
            "4. 是否应该降低严重级别?",
            currentRule.getRuleId(), currentRule.getName(),
            currentRule.getDescription(), rebuttalSummary);

        LLMResponse response = llmClient.call(
            LLMRequest.builder()
                .model("sonnet")
                .prompt(prompt)
                .maxTokens(2000)
                .build());

        return parseRevisionProposal(response.getContent(), ruleId);
    }

    /**
     * 修复检测: 从后续Push diff中判断问题是否已修复
     */
    public FixDetectionResult detectFix(ReviewFinding originalFinding,
                                         List<FileDiff> subsequentDiffs) {
        // 查找与原始发现相关的文件变更
        Optional<FileDiff> relatedDiff = subsequentDiffs.stream()
            .filter(d -> d.getFilePath().equals(originalFinding.getFilePath()))
            .findFirst();

        if (!relatedDiff.isPresent()) {
            return FixDetectionResult.notFixed("相关文件未修改");
        }

        FileDiff diff = relatedDiff.get();

        // 检查原始问题行是否被修改
        boolean lineModified = diff.getModifiedLines()
            .contains(originalFinding.getLineNumber());

        if (!lineModified) {
            return FixDetectionResult.notFixed("问题所在行未修改");
        }

        // 使用模型验证修改是否解决了问题
        String prompt = String.format(
            "原始审查问题:\n%s\n\n" +
            "文件: %s, 行: %d\n\n" +
            "后续代码变更:\n%s\n\n" +
            "请判断这次变更是否修复了上述审查问题。\n" +
            "返回JSON: {\"isFixed\": true/false, \"confidence\": 0.0-1.0}",
            originalFinding.getMessage(),
            originalFinding.getFilePath(), originalFinding.getLineNumber(),
            diff.getPatchContent());

        LLMResponse response = llmClient.call(
            LLMRequest.builder()
                .model("haiku")
                .prompt(prompt)
                .maxTokens(200)
                .build());

        return parseFixDetectionResult(response.getContent());
    }
}
```

### 3.5 Rules Database (规则库)

```java
/**
 * Rules Database - 规则库
 * 
 * 四个Agent的中心枢纽
 * 规则来源: Agent1(代码模式) + Agent2(PR评论) + Agent4(反馈演进)
 * 规则消费: Agent3(代码审查)
 */
public class RulesDatabase {

    /**
     * 规则数据模型
     */
    @Data
    @Builder
    public static class ReviewRule {
        private String ruleId;
        private String name;
        private String description;
        private RuleSeverity severity;          // P0 / P1 / P2
        private String category;                // BUG / SECURITY / STYLE / PERFORMANCE
        private String source;                  // CODEBASE / PR_COMMENT / MANUAL
        private String goodExample;             // 正确示例
        private String badExample;              // 错误示例
        private String searchPattern;           // 正则匹配模式
        private int supportingCommentCount;     // 支持该规则的独立评论数
        private boolean isActive;               // 是否启用
        private Date createTime;
        private Date updateTime;
        private String lastModifiedBy;
        private int rebuttalCount;              // 累计反驳次数
        private int acceptCount;                // 累计接受次数
        private double effectivenessScore;      // 有效性评分
    }

    /**
     * 规则CRUD操作
     */
    public interface RuleRepository {
        void save(ReviewRule rule);
        ReviewRule findById(String ruleId);
        List<ReviewRule> findActiveRules();
        List<ReviewRule> findByCategory(String category);
        List<ReviewRule> findBySeverity(RuleSeverity severity);
        void updateStatus(String ruleId, boolean active);
        void incrementRebuttalCount(String ruleId);
        void incrementAcceptCount(String ruleId);
    }

    /**
     * 规则生命周期管理
     */
    public static class RuleLifecycleManager {

        private final RuleRepository ruleRepo;

        /**
         * 规则有效性评估
         * 综合接受率、反驳率计算规则效力分
         */
        public void evaluateRuleEffectiveness() {
            List<ReviewRule> allRules = ruleRepo.findActiveRules();

            for (ReviewRule rule : allRules) {
                int totalFeedback = rule.getAcceptCount() + rule.getRebuttalCount();
                if (totalFeedback < 10) continue; // 反馈数不足,跳过

                double acceptRate = (double) rule.getAcceptCount() / totalFeedback;
                double effectivenessScore = acceptRate * 100;

                rule.setEffectivenessScore(effectivenessScore);
                ruleRepo.save(rule);

                // 低效规则告警
                if (effectivenessScore < 50) {
                    log.warn("低效规则: ruleId={}, acceptRate={:.2f}%, 建议审查",
                        rule.getRuleId(), acceptRate * 100);
                }

                // 极低效规则自动停用
                if (effectivenessScore < 20 && totalFeedback >= 20) {
                    log.warn("规则自动停用: ruleId={}, acceptRate={:.2f}%",
                        rule.getRuleId(), acceptRate * 100);
                    ruleRepo.updateStatus(rule.getRuleId(), false);
                }
            }
        }
    }
}
```

### 3.6 Code Review 组织流程

```java
/**
 * Code Review 组织流程管理
 */
public class CodeReviewOrganization {

    /**
     * 角色定义
     */
    public enum ReviewRole {
        DEVELOPER("普通开发者", "提交代码,接受审查"),
        REVIEWER("普通审查者", "可参与审查,无合入权限"),
        CERTIFIED_REVIEWER("认证审查者", "通过认证考试,有合入权限"),
        CR_COMMITTEE("CR委员会", "审查CR质量,制定标准"),
        STANDARDS_COMMITTEE("标准委员会", "制定编码规范");

        private final String name;
        private final String description;

        ReviewRole(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    /**
     * 认证审查者资格管理
     * 认证要求: 编码规范考试 + CR规范考试 + 历史CR记录
     */
    public static class CertificationManager {

        /**
         * 认证审查者申请条件
         */
        @Data
        @Builder
        public static class CertificationRequirement {
            private boolean codingStandardsExamPassed;   // 编码规范考试通过
            private boolean crStandardsExamPassed;       // CR规范考试通过
            private int historicalCRCount;                // 历史CR记录数量
            private double historicalCRQualityScore;     // 历史CR质量分
            private int nominationCount;                 // 组织提名次数
        }

        /**
         * 认证路径1: 组织提名
         */
        public CertificationResult certifyByNomination(String developerId,
                                                         String nominatorId) {
            CertificationRequirement req = getCertificationStatus(developerId);

            // 检查考试是否通过
            if (!req.isCodingStandardsExamPassed()) {
                return CertificationResult.failed("未通过编码规范考试");
            }
            if (!req.isCrStandardsExamPassed()) {
                return CertificationResult.failed("未通过CR规范考试");
            }

            // 检查历史CR记录
            if (req.getHistoricalCRCount() < 50) {
                return CertificationResult.failed(
                    "历史CR记录不足: 当前" + req.getHistoricalCRCount() + "条,需要50条");
            }

            // 通过认证
            certifiedReviewerDao.save(CertifiedReviewer.builder()
                .developerId(developerId)
                .certifiedBy(nominatorId)
                .certificationDate(new Date())
                .certificationPath("NOMINATION")
                .build());

            return CertificationResult.success(developerId);
        }

        /**
         * 认证路径2: 自助申请 (需5分,4维度)
         * 维度: 代码质量 / CR参与度 / 知识分享 / 团队贡献
         */
        public CertificationResult certifyBySelfApplication(String developerId) {
            int score = calculateCertificationScore(developerId);

            if (score < 5) {
                return CertificationResult.failed(
                    "积分不足: 当前" + score + "分,需要5分");
            }

            certifiedReviewerDao.save(CertifiedReviewer.builder()
                .developerId(developerId)
                .certifiedBy("SELF_APPLICATION")
                .certificationDate(new Date())
                .certificationPath("SELF_APPLICATION")
                .certificationScore(score)
                .build());

            return CertificationResult.success(developerId);
        }

        /**
         * 计算认证积分
         * 4个维度,每个维度最多2分,总计需要跨4个维度达到5分
         */
        private int calculateCertificationScore(String developerId) {
            int score = 0;

            // 维度1: 代码质量 (基于历史代码扫描结果)
            double codeQuality = metricsService.getCodeQualityScore(developerId);
            if (codeQuality >= 90) score += 2;
            else if (codeQuality >= 80) score += 1;

            // 维度2: CR参与度 (历史CR数量和质量)
            int crCount = crMetricsService.getCRCount(developerId, 180);
            double crQuality = crMetricsService.getCRQualityScore(developerId);
            if (crCount >= 100 && crQuality >= 4.0) score += 2;
            else if (crCount >= 50 && crQuality >= 3.5) score += 1;

            // 维度3: 知识分享 (技术分享、文档贡献)
            int sharingCount = knowledgeService.getSharingCount(developerId);
            if (sharingCount >= 5) score += 2;
            else if (sharingCount >= 2) score += 1;

            // 维度4: 团队贡献 (导师、培训、工具建设)
            int contributionScore = teamService.getContributionScore(developerId);
            if (contributionScore >= 10) score += 2;
            else if (contributionScore >= 5) score += 1;

            return score;
        }
    }

    /**
     * CR 流程控制
     */
    public static class CRFlowController {

        /**
         * 标准CR流程
         * 开发者自测 -> 提交PR -> 认证Reviewer审核 -> 合入master
         */
        public PRMergeResult submitAndReview(PRSubmission submission) {
            // Step 1: 自测检查
            SelfTestResult selfTest = verifySelfTest(submission);
            if (!selfTest.isPassed()) {
                return PRMergeResult.rejected("自测未通过: " + selfTest.getReason());
            }

            // Step 2: 静态代码检查自动阻断
            StaticCheckResult staticCheck = runStaticCheck(submission);
            if (staticCheck.hasBlockers()) {
                return PRMergeResult.rejected("静态检查有阻断问题: " +
                    staticCheck.getBlockerCount() + "个");
            }

            // Step 3: 分配认证Reviewer
            CertifiedReviewer reviewer = assignCertifiedReviewer(submission);

            // Step 4: 等待审核
            ReviewDecision decision = waitForReview(submission.getPrNumber(), reviewer);

            // Step 5: 检查所有评论是否已处理
            if (hasUnresolvedComments(submission.getPrNumber())) {
                return PRMergeResult.rejected("有未解决的Review评论");
            }

            // Step 6: 合入
            if (decision.isApproved()) {
                mergePR(submission.getPrNumber());
                return PRMergeResult.merged(submission.getPrNumber());
            }

            return PRMergeResult.rejected(decision.getReason());
        }

        /**
         * 分配认证Reviewer
         * 策略: 优先分配熟悉相关代码的认证Reviewer
         */
        private CertifiedReviewer assignCertifiedReviewer(PRSubmission submission) {
            List<String> changedFiles = submission.getChangedFiles();

            // 查找熟悉变更文件的认证Reviewer
            List<CertifiedReviewer> candidates = certifiedReviewerDao.findAll();

            // 按代码熟悉度排序
            candidates.sort((a, b) -> {
                int familiarityA = calculateFamiliarity(a.getDeveloperId(), changedFiles);
                int familiarityB = calculateFamiliarity(b.getDeveloperId(), changedFiles);
                return Integer.compare(familiarityB, familiarityA);
            });

            // 排除PR作者自己
            candidates = candidates.stream()
                .filter(r -> !r.getDeveloperId().equals(submission.getAuthor()))
                .collect(Collectors.toList());

            if (candidates.isEmpty()) {
                throw new ReviewException("无可用的认证Reviewer");
            }

            CertifiedReviewer assigned = candidates.get(0);
            log.info("分配认证Reviewer: pr={}, reviewer={}",
                submission.getPrNumber(), assigned.getDeveloperId());

            return assigned;
        }
    }

    /**
     * CR 质量审计
     */
    public static class CRAuditService {

        /**
         * CR委员会抽检
         * 定期随机抽取已合入的PR,检查CR质量
         */
        @Scheduled(cron = "0 0 14 * * WED")  // 每周三下午2点
        public void weeklyAudit() {
            // 随机抽取本周合入的PR (10%)
            List<PRInfo> mergedPRs = gitClient.getMergedPRs(
                LocalDate.now().minusWeeks(1), LocalDate.now());

            int sampleSize = Math.max(1, mergedPRs.size() / 10);
            List<PRInfo> samplePRs = randomSample(mergedPRs, sampleSize);

            log.info("CR质量抽检: 本周合入{}个PR, 抽检{}个", mergedPRs.size(), sampleSize);

            List<AuditResult> auditResults = new ArrayList<>();

            for (PRInfo pr : samplePRs) {
                AuditResult result = auditSinglePR(pr);
                auditResults.add(result);
            }

            // 生成审计报告
            AuditReport report = generateAuditReport(auditResults);
            notifyCRCommittee(report);
        }

        /**
         * AI辅助的CR质量评估
         */
        private AuditResult auditSinglePR(PRInfo pr) {
            // 获取PR的审查评论
            List<ReviewComment> comments = gitClient.getReviewComments(pr.getPrNumber());

            // 使用AI评估CR质量
            String prompt = String.format(
                "请评估以下代码审查的质量:\n\n" +
                "PR标题: %s\n" +
                "变更文件数: %d\n" +
                "变更行数: +%d -%d\n\n" +
                "审查评论:\n%s\n\n" +
                "请从以下维度评分(1-5分):\n" +
                "1. 问题发现深度: 是否发现了潜在Bug/安全/性能问题\n" +
                "2. 建议可操作性: 修改建议是否具体可执行\n" +
                "3. 覆盖完整性: 是否覆盖了所有关键变更\n" +
                "4. 沟通规范性: 沟通是否专业、建设性",
                pr.getTitle(), pr.getChangedFiles().size(),
                pr.getAdditions(), pr.getDeletions(),
                formatComments(comments));

            LLMResponse response = llmClient.call(
                LLMRequest.builder()
                    .model("sonnet")
                    .prompt(prompt)
                    .maxTokens(1000)
                    .build());

            return parseAuditResult(response.getContent(), pr);
        }
    }
}
```

### 3.7 静态代码扫描规则管理

```java
/**
 * 静态代码扫描规则管理
 * 部门级规则集管理,支持沟通和变更机制
 */
public class StaticCodeAnalysisManager {

    /**
     * 规则集定义
     */
    @Data
    @Builder
    public static class RuleSet {
        private String ruleSetId;
        private String departmentId;
        private String name;
        private List<StaticRule> rules;
        private Date effectiveDate;
        private String version;
    }

    /**
     * 静态规则
     */
    @Data
    @Builder
    public static class StaticRule {
        private String ruleId;
        private String name;
        private String description;
        private String severity;          // BLOCKER / CRITICAL / MAJOR / MINOR
        private String detector;           // PMD / SpotBugs / Checkstyle / SonarQube
        private String detectorRuleId;     // 检测器内的规则ID
        private boolean isActive;
        private String exampleCode;
        private String fixSuggestion;
    }

    /**
     * 规则变更管理
     */
    public static class RuleChangeManager {

        /**
         * 提交规则变更申请
         */
        public RuleChangeRequest submitRuleChange(RuleChangeProposal proposal) {
            // 创建变更请求
            RuleChangeRequest request = RuleChangeRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .proposer(proposal.getProposer())
                .changeType(proposal.getChangeType())  // ADD / MODIFY / REMOVE
                .ruleId(proposal.getRuleId())
                .currentConfig(proposal.getCurrentConfig())
                .proposedConfig(proposal.getProposedConfig())
                .reason(proposal.getReason())
                .impactAnalysis(analyzeImpact(proposal))
                .status(ChangeStatus.PENDING)
                .createTime(new Date())
                .build();

            // 保存并通知审批人
            changeRequestDao.save(request);
            notifyApprovers(request);

            return request;
        }

        /**
         * 影响分析: 评估规则变更的影响范围
         */
        private ImpactAnalysis analyzeImpact(RuleChangeProposal proposal) {
            ImpactAnalysis analysis = new ImpactAnalysis();

            if (proposal.getChangeType() == ChangeType.ADD) {
                // 新增规则: 扫描现有代码库,统计不符合的代码量
                int violationCount = scanExistingCode(proposal.getProposedConfig());
                analysis.setExistingViolationCount(violationCount);
                analysis.setAffectedFiles(getAffectedFiles(proposal.getProposedConfig()));
            } else if (proposal.getChangeType() == ChangeType.REMOVE) {
                // 移除规则: 统计该规则历史拦截次数
                int historicalBlocks = getHistoricalBlockCount(proposal.getRuleId());
                analysis.setHistoricalBlockCount(historicalBlocks);
            }

            return analysis;
        }
    }

    /**
     * 扫描执行引擎
     */
    public static class ScanEngine {

        /**
         * 执行静态代码扫描
         */
        public ScanResult executeScan(String projectPath, RuleSet ruleSet) {
            List<ScanViolation> allViolations = new ArrayList<>();

            // 多检测器并行扫描
            Map<String, List<StaticRule>> rulesByDetector = ruleSet.getRules().stream()
                .filter(StaticRule::isActive)
                .collect(Collectors.groupingBy(StaticRule::getDetector));

            List<CompletableFuture<List<ScanViolation>>> futures = new ArrayList<>();

            for (Map.Entry<String, List<StaticRule>> entry : rulesByDetector.entrySet()) {
                String detector = entry.getKey();
                List<StaticRule> rules = entry.getValue();

                futures.add(CompletableFuture.supplyAsync(() ->
                    executeDetector(detector, projectPath, rules)));
            }

            // 汇总结果
            for (CompletableFuture<List<ScanViolation>> future : futures) {
                try {
                    allViolations.addAll(future.get(10, TimeUnit.MINUTES));
                } catch (Exception e) {
                    log.error("检测器执行失败", e);
                }
            }

            return ScanResult.builder()
                .totalViolations(allViolations.size())
                .violations(allViolations)
                .blockerCount(allViolations.stream()
                    .filter(v -> "BLOCKER".equals(v.getSeverity())).count())
                .criticalCount(allViolations.stream()
                    .filter(v -> "CRITICAL".equals(v.getSeverity())).count())
                .build();
        }

        private List<ScanViolation> executeDetector(String detector,
                                                      String projectPath,
                                                      List<StaticRule> rules) {
            switch (detector) {
                case "PMD":
                    return executePMD(projectPath, rules);
                case "SpotBugs":
                    return executeSpotBugs(projectPath, rules);
                case "Checkstyle":
                    return executeCheckstyle(projectPath, rules);
                default:
                    log.warn("未知检测器: {}", detector);
                    return Collections.emptyList();
            }
        }

        /**
         * PMD 扫描
         */
        private List<ScanViolation> executePMD(String projectPath, List<StaticRule> rules) {
            // 构建PMD规则配置
            String ruleConfig = buildPMDRuleConfig(rules);

            ProcessBuilder pb = new ProcessBuilder(
                "pmd", "check",
                "-d", projectPath,
                "-R", ruleConfig,
                "-f", "json");

            try {
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();

                return parsePMDResult(output);
            } catch (Exception e) {
                log.error("PMD执行失败", e);
                return Collections.emptyList();
            }
        }
    }
}
```

### 3.8 技术债务管理

```java
/**
 * 技术债务管理框架
 */
public class TechnicalDebtManager {

    /**
     * 技术债务分类
     */
    public enum DebtCategory {
        ARCHITECTURE("架构债务", "不合理的架构设计、过度耦合"),
        CODE("代码债务", "代码质量差、重复代码、坏味道"),
        TEST("测试债务", "测试覆盖率低、缺少集成测试"),
        DEPENDENCY("依赖债务", "过期依赖、安全漏洞"),
        DOCUMENTATION("文档债务", "文档缺失或过时"),
        PERFORMANCE("性能债务", "已知的性能瓶颈未优化");

        private final String name;
        private final String description;

        DebtCategory(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    /**
     * 技术债务登记
     */
    @Data
    @Builder
    public static class TechnicalDebt {
        private String debtId;
        private DebtCategory category;
        private String title;
        private String description;
        private String filePath;
        private int estimatedEffort;       // 预估修复工时(人天)
        private int impactScore;           // 影响评分(1-10)
        private int urgencyScore;          // 紧迫度(1-10)
        private String registeredBy;
        private Date registeredDate;
        private DebtStatus status;
        private String assignee;
        private Date resolvedDate;
    }

    /**
     * 债务评估与优先级计算
     */
    public static class DebtPrioritizer {

        /**
         * 计算债务优先级
         * 优先级 = 影响评分 * 0.6 + 紧迫度 * 0.4
         */
        public List<TechnicalDebt> prioritize(List<TechnicalDebt> debts) {
            return debts.stream()
                .peek(debt -> {
                    double priority = debt.getImpactScore() * 0.6
                                    + debt.getUrgencyScore() * 0.4;
                    debt.setPriorityScore(priority);
                })
                .sorted(Comparator.comparingDouble(TechnicalDebt::getPriorityScore).reversed())
                .collect(Collectors.toList());
        }

        /**
         * 技术债务健康度报告
         */
        public DebtHealthReport generateHealthReport(String projectId) {
            List<TechnicalDebt> allDebts = debtDao.findByProject(projectId);

            return DebtHealthReport.builder()
                .totalDebts(allDebts.size())
                .openDebts((int) allDebts.stream()
                    .filter(d -> d.getStatus() == DebtStatus.OPEN).count())
                .resolvedDebts((int) allDebts.stream()
                    .filter(d -> d.getStatus() == DebtStatus.RESOLVED).count())
                .totalEstimatedEffort(allDebts.stream()
                    .filter(d -> d.getStatus() == DebtStatus.OPEN)
                    .mapToInt(TechnicalDebt::getEstimatedEffort).sum())
                .categoryDistribution(allDebts.stream()
                    .collect(Collectors.groupingBy(TechnicalDebt::getCategory,
                        Collectors.counting())))
                .topPriorityDebts(prioritize(allDebts).subList(0,
                    Math.min(10, allDebts.size())))
                .build();
        }
    }

    /**
     * AI辅助的技术债务发现
     */
    public static class AIDebtScanner {

        /**
         * 扫描代码仓库,自动发现技术债务
         */
        public List<TechnicalDebt> scanForDebts(String repoPath) {
            List<TechnicalDebt> debts = new ArrayList<>();

            // 1. 代码复杂度分析
            debts.addAll(scanCodeComplexity(repoPath));

            // 2. 重复代码检测
            debts.addAll(scanDuplicateCode(repoPath));

            // 3. 过期依赖检测
            debts.addAll(scanOutdatedDependencies(repoPath));

            // 4. 代码坏味道检测
            debts.addAll(scanCodeSmells(repoPath));

            return debts;
        }

        /**
         * 代码复杂度分析
         */
        private List<TechnicalDebt> scanCodeComplexity(String repoPath) {
            List<TechnicalDebt> debts = new ArrayList<>();

            // 使用工具计算圈复杂度
            List<ComplexityResult> results = complexityAnalyzer.analyze(repoPath);

            for (ComplexityResult result : results) {
                if (result.getCyclomaticComplexity() > 20) {
                    debts.add(TechnicalDebt.builder()
                        .category(DebtCategory.CODE)
                        .title("高复杂度方法: " + result.getMethodName())
                        .description(String.format(
                            "方法圈复杂度为%d(阈值20),建议拆分",
                            result.getCyclomaticComplexity()))
                        .filePath(result.getFilePath())
                        .impactScore(result.getCyclomaticComplexity() > 30 ? 8 : 5)
                        .urgencyScore(3)
                        .registeredBy("AI_SCANNER")
                        .registeredDate(new Date())
                        .status(DebtStatus.OPEN)
                        .build());
                }
            }

            return debts;
        }
    }
}
```

---

## 四、异常处理

### 4.1 AI Code Review 异常处理

```java
/**
 * AI Code Review 异常处理
 */
public class AICodeReviewExceptionHandler {

    /**
     * 模型调用失败处理
     */
    public ReviewFinding handleModelFailure(String agentName, Exception e,
                                             FileDiff diff) {
        log.error("AI模型调用失败: agent={}, file={}", agentName, diff.getFilePath(), e);

        // 降级策略: 仅运行基础规则匹配(不依赖模型)
        if (e instanceof TimeoutException) {
            // 超时: 记录并跳过
            metricsReporter.reportTimeout(agentName);
        } else if (e instanceof RateLimitException) {
            // 限流: 排队重试
            retryQueue.enqueue(agentName, diff);
        }

        return null; // 返回null表示跳过该文件
    }

    /**
     * 分层模型策略
     * 高级模型用于发现,中等模型用于验证,轻量模型用于分类
     */
    public static class TieredModelStrategy {

        public String selectModel(TaskType taskType) {
            switch (taskType) {
                case DISCOVERY:     return "opus";   // 发现Bug: 用最强模型
                case VERIFICATION:  return "sonnet"; // 验证: 用中等模型
                case CLASSIFICATION: return "haiku"; // 分类: 用轻量模型
                default:            return "sonnet";
            }
        }

        /**
         * 模型降级: 高级模型不可用时自动降级
         */
        public String fallbackModel(String preferredModel) {
            switch (preferredModel) {
                case "opus":   return "sonnet";  // 高级 -> 中等
                case "sonnet": return "haiku";   // 中等 -> 轻量
                default:       return preferredModel;
            }
        }
    }
}
```

### 4.2 Agent 设计的容错模式

```java
/**
 * Agent 设计的容错模式
 * 
 * 核心原则:
 * 1. 任务大小控制: 每个Agent处理的任务粒度要可控
 * 2. 状态外化: 通过MCP Tools外化中间状态
 * 3. 强制中间状态输出: 每步都有检查点
 * 4. 使用禁止列表而非建议列表: 明确禁止的比建议做的更有效
 */
public class AgentDesignPatterns {

    /**
     * 模式1: 任务大小控制
     * 将大任务拆分为可控粒度的子任务
     */
    public static class TaskSizeController {

        private static final int MAX_FILES_PER_BATCH = 10;
        private static final int MAX_RULES_PER_PROMPT = 30;

        public List<List<FileDiff>> splitIntoBatches(List<FileDiff> diffs) {
            List<List<FileDiff>> batches = new ArrayList<>();
            for (int i = 0; i < diffs.size(); i += MAX_FILES_PER_BATCH) {
                batches.add(diffs.subList(i,
                    Math.min(i + MAX_FILES_PER_BATCH, diffs.size())));
            }
            return batches;
        }
    }

    /**
     * 模式2: 状态外化
     * 通过MCP Tools将Agent的中间状态持久化
     */
    public static class StateExternalization {

        /**
         * 保存Agent中间状态
         */
        public void saveCheckpoint(String agentId, String stepName,
                                    Map<String, Object> state) {
            CheckpointData checkpoint = CheckpointData.builder()
                .agentId(agentId)
                .stepName(stepName)
                .state(JSON.toJSONString(state))
                .timestamp(new Date())
                .build();

            checkpointStore.save(checkpoint);
            log.debug("Agent检查点保存: agent={}, step={}", agentId, stepName);
        }

        /**
         * 从检查点恢复
         */
        public Map<String, Object> restoreFromCheckpoint(String agentId, String stepName) {
            CheckpointData checkpoint = checkpointStore.findLatest(agentId, stepName);
            if (checkpoint == null) return null;

            return JSON.parseObject(checkpoint.getState(),
                new TypeReference<Map<String, Object>>(){});
        }
    }

    /**
     * 模式3: 禁止列表优于建议列表
     * 明确禁止的行为比建议做的行为更有约束力
     */
    public static class ProhibitionListPattern {

        /**
         * 禁止列表示例
         */
        public static final List<String> PROHIBITED_PATTERNS = Arrays.asList(
            "禁止在catch块中使用e.printStackTrace()",
            "禁止在循环中创建数据库连接",
            "禁止在Controller层直接操作数据库",
            "禁止在日志中输出敏感信息(密码/token/身份证号)",
            "禁止使用System.out.println替代日志框架",
            "禁止在finally块中使用return语句",
            "禁止忽略InterruptedException(不重设中断标志位)",
            "禁止在equals方法中使用instanceof之后不检查null"
        );
    }
}
```

---

## 五、性能优化

### 5.1 AI Code Review 性能优化

```java
/**
 * AI Code Review 性能优化
 */
public class ReviewPerformanceOptimization {

    /**
     * 并行审查 - 文件级并行
     */
    public static class ParallelReviewOptimizer {

        private final ExecutorService reviewExecutor =
            Executors.newFixedThreadPool(4, new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "review-worker-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

        public List<ReviewFinding> parallelReview(List<FileDiff> diffs,
                                                    List<ReviewRule> rules) {
            List<CompletableFuture<List<ReviewFinding>>> futures = diffs.stream()
                .filter(d -> isJavaFile(d.getFilePath()))
                .map(diff -> CompletableFuture.supplyAsync(
                    () -> reviewSingleFile(diff, rules), reviewExecutor))
                .collect(Collectors.toList());

            return futures.stream()
                .flatMap(f -> {
                    try {
                        return f.get(3, TimeUnit.MINUTES).stream();
                    } catch (Exception e) {
                        log.error("文件审查超时", e);
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toList());
        }
    }

    /**
     * 增量审查优化
     * 只审查变更的代码,而非整个文件
     */
    public static class IncrementalReviewOptimizer {

        /**
         * 提取有效变更行
         * 过滤掉空行、注释、import语句等非实质变更
         */
        public List<String> extractMeaningfulChanges(FileDiff diff) {
            return diff.getAddedLines().stream()
                .filter(line -> !line.trim().isEmpty())
                .filter(line -> !line.trim().startsWith("//"))
                .filter(line -> !line.trim().startsWith("*"))
                .filter(line -> !line.trim().startsWith("import "))
                .filter(line -> !line.trim().startsWith("package "))
                .collect(Collectors.toList());
        }
    }

    /**
     * 结果缓存 - 相同代码模式不重复审查
     */
    public static class ReviewResultCache {

        private final Cache<String, List<ReviewFinding>> cache =
            CacheBuilder.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(24, TimeUnit.HOURS)
                .build();

        public List<ReviewFinding> getOrReview(FileDiff diff,
                                                Supplier<List<ReviewFinding>> reviewer) {
            String cacheKey = DigestUtils.md5Hex(diff.getPatchContent());

            List<ReviewFinding> cached = cache.getIfPresent(cacheKey);
            if (cached != null) {
                log.debug("命中审查缓存: file={}", diff.getFilePath());
                return cached;
            }

            List<ReviewFinding> findings = reviewer.get();
            cache.put(cacheKey, findings);
            return findings;
        }
    }
}
```

### 5.2 质量度量体系

```java
/**
 * 代码质量度量体系
 */
public class QualityMetricsFramework {

    /**
     * 核心质量指标
     */
    @Data
    @Builder
    public static class QualityMetrics {
        // 代码级指标
        private double lineCoverage;           // 行覆盖率
        private double branchCoverage;         // 分支覆盖率
        private double cyclomaticComplexity;   // 平均圈复杂度
        private int duplicatedLineCount;       // 重复代码行数
        private double duplicatedLineRatio;    // 重复代码比例

        // CR级指标
        private double crParticipationRate;    // CR参与率
        private double crCommentDensity;       // CR评论密度(评论数/变更行数)
        private double crTurnaroundTime;       // CR周转时间(小时)
        private double crApprovalRate;         // CR首次通过率

        // 缺陷级指标
        private int bugCountPerKLoc;           // 千行代码Bug数
        private double productionBugRate;      // 生产Bug率
        private double defectEscapeRate;       // 缺陷逃逸率

        // 技术债务指标
        private int openDebtCount;             // 待处理技术债务数
        private int totalDebtEffort;           // 技术债务总工时(人天)
        private double debtReductionRate;      // 债务消减率
    }

    /**
     * 质量趋势分析
     */
    public static class QualityTrendAnalyzer {

        public QualityTrend analyzeTrend(String projectId, int months) {
            List<QualityMetrics> historicalMetrics = metricsDao.getMonthlyMetrics(
                projectId, months);

            QualityTrend trend = new QualityTrend();

            // 计算各指标的趋势
            trend.setCoverageTrend(calculateTrend(
                historicalMetrics.stream().map(QualityMetrics::getLineCoverage)));
            trend.setComplexityTrend(calculateTrend(
                historicalMetrics.stream().map(QualityMetrics::getCyclomaticComplexity)));
            trend.setBugRateTrend(calculateTrend(
                historicalMetrics.stream().map(m -> (double) m.getBugCountPerKLoc())));

            return trend;
        }

        private TrendDirection calculateTrend(Stream<Double> values) {
            List<Double> list = values.collect(Collectors.toList());
            if (list.size() < 2) return TrendDirection.STABLE;

            double firstHalf = list.subList(0, list.size() / 2).stream()
                .mapToDouble(d -> d).average().orElse(0);
            double secondHalf = list.subList(list.size() / 2, list.size()).stream()
                .mapToDouble(d -> d).average().orElse(0);

            double changeRate = (secondHalf - firstHalf) / Math.max(firstHalf, 0.001);

            if (changeRate > 0.05) return TrendDirection.IMPROVING;
            if (changeRate < -0.05) return TrendDirection.DEGRADING;
            return TrendDirection.STABLE;
        }
    }
}
```

---

## 六、最佳实践

### 6.1 Code Review 检查清单

| 维度 | 检查项 | 优先级 |
|------|--------|--------|
| **正确性** | 是否有逻辑错误、边界条件问题 | P0 |
| **安全性** | 是否有SQL注入、XSS、权限绕过 | P0 |
| **异常处理** | 异常是否被正确捕获和处理 | P0 |
| **并发安全** | 是否有竞态条件、死锁风险 | P0 |
| **性能** | 是否有N+1查询、循环中的重操作 | P1 |
| **可维护性** | 命名是否清晰、逻辑是否简洁 | P1 |
| **测试** | 是否有对应的单元测试 | P1 |
| **日志** | 关键路径是否有足够的日志 | P2 |
| **兼容性** | API变更是否向前兼容 | P1 |
| **文档** | 公共API是否有清晰的注释 | P2 |

### 6.2 AI Code Review 使用指南

| 场景 | 推荐做法 | 避免做法 |
|------|---------|---------|
| 规则制定 | 使用禁止列表,明确不允许的 | 使用模糊的建议列表 |
| 任务粒度 | 每次处理10个文件以内 | 一次审查整个大型PR |
| 模型选择 | 分层: 发现用高级、验证用中等、分类用轻量 | 所有任务都用最贵的模型 |
| 误报处理 | 通过Verifier二次验证 | 直接输出Scanner结果 |
| 反馈闭环 | 收集反驳,自动触发规则修订 | 忽略开发者反馈 |

### 6.3 编码规范核心条目

```java
/**
 * 编码规范核心条目 (Java)
 */
public class CodingStandardsChecklist {

    // 1. 命名规范
    // 类名: UpperCamelCase
    // 方法名/变量名: lowerCamelCase
    // 常量: UPPER_SNAKE_CASE
    // 包名: 全小写

    // 2. 异常处理规范
    // - 禁止空catch块
    // - 禁止catch Exception(应catch具体异常)
    // - 必须在catch中记录日志或重新抛出
    // - finally块中禁止return

    // 3. 日志规范
    // - 使用SLF4J而非System.out
    // - 日志中禁止拼接字符串(使用占位符)
    // - 禁止输出敏感信息
    // - ERROR级别日志必须包含异常堆栈

    // 4. 并发规范
    // - 禁止使用new Thread(),使用线程池
    // - 线程池必须设置有界队列
    // - 必须正确处理InterruptedException
    // - 共享可变状态必须同步

    // 5. 数据库规范
    // - 禁止SELECT *
    // - 必须使用参数化查询(防SQL注入)
    // - 事务范围尽量小
    // - 索引字段不允许NULL
}
```

### 6.4 上线 Checklist

1. **CR 通过**: 认证 Reviewer 已 Approve，所有评论已解决
2. **静态扫描**: 无 BLOCKER/CRITICAL 问题
3. **测试覆盖**: 新增代码覆盖率 >= 80%
4. **安全扫描**: 无高危安全漏洞
5. **AI Review**: AI 发现的 P0 问题已全部修复
6. **技术债务**: 新代码未引入新的技术债务
7. **编码规范**: 符合团队编码规范
8. **文档更新**: API 变更对应文档已更新
9. **监控配置**: 关键业务指标告警已配置
10. **回滚验证**: 确认回滚方案可行
