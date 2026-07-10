# CI/CD 与发布策略架构设计

## 一、问题背景

### 1.1 软件交付的核心挑战

随着微服务架构的普及和业务迭代速度的加快，软件交付面临前所未有的挑战：

- **发布频率提升**：从月级发布到日级甚至小时级发布，对自动化程度要求极高
- **服务数量爆炸**：一个业务可能涉及数十甚至上百个微服务，手工发布不可持续
- **环境一致性**：开发、测试、预发、生产环境之间的差异导致"在我机器上能跑"的问题
- **发布风险控制**：如何在保证发布速度的同时控制风险，避免线上故障
- **回滚能力**：出现问题时能够快速回滚到稳定版本

### 1.2 CI/CD 的核心理念

CI/CD 是现代软件工程的基石，包含三个层次：

```
┌──────────────────────────────────────────────────────────────┐
│                     CI/CD 成熟度模型                          │
│                                                              │
│  Level 1: 持续集成 (CI - Continuous Integration)              │
│  ├── 代码提交自动触发构建                                      │
│  ├── 自动化单元测试                                           │
│  └── 静态代码检查                                             │
│                                                              │
│  Level 2: 持续交付 (CD - Continuous Delivery)                 │
│  ├── 自动化部署到测试/预发环境                                  │
│  ├── 自动化集成测试                                           │
│  └── 一键发布到生产环境(需人工审批)                              │
│                                                              │
│  Level 3: 持续部署 (CD - Continuous Deployment)               │
│  ├── 全自动化发布到生产环境                                    │
│  ├── 自动化金丝雀验证                                         │
│  └── 自动化回滚                                              │
└──────────────────────────────────────────────────────────────┘
```

### 1.3 分支模型

大规模研发团队通常采用基于 master 的分支模型：

```
master ─────●──────●──────●──────●──────●──────●──── (稳定主干)
             ╲    ╱        ╲    ╱        ╲    ╱
feature/A ────●──●          │   │         │   │
                            ╲  ╱          │   │
feature/B ───────────────────●●           │   │
                                          ╲  ╱
feature/C ─────────────────────────────────●●

integration ───────●──────────●──────────●──── (集成分支,自动合并)
```

**分支规范**：
- **master**：稳定主干，永远可发布
- **feature/xxx**：功能分支，从 master 拉取，开发完成后 PR 合入 master
- **integration**：集成分支，每日凌晨自动从待集成的 feature 分支合并，用于集成测试

### 1.4 Pipeline 分类

| Pipeline 类型 | 触发时机 | 主要内容 | 目标 |
|--------------|---------|---------|------|
| **Push Pipeline** | 代码 Push | 编译、单测、代码检查 | 快速反馈代码质量 |
| **PR Pipeline** | 提交 PR | 编译、单测、增量代码检查、CR | 保障合入质量 |
| **Integration Pipeline** | 定时/手动 | 集成测试、E2E测试 | 验证多服务联调 |
| **Release Pipeline** | 手动触发 | 构建镜像、灰度发布、验证 | 安全上线 |

---

## 二、整体架构设计

### 2.1 CI/CD 平台整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                     CI/CD 平台整体架构                            │
│                                                                 │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐  │
│  │ 代码仓库   │    │ 流水线引擎 │    │ 制品仓库   │    │ 部署平台   │  │
│  │ (Git)     │───▶│ (Pipeline)│───▶│ (Harbor)  │───▶│ (K8s)    │  │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘  │
│       │               │                              │         │
│       │          ┌─────┴─────┐                  ┌────┴────┐    │
│       │          │           │                  │         │    │
│  ┌────┴────┐  ┌──┴───┐  ┌───┴──┐          ┌───┴───┐ ┌──┴──┐ │
│  │ Webhook  │  │ 代码  │  │ 测试  │          │ 灰度   │ │ 监控 │ │
│  │ 触发     │  │ 扫描  │  │ 执行  │          │ 发布   │ │ 告警 │ │
│  └─────────┘  └──────┘  └──────┘          └───────┘ └─────┘ │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    质量门禁层                              │   │
│  │  静态扫描 │ 单测覆盖率 │ 安全扫描 │ 性能基线 │ CR 审核      │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 发布策略对比

| 策略 | 原理 | 优点 | 缺点 | 适用场景 |
|------|------|------|------|----------|
| **滚动更新** | 逐批替换旧 Pod | 简单、节省资源 | 回滚慢、新旧版本共存 | 无状态服务 |
| **蓝绿部署** | 两套完整环境切换 | 零停机、秒级回滚 | 资源成本翻倍 | 核心服务、数据库变更 |
| **金丝雀发布** | 小流量验证新版本 | 风险可控、精确验证 | 流程复杂 | 核心链路、高风险变更 |
| **A/B 测试** | 按用户特征分流 | 业务效果验证 | 需要流量分发能力 | 产品功能验证 |
| **影子测试** | 复制线上流量到新版本 | 最安全、零用户影响 | 成本最高、写操作需隔离 | 架构升级、重构 |

### 2.3 部署平台核心概念

```
┌───────────────────────────────────────────────────────┐
│                   部署平台核心概念                       │
│                                                       │
│  Revision (不可变部署单元)                               │
│  ├── 容器镜像 (Image)                                  │
│  ├── 应用配置 (Config)                                  │
│  ├── 资源配额 (Resources)                               │
│  └── 环境变量 (Env)                                    │
│                                                       │
│  Group (部署组)                                        │
│  ├── 区域 (Region): 机房/可用区                          │
│  ├── 泳道 (Swimlane): 流量隔离环境                       │
│  └── SET (单元化): 独立闭环的服务单元                      │
│                                                       │
│  Traffic (流量管理)                                     │
│  ├── Latest: 最新版本流量比例                             │
│  ├── Stable: 稳定版本流量比例                             │
│  └── Canary: 金丝雀版本流量比例                           │
└───────────────────────────────────────────────────────┘
```

---

## 三、核心链路设计

### 3.1 Push Pipeline 设计

```java
/**
 * Push Pipeline 实现
 * 代码推送自动触发,提供快速质量反馈
 */
public class PushPipeline {

    /**
     * Pipeline 阶段定义
     */
    public enum Stage {
        CHECKOUT("代码检出"),
        BUILD("编译构建"),
        STATIC_CHECK("静态代码检查"),
        UNIT_TEST("单元测试"),
        REPORT("报告生成");

        private final String displayName;
        Stage(String displayName) { this.displayName = displayName; }
    }

    /**
     * Pipeline 执行引擎
     */
    public static class PipelineExecutor {

        private final List<PipelineStage> stages;
        private final PipelineContext context;

        public PipelineExecutor(PipelineConfig config) {
            this.context = new PipelineContext(config);
            this.stages = buildStages(config);
        }

        /**
         * 执行 Pipeline
         */
        public PipelineResult execute() {
            log.info("Pipeline 开始执行: pipelineId={}, commit={}",
                context.getPipelineId(), context.getCommitId());

            long startTime = System.currentTimeMillis();
            PipelineResult result = new PipelineResult(context.getPipelineId());

            for (PipelineStage stage : stages) {
                StageResult stageResult = executeStage(stage);
                result.addStageResult(stageResult);

                if (!stageResult.isSuccess() && stage.isBlocking()) {
                    log.error("Pipeline 阶段失败(阻断): stage={}, error={}",
                        stage.getName(), stageResult.getErrorMessage());
                    result.setStatus(PipelineStatus.FAILED);
                    result.setFailedStage(stage.getName());
                    break;
                }
            }

            if (result.getStatus() == null) {
                result.setStatus(PipelineStatus.SUCCESS);
            }

            result.setDuration(System.currentTimeMillis() - startTime);
            notifyResult(result);
            return result;
        }

        private StageResult executeStage(PipelineStage stage) {
            log.info("执行阶段: {}", stage.getName());
            long stageStart = System.currentTimeMillis();

            try {
                stage.execute(context);
                return StageResult.success(stage.getName(),
                    System.currentTimeMillis() - stageStart);
            } catch (Exception e) {
                return StageResult.failure(stage.getName(),
                    System.currentTimeMillis() - stageStart, e.getMessage());
            }
        }

        private List<PipelineStage> buildStages(PipelineConfig config) {
            List<PipelineStage> stages = new ArrayList<>();
            stages.add(new CodeCheckoutStage());
            stages.add(new BuildStage(config.getBuildTemplate()));
            stages.add(new StaticCheckStage(config.getStaticCheckRules()));
            stages.add(new UnitTestStage(config.getTestConfig()));
            stages.add(new ReportStage());
            return stages;
        }
    }

    /**
     * 代码检出阶段
     */
    public static class CodeCheckoutStage implements PipelineStage {
        @Override
        public void execute(PipelineContext context) {
            String repoUrl = context.getRepoUrl();
            String branch = context.getBranch();
            String commitId = context.getCommitId();

            // Git clone + checkout
            ProcessBuilder pb = new ProcessBuilder(
                "git", "clone", "--depth", "1",
                "--branch", branch, repoUrl, context.getWorkspace());
            executeCommand(pb, context);

            // 切换到指定 commit
            ProcessBuilder checkout = new ProcessBuilder(
                "git", "checkout", commitId);
            checkout.directory(new File(context.getWorkspace()));
            executeCommand(checkout, context);

            log.info("代码检出完成: repo={}, branch={}, commit={}",
                repoUrl, branch, commitId);
        }

        @Override
        public String getName() { return "CHECKOUT"; }

        @Override
        public boolean isBlocking() { return true; }
    }

    /**
     * 编译构建阶段
     */
    public static class BuildStage implements PipelineStage {
        private final BuildTemplate template;

        public BuildStage(BuildTemplate template) {
            this.template = template;
        }

        @Override
        public void execute(PipelineContext context) {
            String buildCommand = template.getBuildCommand();

            // 执行构建 (Maven / Gradle)
            ProcessBuilder pb = new ProcessBuilder(buildCommand.split(" "));
            pb.directory(new File(context.getWorkspace()));
            pb.environment().putAll(template.getEnvironmentVars());

            int exitCode = executeCommand(pb, context);
            if (exitCode != 0) {
                throw new BuildException("构建失败, exitCode=" + exitCode);
            }

            // 记录构建产物信息
            context.setBuildArtifact(findArtifact(context.getWorkspace(), template));
            log.info("构建完成: artifact={}", context.getBuildArtifact());
        }

        @Override
        public String getName() { return "BUILD"; }

        @Override
        public boolean isBlocking() { return true; }
    }

    /**
     * 静态代码检查阶段
     */
    public static class StaticCheckStage implements PipelineStage {
        private final List<StaticCheckRule> rules;

        public StaticCheckStage(List<StaticCheckRule> rules) {
            this.rules = rules;
        }

        @Override
        public void execute(PipelineContext context) {
            List<CheckViolation> violations = new ArrayList<>();

            for (StaticCheckRule rule : rules) {
                List<CheckViolation> ruleViolations = rule.check(context.getWorkspace());
                violations.addAll(ruleViolations);
            }

            // 按严重级别分类
            long blockerCount = violations.stream()
                .filter(v -> v.getSeverity() == Severity.BLOCKER).count();
            long criticalCount = violations.stream()
                .filter(v -> v.getSeverity() == Severity.CRITICAL).count();

            context.setStaticCheckResult(new StaticCheckResult(violations));

            // BLOCKER 级别问题阻断 Pipeline
            if (blockerCount > 0) {
                throw new QualityGateException(
                    String.format("静态检查不通过: %d个阻断问题, %d个严重问题",
                        blockerCount, criticalCount));
            }

            log.info("静态检查通过: total={}, blocker={}, critical={}",
                violations.size(), blockerCount, criticalCount);
        }

        @Override
        public String getName() { return "STATIC_CHECK"; }

        @Override
        public boolean isBlocking() { return true; }
    }

    /**
     * 单元测试阶段
     */
    public static class UnitTestStage implements PipelineStage {
        private final TestConfig testConfig;

        public UnitTestStage(TestConfig testConfig) {
            this.testConfig = testConfig;
        }

        @Override
        public void execute(PipelineContext context) {
            // 执行单元测试
            ProcessBuilder pb = new ProcessBuilder(
                "mvn", "test",
                "-Dmaven.test.failure.ignore=false",
                "-Djacoco.skip=false");
            pb.directory(new File(context.getWorkspace()));

            int exitCode = executeCommand(pb, context);

            // 解析测试结果
            TestReport report = parseTestReport(context.getWorkspace());
            context.setTestReport(report);

            // 检查覆盖率门禁
            double coverage = report.getLineCoverage();
            if (coverage < testConfig.getMinCoverage()) {
                throw new QualityGateException(
                    String.format("测试覆盖率 %.1f%% 低于阈值 %.1f%%",
                        coverage * 100, testConfig.getMinCoverage() * 100));
            }

            // 检查测试通过率
            if (report.getFailedCount() > 0) {
                throw new QualityGateException(
                    String.format("单元测试失败: 通过 %d, 失败 %d, 跳过 %d",
                        report.getPassedCount(), report.getFailedCount(),
                        report.getSkippedCount()));
            }

            log.info("单元测试通过: total={}, passed={}, coverage={:.1f}%",
                report.getTotalCount(), report.getPassedCount(), coverage * 100);
        }

        @Override
        public String getName() { return "UNIT_TEST"; }

        @Override
        public boolean isBlocking() { return true; }
    }
}
```

### 3.2 PR Pipeline 设计

```java
/**
 * PR Pipeline 实现
 * 提交 PR 时触发,确保合入代码的质量
 */
public class PRPipeline {

    /**
     * PR Pipeline 特有的增量检查
     */
    public static class IncrementalCheckStage implements PipelineStage {

        @Override
        public void execute(PipelineContext context) {
            // 获取本次 PR 的变更文件列表
            List<String> changedFiles = getChangedFiles(
                context.getBaseBranch(), context.getHeadBranch());

            log.info("PR 变更文件数: {}", changedFiles.size());

            // 只对变更文件进行增量检查
            List<CheckViolation> violations = new ArrayList<>();

            for (String file : changedFiles) {
                if (file.endsWith(".java")) {
                    violations.addAll(checkJavaFile(file, context));
                }
            }

            // 将检查结果作为 PR Review Comment
            if (!violations.isEmpty()) {
                postReviewComments(context.getPrNumber(), violations);
            }

            long blockerCount = violations.stream()
                .filter(v -> v.getSeverity() == Severity.BLOCKER).count();

            if (blockerCount > 0) {
                throw new QualityGateException(
                    "增量代码检查发现 " + blockerCount + " 个阻断问题");
            }
        }

        /**
         * 获取 PR 变更文件列表
         */
        private List<String> getChangedFiles(String baseBranch, String headBranch) {
            ProcessBuilder pb = new ProcessBuilder(
                "git", "diff", "--name-only",
                baseBranch + "..." + headBranch);

            String output = executeAndCapture(pb);
            return Arrays.asList(output.split("\n"));
        }

        /**
         * 将检查结果作为 Review Comment 发送到 PR
         */
        private void postReviewComments(String prNumber, List<CheckViolation> violations) {
            for (CheckViolation violation : violations) {
                ReviewComment comment = ReviewComment.builder()
                    .path(violation.getFilePath())
                    .line(violation.getLineNumber())
                    .body(String.format("[%s] %s\n规则: %s",
                        violation.getSeverity(), violation.getMessage(),
                        violation.getRuleId()))
                    .build();

                gitClient.postReviewComment(prNumber, comment);
            }
        }

        @Override
        public String getName() { return "INCREMENTAL_CHECK"; }

        @Override
        public boolean isBlocking() { return true; }
    }

    /**
     * CR (Code Review) 审核门禁
     */
    public static class CodeReviewGate implements PipelineStage {

        @Override
        public void execute(PipelineContext context) {
            PRInfo pr = gitClient.getPRInfo(context.getPrNumber());

            // 检查是否有足够的 Reviewer 批准
            int approvedCount = pr.getApprovedReviewers().size();
            int requiredApprovals = context.getConfig().getRequiredApprovals();

            if (approvedCount < requiredApprovals) {
                throw new QualityGateException(
                    String.format("CR 审核不足: 需要 %d 个审批, 当前 %d 个",
                        requiredApprovals, approvedCount));
            }

            // 检查是否有未解决的 Review Comment
            long unresolvedCount = pr.getReviewComments().stream()
                .filter(c -> !c.isResolved()).count();
            if (unresolvedCount > 0) {
                throw new QualityGateException(
                    "有 " + unresolvedCount + " 条 Review 评论未解决");
            }

            log.info("CR 审核通过: approvals={}, comments全部已解决", approvedCount);
        }

        @Override
        public String getName() { return "CODE_REVIEW"; }

        @Override
        public boolean isBlocking() { return true; }
    }
}
```

### 3.3 集成测试 Pipeline 设计

```java
/**
 * Integration Pipeline 实现
 * 在集成环境(泳道)中运行,验证多服务联调
 */
public class IntegrationPipeline {

    /**
     * 泳道环境管理
     * 自动创建和管理集成测试用的泳道环境
     */
    public static class SwimlaneManager {

        /**
         * 创建集成测试泳道
         */
        public Swimlane createIntegrationSwimlane(IntegrationConfig config) {
            String swimlaneName = "integration-" + config.getBranchName()
                + "-" + System.currentTimeMillis();

            Swimlane swimlane = Swimlane.builder()
                .name(swimlaneName)
                .services(config.getServiceList())
                .baseEnvironment(config.getBaseEnvironment())
                .trafficRules(buildTrafficRules(config))
                .ttl(Duration.ofHours(config.getTtlHours()))
                .build();

            // 创建泳道
            deployPlatform.createSwimlane(swimlane);

            // 部署服务到泳道
            for (ServiceDeployConfig svc : config.getServiceList()) {
                deployPlatform.deployToSwimlane(swimlaneName, svc);
            }

            // 等待所有服务就绪
            waitForServicesReady(swimlaneName, config.getServiceList(),
                Duration.ofMinutes(10));

            log.info("集成测试泳道创建完成: swimlane={}, services={}",
                swimlaneName, config.getServiceList().size());

            return swimlane;
        }

        /**
         * 等待服务就绪
         */
        private void waitForServicesReady(String swimlaneName,
                                           List<ServiceDeployConfig> services,
                                           Duration timeout) {
            long deadline = System.currentTimeMillis() + timeout.toMillis();

            while (System.currentTimeMillis() < deadline) {
                boolean allReady = true;
                for (ServiceDeployConfig svc : services) {
                    ServiceStatus status = deployPlatform.getServiceStatus(
                        swimlaneName, svc.getServiceName());
                    if (status != ServiceStatus.READY) {
                        allReady = false;
                        break;
                    }
                }

                if (allReady) {
                    log.info("所有服务已就绪: swimlane={}", swimlaneName);
                    return;
                }

                try { Thread.sleep(5000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("等待服务就绪被中断", e);
                }
            }

            throw new TimeoutException("服务就绪超时: swimlane=" + swimlaneName);
        }
    }

    /**
     * 集成测试执行器
     */
    public static class IntegrationTestExecutor {

        /**
         * 执行集成测试
         * 每日凌晨 2:00-9:00 自动运行,也支持手动触发
         */
        @Scheduled(cron = "0 0 2 * * ?")
        public void runDailyIntegrationTests() {
            List<IntegrationConfig> configs = configService.getActiveIntegrationConfigs();

            for (IntegrationConfig config : configs) {
                try {
                    runIntegrationTest(config);
                } catch (Exception e) {
                    log.error("集成测试执行失败: config={}", config.getName(), e);
                    notifyFailure(config, e);
                }
            }
        }

        public IntegrationTestResult runIntegrationTest(IntegrationConfig config) {
            Swimlane swimlane = null;

            try {
                // Step 1: 创建泳道环境
                SwimlaneManager swimlaneManager = new SwimlaneManager();
                swimlane = swimlaneManager.createIntegrationSwimlane(config);

                // Step 2: 执行测试用例
                TestSuiteRunner runner = new TestSuiteRunner(swimlane.getEndpoint());
                TestSuiteResult result = runner.runSuite(config.getTestSuite());

                // Step 3: 生成报告
                IntegrationTestResult report = IntegrationTestResult.builder()
                    .configName(config.getName())
                    .totalTests(result.getTotalCount())
                    .passedTests(result.getPassedCount())
                    .failedTests(result.getFailedCount())
                    .duration(result.getDuration())
                    .swimlaneName(swimlane.getName())
                    .build();

                // Step 4: 通知结果
                notifyResult(report);
                return report;

            } finally {
                // 清理泳道
                if (swimlane != null) {
                    deployPlatform.destroySwimlane(swimlane.getName());
                }
            }
        }
    }
}
```

### 3.4 金丝雀发布核心流程

金丝雀发布是生产环境发布的核心策略，通过精确的流量控制实现新版本的渐进式验证。

```java
/**
 * 金丝雀发布控制器
 * 
 * 核心流程:
 * 1. 锁定流量到当前稳定版本 (override Latest:100%)
 * 2. 部署新版本到同一 Group,等待 Readiness
 * 3. 逐步调整流量比例 (5% -> 20% -> 50% -> 100%)
 * 4. 每个阶段监控系统指标 (2XX比例, P99延迟)
 * 5. 全部通过后重置为 Latest:100%;异常则立即回滚
 */
public class CanaryReleaseController {

    private final DeployPlatformClient deployClient;
    private final MetricsMonitor metricsMonitor;
    private final AlertService alertService;

    /**
     * 金丝雀发布配置
     */
    @Data
    @Builder
    public static class CanaryConfig {
        private String serviceName;
        private String group;             // 部署组 (region + swimlane)
        private String newRevision;        // 新版本 Revision
        private List<Integer> trafficSteps; // 流量阶梯 [5, 20, 50, 100]
        private Duration observationPeriod; // 每阶段观察时间
        private CanaryMetricsThreshold threshold; // 指标阈值
        private boolean autoRollback;      // 是否自动回滚
    }

    /**
     * 金丝雀指标阈值
     */
    @Data
    @Builder
    public static class CanaryMetricsThreshold {
        private double min2xxRatio;      // 最低 2XX 比例 (如 0.995)
        private long maxP99LatencyMs;    // 最大 P99 延迟 (如 500ms)
        private double maxErrorRate;     // 最大错误率 (如 0.01)
        private long maxP50LatencyMs;    // 最大 P50 延迟
    }

    /**
     * 执行金丝雀发布
     */
    public CanaryReleaseResult executeCanaryRelease(CanaryConfig config) {
        log.info("========== 金丝雀发布开始 ==========");
        log.info("服务: {}, 新版本: {}, 流量阶梯: {}",
            config.getServiceName(), config.getNewRevision(), config.getTrafficSteps());

        CanaryReleaseResult result = new CanaryReleaseResult(config.getServiceName());

        try {
            // ============ Step 1: 锁定当前流量 ============
            lockTrafficToStableVersion(config);

            // ============ Step 2: 部署新版本 ============
            deployNewRevision(config);

            // ============ Step 3: 逐步放量 ============
            for (int trafficPercent : config.getTrafficSteps()) {
                boolean stepPassed = executeCanaryStep(config, trafficPercent);

                if (!stepPassed) {
                    // 指标异常,触发回滚
                    log.error("金丝雀验证失败: step={}%, 触发回滚", trafficPercent);
                    rollback(config);
                    result.setStatus(CanaryStatus.ROLLED_BACK);
                    result.setFailedAtPercent(trafficPercent);
                    return result;
                }

                result.addPassedStep(trafficPercent);
            }

            // ============ Step 4: 发布成功,重置流量 ============
            resetTrafficToLatest(config);
            result.setStatus(CanaryStatus.SUCCESS);

            log.info("========== 金丝雀发布成功 ==========");

        } catch (Exception e) {
            log.error("金丝雀发布异常", e);
            if (config.isAutoRollback()) {
                rollback(config);
            }
            result.setStatus(CanaryStatus.ERROR);
            result.setErrorMessage(e.getMessage());
        }

        return result;
    }

    /**
     * Step 1: 锁定流量到当前稳定版本
     * 将所有流量指向当前 Stable 版本,防止新部署影响线上
     */
    private void lockTrafficToStableVersion(CanaryConfig config) {
        log.info("[Step 1] 锁定流量到稳定版本");

        // 设置流量规则: Stable=100%, Latest=0%
        TrafficPolicy policy = TrafficPolicy.builder()
            .stablePercent(100)
            .latestPercent(0)
            .canaryPercent(0)
            .build();

        deployClient.updateTrafficPolicy(
            config.getServiceName(), config.getGroup(), policy);

        log.info("流量已锁定: Stable=100%, Latest=0%");
    }

    /**
     * Step 2: 部署新版本到同一 Group
     * 新版本作为 Canary Revision 部署,但不接收流量
     */
    private void deployNewRevision(CanaryConfig config) {
        log.info("[Step 2] 部署新版本: revision={}", config.getNewRevision());

        // 部署新版本
        DeployRequest request = DeployRequest.builder()
            .serviceName(config.getServiceName())
            .group(config.getGroup())
            .revision(config.getNewRevision())
            .targetType("CANARY")
            .build();

        deployClient.deploy(request);

        // 等待新版本就绪
        waitForReadiness(config.getServiceName(), config.getNewRevision(),
            Duration.ofMinutes(5));

        log.info("新版本部署就绪: revision={}", config.getNewRevision());
    }

    /**
     * Step 3: 执行单步金丝雀验证
     * 调整流量比例,观察指标
     */
    private boolean executeCanaryStep(CanaryConfig config, int trafficPercent) {
        log.info("[Step 3] 放量至 {}%", trafficPercent);

        // 调整流量
        TrafficPolicy policy = TrafficPolicy.builder()
            .stablePercent(100 - trafficPercent)
            .latestPercent(0)
            .canaryPercent(trafficPercent)
            .build();

        deployClient.updateTrafficPolicy(
            config.getServiceName(), config.getGroup(), policy);

        // 等待流量生效
        try { Thread.sleep(10_000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 观察期: 持续监控指标
        return observeMetrics(config, trafficPercent);
    }

    /**
     * 观察期指标监控
     */
    private boolean observeMetrics(CanaryConfig config, int trafficPercent) {
        long observationMs = config.getObservationPeriod().toMillis();
        long checkInterval = 10_000; // 每10秒检查一次
        long elapsed = 0;

        while (elapsed < observationMs) {
            // 采集指标
            CanaryMetrics metrics = metricsMonitor.collectCanaryMetrics(
                config.getServiceName(), config.getNewRevision());

            log.info("金丝雀指标 [{}%]: 2XX比例={:.4f}, P99={}ms, 错误率={:.4f}",
                trafficPercent, metrics.getSuccess2xxRatio(),
                metrics.getP99LatencyMs(), metrics.getErrorRate());

            // 对比阈值
            CanaryMetricsThreshold threshold = config.getThreshold();

            if (metrics.getSuccess2xxRatio() < threshold.getMin2xxRatio()) {
                log.error("2XX比例不达标: actual={:.4f}, threshold={:.4f}",
                    metrics.getSuccess2xxRatio(), threshold.getMin2xxRatio());
                return false;
            }

            if (metrics.getP99LatencyMs() > threshold.getMaxP99LatencyMs()) {
                log.error("P99延迟不达标: actual={}ms, threshold={}ms",
                    metrics.getP99LatencyMs(), threshold.getMaxP99LatencyMs());
                return false;
            }

            if (metrics.getErrorRate() > threshold.getMaxErrorRate()) {
                log.error("错误率不达标: actual={:.4f}, threshold={:.4f}",
                    metrics.getErrorRate(), threshold.getMaxErrorRate());
                return false;
            }

            try { Thread.sleep(checkInterval); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            elapsed += checkInterval;
        }

        log.info("金丝雀验证通过: step={}%, 观察期={}s",
            trafficPercent, observationMs / 1000);
        return true;
    }

    /**
     * 回滚: 将所有流量切回稳定版本
     */
    private void rollback(CanaryConfig config) {
        log.warn("========== 执行回滚 ==========");

        // 1. 流量切回稳定版本
        TrafficPolicy policy = TrafficPolicy.builder()
            .stablePercent(100)
            .latestPercent(0)
            .canaryPercent(0)
            .build();
        deployClient.updateTrafficPolicy(
            config.getServiceName(), config.getGroup(), policy);

        // 2. 移除金丝雀版本
        deployClient.removeCanaryRevision(
            config.getServiceName(), config.getGroup());

        // 3. 发送告警
        alertService.sendAlert(AlertLevel.P1,
            "金丝雀发布回滚: " + config.getServiceName(),
            "新版本 " + config.getNewRevision() + " 验证未通过,已回滚");

        log.warn("回滚完成: 流量已切回稳定版本");
    }

    /**
     * 发布成功: 重置流量规则
     */
    private void resetTrafficToLatest(CanaryConfig config) {
        log.info("[Step 4] 发布成功,重置流量为 Latest:100%");

        // 将金丝雀版本提升为正式版本
        deployClient.promoteCanaryToStable(
            config.getServiceName(), config.getGroup());

        // 重置流量规则
        TrafficPolicy policy = TrafficPolicy.builder()
            .stablePercent(0)
            .latestPercent(100)
            .canaryPercent(0)
            .build();
        deployClient.updateTrafficPolicy(
            config.getServiceName(), config.getGroup(), policy);

        log.info("流量已重置: Latest=100%");
    }
}
```

### 3.5 蓝绿部署实现

```java
/**
 * 蓝绿部署控制器
 * 
 * 核心思想: 维护两套完整环境(Blue/Green)
 * 切换时只需要更改路由规则,实现秒级切换和回滚
 */
public class BlueGreenDeployController {

    /**
     * 蓝绿部署状态
     */
    @Data
    public static class BlueGreenState {
        private String activeColor;     // 当前活跃颜色 "BLUE" / "GREEN"
        private String blueRevision;    // Blue 环境版本
        private String greenRevision;   // Green 环境版本
        private Date lastSwitchTime;    // 上次切换时间
    }

    /**
     * 执行蓝绿部署
     */
    public BlueGreenResult executeBlueGreenDeploy(String serviceName,
                                                    String newRevision) {
        BlueGreenState state = getBlueGreenState(serviceName);

        // 确定目标环境 (非活跃的那个)
        String targetColor = "BLUE".equals(state.getActiveColor()) ? "GREEN" : "BLUE";
        log.info("蓝绿部署: 当前活跃={}, 目标环境={}", state.getActiveColor(), targetColor);

        try {
            // Step 1: 在非活跃环境部署新版本
            log.info("Step 1: 部署新版本到 {} 环境", targetColor);
            deployToEnvironment(serviceName, targetColor, newRevision);

            // Step 2: 等待新版本就绪
            log.info("Step 2: 等待 {} 环境就绪", targetColor);
            waitForEnvironmentReady(serviceName, targetColor, Duration.ofMinutes(5));

            // Step 3: 预热 (可选)
            log.info("Step 3: 预热 {} 环境", targetColor);
            warmup(serviceName, targetColor);

            // Step 4: 切换流量 (秒级)
            log.info("Step 4: 切换流量到 {} 环境", targetColor);
            switchTraffic(serviceName, targetColor);

            // Step 5: 观察
            log.info("Step 5: 观察新版本运行状态");
            boolean healthy = observeHealth(serviceName, Duration.ofMinutes(5));

            if (!healthy) {
                // 回滚: 切回原环境
                log.warn("新版本异常,回滚到 {} 环境", state.getActiveColor());
                switchTraffic(serviceName, state.getActiveColor());
                return BlueGreenResult.rolledBack(serviceName, targetColor);
            }

            // 更新状态
            updateBlueGreenState(serviceName, targetColor, newRevision);
            return BlueGreenResult.success(serviceName, targetColor, newRevision);

        } catch (Exception e) {
            log.error("蓝绿部署失败", e);
            // 确保流量在原环境
            switchTraffic(serviceName, state.getActiveColor());
            return BlueGreenResult.error(serviceName, e.getMessage());
        }
    }

    /**
     * 流量切换 - 秒级完成
     */
    private void switchTraffic(String serviceName, String targetColor) {
        // 更新路由规则,将所有流量指向目标环境
        RoutingRule rule = RoutingRule.builder()
            .serviceName(serviceName)
            .targetEnvironment(targetColor)
            .weight(100)
            .build();

        loadBalancer.updateRouting(rule);
        log.info("流量已切换到 {} 环境", targetColor);
    }
}
```

### 3.6 滚动更新实现

```java
/**
 * 滚动更新控制器
 * 逐批替换旧版本 Pod,保证服务持续可用
 */
public class RollingUpdateController {

    /**
     * 滚动更新配置
     */
    @Data
    @Builder
    public static class RollingUpdateConfig {
        private String serviceName;
        private String newRevision;
        private int maxUnavailable;      // 最大不可用数量 (如 1)
        private int maxSurge;            // 最大超量 (如 1)
        private Duration healthCheckDelay; // 健康检查等待时间
        private int totalReplicas;       // 总副本数
    }

    /**
     * 执行滚动更新
     */
    public RollingUpdateResult executeRollingUpdate(RollingUpdateConfig config) {
        log.info("滚动更新开始: service={}, replicas={}, maxUnavailable={}, maxSurge={}",
            config.getServiceName(), config.getTotalReplicas(),
            config.getMaxUnavailable(), config.getMaxSurge());

        int totalReplicas = config.getTotalReplicas();
        int batchSize = config.getMaxUnavailable() + config.getMaxSurge();
        int currentBatch = 0;

        List<String> updatedPods = new ArrayList<>();

        while (updatedPods.size() < totalReplicas) {
            currentBatch++;
            int remainingCount = totalReplicas - updatedPods.size();
            int thisRoundCount = Math.min(batchSize, remainingCount);

            log.info("滚动更新第 {} 批: 本批更新 {} 个 Pod", currentBatch, thisRoundCount);

            // 启动新 Pod
            List<String> newPods = new ArrayList<>();
            for (int i = 0; i < thisRoundCount; i++) {
                String podId = deployClient.createPod(
                    config.getServiceName(), config.getNewRevision());
                newPods.add(podId);
            }

            // 等待新 Pod 就绪
            for (String podId : newPods) {
                waitForPodReady(podId, Duration.ofMinutes(3));
            }

            // 等待健康检查
            try {
                Thread.sleep(config.getHealthCheckDelay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 停止等量旧 Pod
            List<String> oldPods = getOldVersionPods(
                config.getServiceName(), config.getNewRevision(), thisRoundCount);
            for (String oldPod : oldPods) {
                deployClient.terminatePod(oldPod);
            }

            updatedPods.addAll(newPods);
            log.info("第 {} 批更新完成: 新增 {}, 移除 {}, 总进度 {}/{}",
                currentBatch, newPods.size(), oldPods.size(),
                updatedPods.size(), totalReplicas);
        }

        return RollingUpdateResult.success(config.getServiceName(),
            totalReplicas, currentBatch);
    }
}
```

### 3.7 质量门禁体系

```java
/**
 * 质量门禁框架
 * 在 Pipeline 各阶段设置检查点,阻断不合格的代码
 */
public class QualityGateFramework {

    /**
     * 质量门禁规则
     */
    @Data
    @Builder
    public static class QualityGateRule {
        private String ruleId;
        private String ruleName;
        private GateType gateType;        // BUILD / TEST / SECURITY / REVIEW
        private String expression;         // 规则表达式
        private double threshold;          // 阈值
        private GateAction action;         // BLOCK / WARN / INFO
        private boolean canSkip;           // 是否可跳过 (需审计)
    }

    public enum GateType {
        BUILD, TEST, SECURITY, REVIEW, PERFORMANCE
    }

    public enum GateAction {
        BLOCK,  // 阻断 Pipeline
        WARN,   // 警告但不阻断
        INFO    // 仅记录
    }

    /**
     * 质量门禁检查器
     */
    public static class QualityGateChecker {

        private final List<QualityGateRule> rules;

        public QualityGateChecker(List<QualityGateRule> rules) {
            this.rules = rules;
        }

        /**
         * 执行质量门禁检查
         */
        public QualityGateResult check(PipelineContext context) {
            QualityGateResult result = new QualityGateResult();

            for (QualityGateRule rule : rules) {
                GateCheckResult checkResult = evaluateRule(rule, context);
                result.addCheckResult(checkResult);

                if (!checkResult.isPassed() && rule.getAction() == GateAction.BLOCK) {
                    log.error("质量门禁阻断: rule={}, reason={}",
                        rule.getRuleName(), checkResult.getMessage());

                    // 检查是否有跳过审批
                    if (rule.isCanSkip() && hasSkipApproval(context, rule.getRuleId())) {
                        log.warn("质量门禁已跳过(有审批): rule={}", rule.getRuleName());
                        // 记录跳过审计日志
                        auditLogger.logSkip(context.getPipelineId(), rule.getRuleId(),
                            context.getOperator());
                        checkResult.setSkipped(true);
                    } else {
                        result.setBlocked(true);
                        result.setBlockedByRule(rule.getRuleName());
                    }
                }
            }

            return result;
        }

        private GateCheckResult evaluateRule(QualityGateRule rule, PipelineContext context) {
            switch (rule.getGateType()) {
                case TEST:
                    return checkTestCoverage(rule, context);
                case SECURITY:
                    return checkSecurityScan(rule, context);
                case REVIEW:
                    return checkReviewApproval(rule, context);
                case PERFORMANCE:
                    return checkPerformanceBaseline(rule, context);
                default:
                    return GateCheckResult.passed(rule.getRuleId());
            }
        }

        private GateCheckResult checkTestCoverage(QualityGateRule rule,
                                                    PipelineContext context) {
            TestReport report = context.getTestReport();
            if (report == null) {
                return GateCheckResult.failed(rule.getRuleId(), "测试报告不存在");
            }

            double coverage = report.getLineCoverage();
            if (coverage < rule.getThreshold()) {
                return GateCheckResult.failed(rule.getRuleId(),
                    String.format("测试覆盖率 %.1f%% < 阈值 %.1f%%",
                        coverage * 100, rule.getThreshold() * 100));
            }

            return GateCheckResult.passed(rule.getRuleId());
        }

        private GateCheckResult checkSecurityScan(QualityGateRule rule,
                                                    PipelineContext context) {
            SecurityScanResult scanResult = context.getSecurityScanResult();
            if (scanResult == null) {
                return GateCheckResult.failed(rule.getRuleId(), "安全扫描结果不存在");
            }

            long criticalCount = scanResult.getCriticalVulnerabilities();
            if (criticalCount > rule.getThreshold()) {
                return GateCheckResult.failed(rule.getRuleId(),
                    String.format("发现 %d 个严重安全漏洞(阈值: %.0f)",
                        criticalCount, rule.getThreshold()));
            }

            return GateCheckResult.passed(rule.getRuleId());
        }
    }

    /**
     * 跳过审批管理
     * 某些门禁可以在特定审批后跳过,但需要审计记录
     */
    public static class SkipApprovalManager {

        /**
         * 申请跳过门禁
         */
        public SkipApproval requestSkip(String pipelineId, String ruleId,
                                         String applicant, String reason) {
            SkipApproval approval = SkipApproval.builder()
                .pipelineId(pipelineId)
                .ruleId(ruleId)
                .applicant(applicant)
                .reason(reason)
                .status(ApprovalStatus.PENDING)
                .createTime(new Date())
                .build();

            // 保存申请
            approvalDao.save(approval);

            // 通知审批人
            List<String> approvers = getApprovers(ruleId);
            notifyApprovers(approvers, approval);

            return approval;
        }

        /**
         * 审计: 定期检查跳过记录
         */
        @Scheduled(cron = "0 0 9 * * MON")
        public void weeklySkipAudit() {
            List<SkipApproval> weeklySkips = approvalDao.findByDateRange(
                LocalDate.now().minusWeeks(1), LocalDate.now());

            if (!weeklySkips.isEmpty()) {
                AuditReport report = generateAuditReport(weeklySkips);
                notifyAuditCommittee(report);
            }
        }
    }
}
```

### 3.8 Pipeline 自动化配置

```java
/**
 * Pipeline 自动化配置
 * 通过配置模板快速创建和管理 Pipeline
 */
public class PipelineAutomation {

    /**
     * Pipeline 配置模板
     */
    @Data
    @Builder
    public static class PipelineTemplate {
        private String templateId;
        private String templateName;
        private List<StageTemplate> stages;
        private Map<String, String> defaultVariables;
        private BuildTemplateConfig buildConfig;
    }

    /**
     * 构建模板绑定
     * 不同项目类型绑定不同的构建模板
     */
    public static class BuildTemplateBinding {

        private static final Map<String, BuildTemplateConfig> TEMPLATE_MAP = Map.of(
            "SPRING_BOOT", BuildTemplateConfig.builder()
                .buildCommand("mvn clean package -DskipTests")
                .testCommand("mvn test")
                .dockerfileTemplate("Dockerfile.springboot")
                .jdkVersion("17")
                .build(),

            "SPRING_CLOUD", BuildTemplateConfig.builder()
                .buildCommand("mvn clean package -DskipTests -pl ${module}")
                .testCommand("mvn test -pl ${module}")
                .dockerfileTemplate("Dockerfile.springcloud")
                .jdkVersion("17")
                .build(),

            "GRADLE_PROJECT", BuildTemplateConfig.builder()
                .buildCommand("./gradlew build -x test")
                .testCommand("./gradlew test")
                .dockerfileTemplate("Dockerfile.gradle")
                .jdkVersion("17")
                .build()
        );

        public BuildTemplateConfig resolveTemplate(String projectType) {
            BuildTemplateConfig config = TEMPLATE_MAP.get(projectType);
            if (config == null) {
                throw new IllegalArgumentException("未知项目类型: " + projectType);
            }
            return config;
        }
    }

    /**
     * 自动化发布 - 集成金丝雀分析
     */
    public static class AutomatedReleaseOrchestrator {

        private final CanaryReleaseController canaryController;
        private final QualityGateFramework qualityGate;

        /**
         * 端到端自动化发布流程
         */
        public ReleaseResult automatedRelease(ReleaseRequest request) {
            log.info("自动化发布开始: service={}, version={}",
                request.getServiceName(), request.getVersion());

            // Phase 1: 构建与测试
            PipelineResult buildResult = runBuildPipeline(request);
            if (buildResult.getStatus() != PipelineStatus.SUCCESS) {
                return ReleaseResult.failed("构建失败: " + buildResult.getFailedStage());
            }

            // Phase 2: 质量门禁
            QualityGateResult gateResult = qualityGate.new QualityGateChecker(
                getRules(request.getServiceName())).check(buildResult.getContext());
            if (gateResult.isBlocked()) {
                return ReleaseResult.failed("质量门禁阻断: " + gateResult.getBlockedByRule());
            }

            // Phase 3: 金丝雀发布
            CanaryReleaseController.CanaryConfig canaryConfig =
                CanaryReleaseController.CanaryConfig.builder()
                    .serviceName(request.getServiceName())
                    .group(request.getTargetGroup())
                    .newRevision(buildResult.getContext().getBuildArtifact())
                    .trafficSteps(Arrays.asList(5, 20, 50, 100))
                    .observationPeriod(Duration.ofMinutes(3))
                    .threshold(CanaryReleaseController.CanaryMetricsThreshold.builder()
                        .min2xxRatio(0.995)
                        .maxP99LatencyMs(500)
                        .maxErrorRate(0.01)
                        .build())
                    .autoRollback(true)
                    .build();

            CanaryReleaseResult canaryResult = canaryController.executeCanaryRelease(canaryConfig);

            if (canaryResult.getStatus() == CanaryStatus.SUCCESS) {
                return ReleaseResult.success(request.getServiceName(), request.getVersion());
            } else {
                return ReleaseResult.failed("金丝雀验证失败: " + canaryResult.getErrorMessage());
            }
        }
    }
}
```

---

## 四、异常处理

### 4.1 发布异常处理

```java
/**
 * 发布异常处理框架
 */
public class ReleaseExceptionHandler {

    /**
     * 异常分级与处理策略
     */
    public enum ReleaseExceptionLevel {
        INFRASTRUCTURE("基础设施异常", "镜像拉取失败、Pod调度失败", true),
        APPLICATION("应用异常", "启动失败、健康检查不通过", true),
        METRICS("指标异常", "2XX比例下降、延迟升高", true),
        BUSINESS("业务异常", "业务逻辑错误、数据异常", false);

        private final String description;
        private final String examples;
        private final boolean autoRollback;

        ReleaseExceptionLevel(String desc, String examples, boolean autoRollback) {
            this.description = desc;
            this.examples = examples;
            this.autoRollback = autoRollback;
        }
    }

    /**
     * 统一异常处理入口
     */
    public void handleReleaseException(String serviceName, String revision,
                                        Exception e, ReleaseExceptionLevel level) {
        log.error("发布异常: service={}, revision={}, level={}, error={}",
            serviceName, revision, level.description, e.getMessage());

        // 1. 记录异常事件
        releaseEventDao.save(ReleaseEvent.builder()
            .serviceName(serviceName)
            .revision(revision)
            .eventType("EXCEPTION")
            .level(level.name())
            .message(e.getMessage())
            .timestamp(new Date())
            .build());

        // 2. 根据级别决定是否自动回滚
        if (level.autoRollback) {
            log.warn("触发自动回滚: service={}", serviceName);
            rollbackService.rollbackToLastStable(serviceName);
        }

        // 3. 发送告警
        alertService.sendAlert(
            level.autoRollback ? AlertLevel.P1 : AlertLevel.P2,
            String.format("发布异常 [%s]: %s", level.description, serviceName),
            e.getMessage()
        );
    }

    /**
     * 回滚服务
     */
    public static class RollbackService {

        /**
         * 回滚到上一个稳定版本
         */
        public void rollbackToLastStable(String serviceName) {
            // 获取上一个稳定版本
            String lastStableRevision = deployClient.getLastStableRevision(serviceName);
            if (lastStableRevision == null) {
                throw new RollbackException("无可用的稳定版本: " + serviceName);
            }

            log.info("执行回滚: service={}, targetRevision={}", serviceName, lastStableRevision);

            // 直接切换到稳定版本 (蓝绿方式,秒级)
            deployClient.switchToRevision(serviceName, lastStableRevision);

            log.info("回滚完成: service={}, revision={}", serviceName, lastStableRevision);
        }
    }
}
```

### 4.2 Pipeline 失败处理

```java
/**
 * Pipeline 失败处理与重试
 */
public class PipelineFailureHandler {

    /**
     * 失败阶段智能重试
     */
    public PipelineResult retryFromFailedStage(String pipelineId) {
        PipelineExecution execution = executionDao.findById(pipelineId);
        String failedStage = execution.getFailedStage();

        if (failedStage == null) {
            throw new IllegalStateException("Pipeline 未失败,无需重试");
        }

        // 从失败阶段开始重试,跳过已成功的阶段
        log.info("从失败阶段重试: pipelineId={}, failedStage={}",
            pipelineId, failedStage);

        PipelineExecutor executor = new PipelineExecutor(execution.getConfig());
        return executor.executeFromStage(failedStage, execution.getContext());
    }

    /**
     * 构建缓存 - 加速重试
     * 利用增量构建减少重试时间
     */
    public static class BuildCacheManager {

        /**
         * 保存构建缓存
         */
        public void saveBuildCache(String projectId, String commitId, Path workspace) {
            // 保存 Maven/Gradle 本地仓库
            Path m2Cache = workspace.resolve(".m2/repository");
            Path gradleCache = workspace.resolve(".gradle/caches");

            if (Files.exists(m2Cache)) {
                cacheStorage.save(projectId + "/m2", m2Cache);
            }
            if (Files.exists(gradleCache)) {
                cacheStorage.save(projectId + "/gradle", gradleCache);
            }
        }

        /**
         * 恢复构建缓存
         */
        public void restoreBuildCache(String projectId, Path workspace) {
            cacheStorage.restore(projectId + "/m2",
                workspace.resolve(".m2/repository"));
            cacheStorage.restore(projectId + "/gradle",
                workspace.resolve(".gradle/caches"));
        }
    }
}
```

---

## 五、性能优化

### 5.1 构建加速

```java
/**
 * 构建性能优化
 */
public class BuildPerformanceOptimization {

    /**
     * 并行构建 - 多模块并行编译
     */
    public static class ParallelBuildOptimizer {

        public String optimizeMavenCommand(String baseCommand, int cpuCores) {
            // Maven 并行构建参数
            int threadCount = Math.max(1, cpuCores - 1);
            return baseCommand + " -T " + threadCount + "C"; // 每核1线程
        }
    }

    /**
     * 增量构建 - 只构建变更模块
     */
    public static class IncrementalBuildOptimizer {

        /**
         * 分析变更文件,确定需要构建的模块
         */
        public Set<String> determineAffectedModules(List<String> changedFiles,
                                                      ModuleDependencyGraph graph) {
            Set<String> affectedModules = new LinkedHashSet<>();

            for (String file : changedFiles) {
                String module = extractModule(file);
                if (module != null) {
                    affectedModules.add(module);
                    // 添加依赖该模块的下游模块
                    affectedModules.addAll(graph.getDownstreamModules(module));
                }
            }

            return affectedModules;
        }

        /**
         * 生成增量构建命令
         */
        public String buildIncrementalCommand(Set<String> modules) {
            String moduleList = String.join(",", modules);
            return "mvn clean package -pl " + moduleList + " -am -DskipTests";
        }
    }

    /**
     * Docker 镜像分层缓存
     */
    public static final String OPTIMIZED_DOCKERFILE =
        "# 第一层: 基础运行环境 (很少变化)\n" +
        "FROM openjdk:17-slim AS base\n" +
        "WORKDIR /app\n" +
        "\n" +
        "# 第二层: 依赖 (仅 pom 变化时重建)\n" +
        "FROM base AS dependencies\n" +
        "COPY pom.xml .\n" +
        "RUN mvn dependency:go-offline\n" +
        "\n" +
        "# 第三层: 应用代码 (每次构建都变化)\n" +
        "FROM dependencies AS build\n" +
        "COPY src ./src\n" +
        "RUN mvn package -DskipTests\n" +
        "\n" +
        "# 最终镜像: 只包含运行时\n" +
        "FROM base AS runtime\n" +
        "COPY --from=build /app/target/*.jar app.jar\n" +
        "EXPOSE 8080\n" +
        "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]";
}
```

### 5.2 发布效率优化

```java
/**
 * 发布效率优化
 */
public class ReleaseEfficiencyOptimization {

    /**
     * 自动扩缩容 - 发布期间资源管理
     */
    public static class AutoScalingDuringRelease {

        /**
         * 发布前预扩容
         * 确保金丝雀期间有足够的资源
         */
        public void preScaleForRelease(String serviceName, int additionalReplicas) {
            int currentReplicas = deployClient.getCurrentReplicas(serviceName);
            int targetReplicas = currentReplicas + additionalReplicas;

            deployClient.scaleReplicas(serviceName, targetReplicas);
            log.info("发布预扩容: service={}, {} -> {}",
                serviceName, currentReplicas, targetReplicas);
        }

        /**
         * 发布完成后缩容
         */
        public void postScaleAfterRelease(String serviceName) {
            int optimalReplicas = calculateOptimalReplicas(serviceName);
            deployClient.scaleReplicas(serviceName, optimalReplicas);
            log.info("发布后缩容: service={}, target={}",
                serviceName, optimalReplicas);
        }
    }

    /**
     * 发布窗口管理
     * 避免在高峰期发布
     */
    public static class ReleaseWindowManager {

        /**
         * 检查当前是否在允许发布的时间窗口内
         */
        public boolean isInReleaseWindow() {
            LocalTime now = LocalTime.now();
            DayOfWeek day = LocalDate.now().getDayOfWeek();

            // 工作日 10:00-12:00, 14:00-18:00 允许发布
            if (day.getValue() >= 1 && day.getValue() <= 5) {
                return (now.isAfter(LocalTime.of(10, 0)) && now.isBefore(LocalTime.of(12, 0)))
                    || (now.isAfter(LocalTime.of(14, 0)) && now.isBefore(LocalTime.of(18, 0)));
            }

            // 周末不建议发布
            return false;
        }
    }
}
```

---

## 六、最佳实践

### 6.1 Pipeline 设计原则

| 原则 | 说明 |
|------|------|
| **快速反馈** | Push Pipeline 5分钟内完成,PR Pipeline 15分钟内完成 |
| **可靠性** | 非确定性测试(Flaky Test)应隔离或修复,不允许因环境问题阻断 |
| **可观测性** | 每个阶段有明确的日志、指标和产物 |
| **可复现** | 相同输入产生相同输出(构建确定性) |
| **渐进式门禁** | Push < PR < Integration < Release,逐级增强检查 |

### 6.2 发布策略选择指南

| 场景 | 推荐策略 | 说明 |
|------|---------|------|
| 常规功能迭代 | 金丝雀发布 | 5% -> 20% -> 50% -> 100% |
| 核心链路变更 | 金丝雀 + 影子测试 | 先影子验证,再金丝雀 |
| 数据库 Schema 变更 | 蓝绿部署 | 确保秒级回滚能力 |
| 基础框架升级 | 灰度 + 长观察期 | 每阶段观察时间加倍 |
| 无状态轻量服务 | 滚动更新 | 简单高效 |
| A/B 实验 | A/B 测试 | 按用户特征分流 |

### 6.3 上线 Checklist

1. **代码质量**: 静态扫描无 BLOCKER 问题，CR 已通过
2. **测试覆盖**: 单测覆盖率 >= 80%，核心逻辑 >= 90%
3. **安全扫描**: 无高危/严重安全漏洞
4. **兼容性**: API 向前兼容，数据库变更向前兼容
5. **监控就绪**: 关键指标告警已配置，大盘已更新
6. **回滚方案**: 明确回滚步骤，验证回滚可行性
7. **发布窗口**: 在允许的发布时间窗口内
8. **通知协调**: 通知相关上下游团队
9. **灰度策略**: 金丝雀配置合理，观察指标明确
10. **值班安排**: 发布期间有人值班，出现问题可快速响应
