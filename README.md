# 分布式系统学习资料总览

这个目录用于整理分布式系统相关的论文、笔记、配图，以及按统一风格生成的中文讲解稿。

当前内容大致分为四类：

- `papers/`：原始论文 PDF、部分抽取文本、索引文件
- 根目录下的 `*-raft-teacher-style讲解.md`：已经整理好的中文通俗讲解稿
- `raft算法.txt` 与若干配图：围绕 Raft / 一致性 / 多数派 / 日志复制的学习笔记
- `.trae/skills/raft-teacher-style/`：用于生成“老师风格”论文讲解的 skill

---

## 目录结构

### 1. `papers/`

主要用于存放原始论文资料：

- 论文 PDF
- 部分 PDF 的抽取文本 `*.extracted.txt`
- 论文下载索引 `index.csv` / `index.json`
- 个别补充说明文档

说明：

- `*.extracted.txt` 是从 PDF 中抽出来的中间文本，主要用于后续做讲解整理，不是最终学习稿
- 如果只想读整理后的内容，优先看根目录下对应的 `*-raft-teacher-style讲解.md`

### 2. 根目录讲解稿

目前已经整理完成的讲解稿包括：

- `分布式时钟领域大作-raft-teacher-style讲解.md`
- `RocksDB-raft-teacher-style讲解.md`
- `TiDB-基于raft的高性能写数据库-raft-teacher-style讲解.md`
- `vertical-paxos-raft-teacher-style讲解.md`
- `lsmtree-raft-teacher-style讲解.md`

这些文件的特点是：

- 用中文重写论文主线
- 强调问题背景、核心矛盾、关键机制、bad case、工程意义和 Q&A
- 目标不是“论文摘要”，而是“尽量让学生看完以后真的能懂”

### 3. Raft 相关学习资料

这部分主要包括：

- `raft算法.txt`
- 多张配图，如 `多数派原则.png`、`日志同步请求.png`、`角色定义与切换.png` 等

用途：

- 用于梳理 Raft 的主线理解
- 形成自己的讲解风格和问题拆解方式

### 4. Skill

路径：

- `.trae/skills/raft-teacher-style/SKILL.md`

用途：

- 把论文讲成“一个会带学生的老师在面对面讲课”
- 适合分布式系统论文的通俗、清晰、全面讲解

---

## 已整理论文导航

### 理论基础

- `分布式时钟领域大作-raft-teacher-style讲解.md`
  - 对应 Lamport 的《Time, Clocks, and the Ordering of Events in a Distributed System》
  - 重点：`happened-before`、逻辑时钟、偏序、全序、物理时钟

### 存储结构

- `lsmtree-raft-teacher-style讲解.md`
  - 对应《The Log-Structured Merge-Tree》
  - 重点：为什么高更新索引需要 LSM、`defer + batch + merge` 的思想

- `RocksDB-raft-teacher-style讲解.md`
  - 对应《RocksDB: Evolution of Development Priorities in a Key-value Store Serving Large-scale Applications》
  - 重点：LSM 在工业系统里的演化、写放大/空间放大/CPU 的取舍

### 一致性与复制

- `vertical-paxos-raft-teacher-style讲解.md`
  - 对应《Vertical Paxos and Primary-Backup Replication》
  - 重点：重配置、读写 quorum、configuration master、primary-backup 与 Paxos 的关系

### 现代系统实践

- `TiDB-基于raft的高性能写数据库-raft-teacher-style讲解.md`
  - 对应《TiDB: A Raft-based HTAP Database》
  - 重点：Raft learner、TiKV/TiFlash、HTAP、事务与分析隔离

---

## 推荐阅读顺序

如果你更想补底层抽象：

1. `分布式时钟领域大作-raft-teacher-style讲解.md`
2. `vertical-paxos-raft-teacher-style讲解.md`

如果你更想补存储结构：

1. `lsmtree-raft-teacher-style讲解.md`
2. `RocksDB-raft-teacher-style讲解.md`

如果你更想看现代工程系统怎么把这些思想落地：

1. `TiDB-基于raft的高性能写数据库-raft-teacher-style讲解.md`

---

## 使用建议

推荐的阅读方式：

- 先看讲解稿，建立主线理解
- 再回到 `papers/` 里的原始 PDF 对照细节
- 如果某篇论文涉及 Raft / Paxos / LSM 等基础概念，先把对应基础讲义读完再回原文

不推荐的方式：

- 直接从原始 PDF 一页页硬啃
- 在没有主线的情况下同时并行读很多篇一致性论文

---

## 后续可继续补充

这个目录后续可以继续扩展为：

- 每篇论文一份 `raft-teacher-style` 讲义
- 一个总索引表，记录“论文标题 / 类别 / 是否已整理 / 对应讲义文件”
- 一个分主题阅读路线，例如：
  - 分布式时钟
  - 共识与复制
  - 存储引擎与 LSM
  - 工业级分布式数据库

---

## 备注

如果后续继续往这个目录里补论文讲解，建议统一命名格式：

- 原始论文：放在 `papers/`
- 整理后的讲义：`论文名-raft-teacher-style讲解.md`

这样后面目录会比较清晰，也方便统一检索和维护。
