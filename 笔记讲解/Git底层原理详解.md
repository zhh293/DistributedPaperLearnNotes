# Git 底层原理详解

> 读完这篇文档，你会彻底明白 Git 不是魔法，它只是一个**精心设计的文件系统**。

---

## 一、Git 到底是什么？

很多人把 Git 当成一个"版本管理工具"，但更准确的说法是：

> **Git 是一个以内容寻址的文件系统，上面套了一层版本控制的壳。**

所谓"内容寻址"，意思是：**文件的名字就是它内容的哈希值**。你存什么内容，Git 就用这个内容算出一个 SHA-1 哈希（40位十六进制字符串），用这个哈希来找到这个文件。内容变了，哈希就变了，地址就变了。

所有的秘密，都藏在你项目根目录下的 `.git` 文件夹里。

---

## 二、`.git` 目录长什么样？

```
.git/
├── HEAD           # 指针：当前在哪个分支
├── config         # 仓库配置
├── index          # 暂存区（Stage/Index）
├── objects/       # 🌟 核心！所有数据都在这里
│   ├── ab/
│   │   └── cd1234...  # 对象文件（前2位是目录名）
│   ├── pack/      # 打包后的对象
│   └── info/
└── refs/
    ├── heads/     # 本地分支
    │   ├── main
    │   └── dev
    └── tags/      # 标签
```

最重要的两个地方：**`objects/` 目录**和 **`refs/` 目录**。

---

## 三、Git 的四种对象（万物的基础）

Git 只有四种对象，所有功能都建立在这四种对象之上。

### 3.1 Blob —— 文件内容

Blob 就是文件的内容快照，**只存内容，不存文件名**。

```
"hello world\n"
       ↓ SHA-1 哈希
  3b18e512dba79e4c8300dd08aeb37f8e728b8dad
```

存储格式：`blob <内容长度>\0<内容>`，然后 zlib 压缩，存到 `objects/3b/18e512...`。

> 💡 两个不同名字但内容完全相同的文件，在 Git 里只存一份 Blob！这就是 Git 节省空间的秘密之一。

---

### 3.2 Tree —— 目录结构

Tree 对象就是目录的快照，记录了"这个目录下有哪些文件/子目录，以及它们的哈希"。

```
tree 对象内容示例：

100644 blob a8c3f... README.md
100644 blob 3b18e... hello.txt
040000 tree 9d8f2... src/
```

每一行的格式：`权限 类型 哈希值 文件名`

Tree 指向 Blob（文件），也可以指向其他 Tree（子目录）。整个项目目录就是一棵 Tree 的树形结构。

---

### 3.3 Commit —— 提交快照

Commit 对象是你每次 `git commit` 产生的东西，它记录了：

```
tree   9d8f2c4b...     ← 指向这次提交时的根目录 Tree
parent a1b2c3d4...     ← 上一次提交的哈希（第一次提交没有 parent）
author  张三 <z@z.com> 1713000000 +0800
committer 张三 <z@z.com> 1713000000 +0800

feat: 添加登录功能     ← 提交信息
```

**Commit 不存储"变化了什么"，而是存储"这一刻整个项目长什么样"（完整快照）。**

所谓的"diff"是 Git 在展示时临时计算出来的，不是存储的内容。

---

### 3.4 Tag —— 标签对象

附注标签（`git tag -a`）会创建一个 Tag 对象，指向某个 Commit，并附带标签信息、作者、时间等。轻量标签只是一个引用，不创建对象。

---

### 四种对象的关系图

```
Tag
 └──→ Commit ──→ Tree ──→ Blob (文件内容)
        ↑            └──→ Tree ──→ Blob
        │                     └──→ Blob
      parent
        │
      Commit ──→ Tree ──→ ...
```

**整个 Git 历史，就是一张由这四种对象组成的有向无环图（DAG）。**

---

## 四、引用（Refs）—— 给哈希起个人类能记住的名字

40位哈希太难记了，Git 用"引用"来解决这个问题。

引用本质上就是一个**文本文件，里面存着一个哈希值**。

```bash
cat .git/refs/heads/main
# 输出：a1b2c3d4e5f6...（某个 Commit 的哈希）
```

- **分支**：`.git/refs/heads/main` → 指向该分支最新的 Commit
- **远程分支**：`.git/refs/remotes/origin/main`
- **标签**：`.git/refs/tags/v1.0`
- **HEAD**：`.git/HEAD` → 特殊引用，指向当前所在分支

```bash
cat .git/HEAD
# 输出：ref: refs/heads/main
# 意思是：HEAD 指向 main 分支，main 分支指向某个 Commit
```

---

## 五、暂存区（Index）—— 被忽视的重要角色

暂存区是 `.git/index` 文件，是一个**二进制格式的目录树**，记录了"下次提交时，项目应该长什么样"。

可以把它理解为：**准备好的下一次快照**。

```
工作区（你的文件）  →  暂存区（index）  →  仓库（objects）
     git add ↗              git commit ↗
```

---

## 六、命令底层解析

现在来看每个命令到底干了什么。

---

### `git init`

```bash
git init
```

**底层做了什么：**

在当前目录创建 `.git` 文件夹，初始化以下内容：

```
.git/
├── HEAD          → 写入 "ref: refs/heads/master"
├── config        → 写入默认配置
├── objects/      → 创建空目录
└── refs/
    ├── heads/    → 创建空目录
    └── tags/     → 创建空目录
```

此时 `objects/` 是空的，没有任何对象，`refs/heads/` 也是空的，连 master 分支文件都还不存在（要等第一次 commit 才会创建）。

---

### `git add`

```bash
git add hello.txt
```

**底层做了什么（分三步）：**

**第一步：** 读取 `hello.txt` 的内容，计算 SHA-1 哈希。

**第二步：** 把内容压缩后写入 `objects/` 目录，创建一个 **Blob 对象**。

```
objects/
└── 3b/
    └── 18e512dba79e4c8300dd08aeb37f8e728b8dad
```

**第三步：** 更新 `.git/index`（暂存区），记录"hello.txt 这个文件名对应哈希 3b18e5..."。

```
index 里新增一条记录：
  100644  3b18e512...  0  hello.txt
```

> 💡 注意：`git add` **只创建 Blob 对象，不创建 Tree 和 Commit**。Tree 和 Commit 是 `git commit` 时才创建的。

---

### `git commit`

```bash
git commit -m "feat: 初始化项目"
```

**底层做了什么（分三步）：**

**第一步：** 根据当前暂存区（index）的内容，递归创建 **Tree 对象**。

```
# 根目录 Tree
100644 blob 3b18e5... hello.txt
100644 blob a8c3f2... README.md
040000 tree 9d8f2c... src/

# src/ 子目录 Tree
100644 blob b4e7a1... main.js
```

**第二步：** 创建 **Commit 对象**，内容包括：
- 指向刚创建的根 Tree 的哈希
- 指向上一个 Commit 的哈希（parent，第一次没有）
- 作者、时间、提交信息

**第三步：** 更新当前分支引用，把分支文件里的哈希改成新 Commit 的哈希。

```bash
# 提交前
cat .git/refs/heads/main
# a1b2c3d4...（旧 Commit）

# 提交后
cat .git/refs/heads/main
# f9e8d7c6...（新 Commit）
```

整个过程的对象变化：

```
git commit 之前：
  objects/ 里只有 Blob

git commit 之后：
  objects/ 里新增了 Tree 对象 + Commit 对象
  .git/refs/heads/main 指向新的 Commit
```

---

### `git branch`

```bash
git branch dev
```

**底层做了什么：**

极其简单——**在 `.git/refs/heads/` 目录下创建一个新文件 `dev`，内容是当前 Commit 的哈希**。

```bash
# 等价于：
cat .git/refs/heads/main > .git/refs/heads/dev
```

就这一件事。创建分支的代价几乎为零，这就是为什么 Git 的分支比其他版本控制系统快得多。

```
.git/refs/heads/
├── main   → a1b2c3d4...
└── dev    → a1b2c3d4...  ← 新建，指向同一个 Commit
```

---

### `git checkout`

```bash
git checkout dev
```

**底层做了什么（分两步）：**

**第一步：** 修改 `.git/HEAD` 文件，让它指向新分支。

```bash
# 之前
cat .git/HEAD
# ref: refs/heads/main

# 之后
cat .git/HEAD
# ref: refs/heads/dev
```

**第二步：** 根据 `dev` 分支指向的 Commit，找到对应的 Tree，把 Tree 里描述的文件状态**还原到工作区**，同时更新暂存区（index）。

如果 `dev` 和 `main` 指向同一个 Commit，工作区文件不会有任何变化，只是 HEAD 换了指向。

> 💡 `git checkout -b dev` = `git branch dev` + `git checkout dev`，即先建分支再切过去。

---

### `git merge`

```bash
# 在 main 分支上
git merge dev
```

**底层做了什么：**

Git 先找到 `main` 和 `dev` 的**共同祖先 Commit**（merge base），然后：

**情况一：Fast-forward（快进合并）**

如果 `dev` 是在 `main` 的基础上直接往前走的，`main` 没有新提交：

```
main: A → B
dev:  A → B → C → D
```

Git 直接把 `main` 的引用文件改成指向 D，不创建新 Commit。

```bash
# 等价于：
echo "D的哈希" > .git/refs/heads/main
```

**情况二：三方合并（Three-way merge）**

两个分支都有新提交：

```
      A → B → C  (main)
       \
        D → E    (dev)
```

Git 以 A（共同祖先）为基准，对比 C 和 E 的差异，合并后创建一个新的 **Merge Commit M**，这个 Commit 有**两个 parent**（C 和 E）。

```
      A → B → C → M  (main)
       \         /
        D → E ──
```

---

### `git rebase`

```bash
# 在 dev 分支上
git rebase main
```

**底层做了什么：**

Rebase 的本质是**重新播放提交**，不是移动提交。

```
# rebase 前
main: A → B → C
dev:  A → D → E

# rebase 后
main: A → B → C
dev:  A → B → C → D' → E'
```

D' 和 E' 是**全新的 Commit 对象**（哈希不同），内容是把 D、E 的变更重新应用在 C 之上。原来的 D、E 对象还在 `objects/` 里，只是没有引用指向它们了（最终会被 GC 清理）。

> ⚠️ 这就是为什么 **rebase 会改写历史**，不要对已推送到远程的分支做 rebase。

---

### `git reset`

```bash
git reset --soft HEAD~1   # 软重置
git reset --mixed HEAD~1  # 混合重置（默认）
git reset --hard HEAD~1   # 硬重置
```

三种模式的区别，本质是"回退到哪一层"：

```
工作区文件  ←  暂存区（index）  ←  HEAD（Commit）
```

| 模式 | HEAD 移动 | 暂存区变化 | 工作区变化 |
|------|-----------|-----------|-----------|
| `--soft` | ✅ 回退 | ❌ 不变 | ❌ 不变 |
| `--mixed` | ✅ 回退 | ✅ 回退 | ❌ 不变 |
| `--hard` | ✅ 回退 | ✅ 回退 | ✅ 回退 |

**底层做了什么：**

三种模式都会把当前分支的引用文件改成指向目标 Commit（`HEAD~1` 就是上一个 Commit）。区别在于是否同步更新 index 和工作区文件。

---

### `git stash`

```bash
git stash
```

**底层做了什么：**

把当前工作区和暂存区的变更打包成两个特殊的 Commit 对象（一个存暂存区状态，一个存工作区状态），然后把引用存到 `.git/refs/stash`，最后把工作区恢复到 HEAD 状态。

本质上 stash 就是一个**临时的、不在分支上的 Commit**。

---

### `git log`

```bash
git log
```

**底层做了什么：**

从 HEAD 指向的 Commit 开始，沿着每个 Commit 的 `parent` 指针一路往前遍历，把每个 Commit 对象的信息格式化输出。就是一次**图的遍历**。

---

## 七、一张图总结所有关系

```
工作区                暂存区(index)           对象库(objects)
─────────────────────────────────────────────────────────────

hello.txt  ──git add──→  [hello.txt: 3b18e5]  ──→  Blob(3b18e5)
                                                      │
                         ──git commit──→  Tree(9d8f2c)─┘
                                              │
                                          Commit(f9e8d7)
                                              │
                                          parent: a1b2c3
                                              │
                                          Commit(a1b2c3)
                                              ...

HEAD ──→ ref: refs/heads/main
                  │
                  ↓
         .git/refs/heads/main ──→ f9e8d7（最新 Commit）
```

---

## 八、几个常见问题的底层解答

**Q：为什么 Git 切换分支这么快？**

因为切换分支只是改了 `.git/HEAD` 这一个文件的内容（几十个字节），然后根据目标 Commit 的 Tree 更新工作区文件。整个过程不需要网络，不需要复制仓库，只是文件读写。

**Q：为什么 Git 的存储这么高效？**

两个原因：① 相同内容只存一份 Blob（内容寻址去重）；② 对象文件用 zlib 压缩存储，定期 `git gc` 还会把松散对象打包成 packfile，用 delta 压缩进一步减小体积。

**Q：`git commit --amend` 是修改了上一个 Commit 吗？**

不是。它创建了一个**全新的 Commit 对象**（哈希不同），然后把当前分支引用指向新 Commit。旧 Commit 对象还在 `objects/` 里，只是没有引用指向它了。所以 amend 也是在改写历史。

**Q：删除分支会丢失提交吗？**

不会立刻丢失。`git branch -d dev` 只是删除了 `.git/refs/heads/dev` 这个文件，Commit 对象还在 `objects/` 里。只有当 Git 执行垃圾回收（`git gc`）时，没有任何引用指向的对象才会被真正删除。所以误删分支后，在 GC 之前都可以用 `git reflog` 找回来。

**Q：`git reflog` 为什么能找回"丢失"的提交？**

因为 Git 会把每次 HEAD 的变动记录在 `.git/logs/HEAD` 里，这就是 reflog。即使分支被删了，只要 Commit 对象还在 `objects/` 里，通过 reflog 找到哈希就能恢复。

---

## 九、动手验证（可选）

想亲眼看到这些对象，可以在任意 Git 仓库里执行：

```bash
# 查看某个文件对应的 Blob 哈希
git hash-object README.md

# 查看一个对象的类型
git cat-file -t a1b2c3d4

# 查看一个对象的内容
git cat-file -p a1b2c3d4

# 查看当前 HEAD 指向
cat .git/HEAD

# 查看 main 分支指向的 Commit
cat .git/refs/heads/main

# 查看暂存区内容
git ls-files --stage

# 查看 reflog
git reflog
```

---

*文档整理：CatDesk · 2026-04-15*
