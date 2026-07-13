# Maven 全面说明书

## 1. 这份文档是写给谁的

这是一份写给 Maven 初学者的说明书，尽量使用直白、容易理解的话，把 Maven 的核心概念、实际表现形式、层次结构、`pom.xml` 中的常见标签、`mvn` 命令的作用，以及 Maven 在背后到底做了什么，系统地讲清楚。

如果你现在对下面这些问题都不清楚，这份文档就是为你准备的：

- Maven 是什么，解决什么问题
- Maven 在实际项目里长什么样
- 为什么 Java 项目里总会看到 `pom.xml`
- `pom.xml` 里的标签是什么意思
- `mvn compile`、`mvn package`、`mvn install`、`mvn deploy` 到底在做什么
- 仓库、本地仓库、远程仓库、私服、模板这些词是什么意思
- 生命周期、阶段、插件之间是什么关系
- 大项目里的父工程、子工程、多模块又是什么

---

## 2. 先用一句大白话理解 Maven

**Maven 是一个帮助 Java 项目“下载材料、按照固定流程加工、最后产生成品”的工具。**

如果把开发一个 Java 项目想成做饭，那么 Maven 大致相当于：

- 帮你列出要买哪些菜：项目依赖哪些第三方库
- 帮你去仓库拿菜：自动下载 jar 包
- 帮你按步骤做菜：编译、测试、打包、安装、发布
- 帮你记录菜谱：这些规则大多写在 `pom.xml` 中

所以 Maven 不只是“下载 jar 包的工具”，它更是：

- 构建工具
- 依赖管理工具
- 项目管理工具
- 规范化工具

---

## 3. Maven 在实际中会以什么形式出现在你眼前

学习 Maven 时，不要把它想得太抽象。你在日常开发中真正会看到的是下面这些东西。

### 3.1 `pom.xml`

这是 Maven 项目的核心配置文件，通常放在项目根目录。

一个典型 Maven 项目大概长这样：

```text
my-project/
  pom.xml
  src/
  target/
```

只要你看到一个 Java 项目根目录下有 `pom.xml`，通常就说明它是一个 Maven 项目。

### 3.2 `mvn` 命令

你在终端里最常看到的是这类命令：

```bash
mvn compile
mvn test
mvn package
mvn clean install
```

这里的 `mvn` 就是 Maven 的命令行程序。

### 3.3 标准目录结构

典型 Maven 项目目录：

```text
my-project/
  pom.xml
  src/
    main/
      java/
      resources/
    test/
      java/
      resources/
  target/
```

各目录常见作用如下：

- `src/main/java`：正式业务代码
- `src/main/resources`：配置文件、模板、资源文件
- `src/test/java`：测试代码
- `src/test/resources`：测试资源
- `target`：构建输出目录，编译结果、打包结果通常在这里

### 3.4 本地仓库目录

Maven 下载的依赖通常会存在本地仓库中，默认位置一般是：

```text
~/.m2/repository
```

你会在里面看到很多真实的目录和 jar 包，例如：

```text
~/.m2/repository/
  org/
    springframework/
  junit/
  com/
    fasterxml/
```

### 3.5 `target` 目录

当你执行 Maven 构建命令后，最直观的结果就是项目里出现或更新 `target` 目录。

里面通常会有：

- 编译后的 `.class` 文件
- 测试报告
- 最终的 `jar` 或 `war`

### 3.6 用 `mario-test-framework` 看一个真实 Maven 仓库

如果只看抽象概念，Maven 很容易学得“会背不会用”。

所以这里先把当前这个仓库放到 Maven 视角下看一遍。

这个项目的根目录大致可以理解成：

```text
mario-test-framework/
  pom.xml
  README.md
  mario-bom/
    pom.xml
  mario-common/
    pom.xml
  mario-core/
    pom.xml
  mario-testng/
    pom.xml
  mario-tools/
    pom.xml
  mario-diff/
    pom.xml
  mario-monkey/
    pom.xml
  mario-scenario/
    pom.xml
  mariocasestats/
    pom.xml
  mario-test-parent/
    pom.xml
    mario-test-spec/
      pom.xml
```

这个结构能让你一次看到 Maven 中几个最常见的现实形态：

- 根目录有一个总 `pom.xml`
- 每个子模块各有自己的 `pom.xml`
- 根工程不是普通业务 jar，而是 `packaging=pom`
- 既有“聚合工程”，也有“父工程继承”
- 既有“版本统一管理”，也有“模板项目生成”

也就是说，这个仓库不是一个只有单模块的简单 demo，而是一个比较完整的 Maven 多模块仓库。

### 3.7 这几个 POM 在本项目里分别做什么

理解这个仓库时，最有价值的不是把所有模块名字背下来，而是先搞清楚“每一层 POM 的职责”。

#### 根目录 `pom.xml`

它的职责非常重，至少同时承担了下面几件事：

- 作为聚合工程，使用 `modules` 把多个模块串起来一起构建
- 作为公共父工程，给很多子模块统一 `groupId`、`version`、Java 版本、编码方式
- 通过 `dependencyManagement` 导入 `mario-bom`
- 通过 `pluginManagement` 和 `plugins` 统一部分插件行为
- 通过 `profiles` 切换 `test` / `prod` 资源目录
- 通过 `distributionManagement` 指定发布到 Pixel 的 release / snapshot 仓库

#### `mario-bom/pom.xml`

这个模块的核心职责不是“放代码”，而是“管版本”。

它主要通过 `properties` 和 `dependencyManagement` 做统一版本治理，例如：

- `spring.version`
- `testng.version`
- `fastjson.version`
- `gson.version`
- `okhttp.version`
- `mario.version`

你可以把它理解成：**整个 Mario 体系的版本清单中心。**

#### 业务模块的 `pom.xml`

例如：

- `mario-common/pom.xml`
- `mario-core/pom.xml`
- `mario-testng/pom.xml`
- `mario-tools/pom.xml`

这些模块大多通过 `parent` 继承根工程配置，因此很多依赖不必每次都手写版本号。

这就是父工程 + BOM 组合带来的好处：

- 子模块配置更短
- 版本更统一
- 升级更集中
- 依赖冲突更容易治理

#### `mario-test-parent/pom.xml`

这个模块不是普通功能模块，而是“测试项目父工程”。

它很关键，因为它把测试项目运行时真正要用的规则固化下来了，例如：

- 通过 `maven-surefire-plugin` 指定 TestNG 套件文件 `src/test/apiTest.xml`
- 自定义测试报告目录 `src/test/reports`
- 定义 `test` / `prod` profile 对应的资源目录
- 聚合 `mario-test-spec` 模块

这说明本仓库不只是“框架源码”，还把“测试工程怎么跑”也做成了 Maven 约定。

#### `mario-test-parent/mario-test-spec/pom.xml`

这个模块更像“模板来源工程”。

它的 README 和部署脚本说明了一个很重要的用法：

- 可以先把模板工程打成本地 archetype
- 也可以把 archetype 发布到远程仓库
- 最终通过 `mvn archetype:generate` 快速创建新的测试项目

这一步非常适合拿来理解 Maven 的另外一个能力：**Maven 不只会构建项目，还能生成项目骨架。**

---

## 4. Maven 主要解决什么问题

在 Maven 出现之前，Java 项目经常有下面这些麻烦：

- jar 包靠手工下载、手工复制，容易漏
- 每个人电脑上的 jar 版本不一致
- 项目目录结构五花八门，新人接手很痛苦
- 编译、测试、打包流程全靠自己写脚本
- 项目构建不标准，不方便团队协作

Maven 的目标就是把这些事情标准化：

- 统一项目结构
- 统一依赖管理方式
- 统一构建流程
- 统一发布方式

所以你可以把 Maven 理解成：**把 Java 项目的构建和依赖管理做成标准流程。**

---

## 5. Maven 的整体层次结构

很多初学者最容易糊涂的地方，就是不知道 Maven 的层次结构。可以把它分成下面几层来理解。

### 5.1 第一层：项目配置层

这一层主要回答：

**“这个项目是什么，需要什么，如何构建？”**

这层主要由 `pom.xml` 决定。

这一层包含的典型内容有：

- 项目坐标
- 依赖
- 插件
- 打包方式
- Java 版本
- 编码方式
- 模块关系
- 仓库地址
- 环境切换配置

### 5.2 第二层：构建流程层

这一层主要回答：

**“Maven 按什么步骤执行这些工作？”**

这就是 Maven 的生命周期和阶段，例如：

- `validate`
- `compile`
- `test`
- `package`
- `install`
- `deploy`

### 5.3 第三层：插件执行层

这一层主要回答：

**“真正干活的是谁？”**

答案是：**插件。**

Maven 更像一个总调度员，它本身负责组织流程；真正去做编译、测试、打包、发布这些事情的，通常是插件，例如：

- `maven-compiler-plugin`：编译 Java 代码
- `maven-surefire-plugin`：执行单元测试
- `maven-jar-plugin`：打 jar 包
- `maven-war-plugin`：打 war 包
- `spring-boot-maven-plugin`：Spring Boot 项目常用插件

### 5.4 一句话理解三层结构

- `pom.xml`：施工方案
- 生命周期：施工流程表
- 插件：真正干活的工人
- `mvn`：启动整个流程的命令

---

## 6. Maven 的几个核心对象

学习 Maven，最重要的是认识下面几个核心对象。

### 6.1 项目

一个 Maven 项目通常由一个 `pom.xml` 来描述。

### 6.2 依赖

项目所需要的第三方库。

例如：

- Spring
- JUnit
- MySQL 驱动
- Jackson

### 6.3 插件

Maven 在构建过程中使用的工具。

### 6.4 仓库

存放依赖和构件的地方。

### 6.5 构件

Maven 中的产物通常叫“构件”，比如：

- jar 包
- war 包
- pom 文件本身

### 6.6 生命周期

规定 Maven 以什么顺序执行构建工作。

---

## 7. 最核心的文件：`pom.xml`

`pom.xml` 是 Maven 的核心。

`pom` 的全称是：

**Project Object Model**

可以把它理解成：

**“项目对象模型文件”**，也就是“项目说明书”。

这个文件里通常会告诉 Maven：

- 这个项目叫什么
- 属于哪个组织
- 当前版本是什么
- 依赖哪些库
- 如何编译
- 如何测试
- 如何打包
- 是否有父工程
- 是否有子模块

---

## 8. 一个最简单的 `pom.xml` 示例

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>demo</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <name>demo</name>
    <description>A simple Maven project</description>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

这个文件不长，但已经包含了 Maven 的很多核心思想。

---

## 9. `pom.xml` 中最常见标签逐个解释

下面按重要程度来解释常见标签。

### 9.1 `project`

根标签，表示这整个文件是一个 Maven 项目配置。

### 9.2 `modelVersion`

```xml
<modelVersion>4.0.0</modelVersion>
```

表示 POM 模型版本。绝大多数项目都是 `4.0.0`。

注意：

- 它不是项目版本
- 它不是 Maven 程序版本
- 它是 POM 规范版本

### 9.3 `groupId`

```xml
<groupId>com.example</groupId>
```

表示项目所属的组织、公司、团队或域。

常见写法像 Java 包名倒写：

- `com.example`
- `org.springframework`
- `com.alibaba`

### 9.4 `artifactId`

```xml
<artifactId>demo</artifactId>
```

表示项目或构件的名字。

例如：

- `user-service`
- `order-api`
- `common-utils`

### 9.5 `version`

```xml
<version>1.0.0</version>
```

表示版本号。

常见形式：

- `1.0.0`
- `2.1.3`
- `1.0.0-SNAPSHOT`

其中 `SNAPSHOT` 表示开发中的快照版本，意味着这个版本还可能继续变化。

### 9.6 `groupId + artifactId + version`

这三个合起来叫：**项目坐标**。

Maven 主要靠这三个信息唯一定位一个构件。

你可以把它理解成构件的“完整地址”。

### 9.7 `packaging`

```xml
<packaging>jar</packaging>
```

表示最终打成什么包。

常见值：

- `jar`：普通 Java 项目，最常见
- `war`：Web 项目
- `pom`：父工程或聚合工程
- `ear`：较少见

### 9.8 `name`

项目显示名。

### 9.9 `description`

项目说明。

这两个标签更多是说明性质，不是 Maven 构建的最核心部分，但也常出现。

### 9.10 `properties`

用于集中定义属性和值。

例如：

```xml
<properties>
    <java.version>17</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>
```

作用：

- 减少重复
- 统一管理版本
- 统一 Java 版本
- 统一编码格式

后续可以这样引用：

```xml
<version>${junit.version}</version>
```

### 9.11 `dependencies`

表示项目依赖列表。

### 9.12 `dependency`

表示某一个具体依赖。

通常包含：

- `groupId`
- `artifactId`
- `version`
- `scope`

### 9.13 `scope`

表示依赖的生效范围。

常见值如下。

#### `compile`

默认值。表示：

- 编译时要用
- 测试时要用
- 运行时也要用

#### `test`

表示只在测试阶段需要，例如 JUnit。

#### `provided`

表示编译时需要，但运行时由外部容器提供。

例如：

- Servlet API 编译要用
- 运行时 Tomcat 已经提供了

#### `runtime`

表示运行时需要，但编译时未必需要。

#### `system`

从本地指定路径引入依赖，通常不推荐。

#### `import`

常与 `dependencyManagement` 配合，导入 BOM。

### 9.14 `build`

表示构建相关配置。

里面常见内容有：

- `plugins`
- `resources`
- `finalName`

### 9.15 `plugins`

表示构建时要使用的插件列表。

### 9.16 `plugin`

表示一个具体插件。

例如：

- 编译插件
- 测试插件
- 打包插件
- Spring Boot 插件

### 9.17 `configuration`

给插件传参数的地方。

例如给编译插件指定 Java 版本：

```xml
<configuration>
    <source>17</source>
    <target>17</target>
</configuration>
```

### 9.18 `executions`

表示插件在什么阶段执行、执行哪些 goal。

例如：

```xml
<executions>
    <execution>
        <phase>package</phase>
        <goals>
            <goal>repackage</goal>
        </goals>
    </execution>
</executions>
```

### 9.19 `parent`

表示当前项目继承哪个父工程。

父工程常用来：

- 统一版本
- 统一插件配置
- 统一属性
- 减少重复配置

### 9.20 `modules`

表示当前工程聚合了哪些子模块。

例如：

```xml
<modules>
    <module>common</module>
    <module>service</module>
    <module>web</module>
</modules>
```

### 9.21 `dependencyManagement`

这是很多初学者容易搞混的标签。

它的作用是：

**统一管理依赖版本，不等于直接引入依赖。**

也就是说，它更像“定规则”，而不是“直接使用”。

### 9.22 `pluginManagement`

和 `dependencyManagement` 类似，但管理的是插件版本和默认配置。

### 9.23 `repositories`

表示项目从哪些远程仓库下载依赖。

### 9.24 `pluginRepositories`

表示从哪些仓库下载插件。

### 9.25 `profiles`

表示不同环境下的配置切换。

常见场景：

- 开发环境
- 测试环境
- 生产环境

例如：

```xml
<profiles>
    <profile>
        <id>dev</id>
    </profile>
    <profile>
        <id>prod</id>
    </profile>
</profiles>
```

### 9.26 `distributionManagement`

定义构件发布到哪里，常用于：

- 发布 release 到正式仓库
- 发布 snapshot 到快照仓库

### 9.27 `exclusions`

这个标签非常常见，但初学者文档里经常被漏掉。

它的作用是：**排除某个依赖带进来的传递依赖。**

例如：

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>a-lib</artifactId>
    <version>1.0</version>
    <exclusions>
        <exclusion>
            <groupId>log4j</groupId>
            <artifactId>log4j</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

这表示：

- 仍然依赖 `a-lib`
- 但是不要把它传递带进来的 `log4j` 引进来

在当前项目里，`exclusions` 的使用非常多，尤其集中在这些场景：

- 排除日志相关冲突依赖
- 排除重复的 `httpclient` / `httpcore`
- 排除一些公司内部大依赖链上的不兼容组件
- 控制测试模块不要把不需要的依赖继续传播出去

所以你会在 `mario-diff`、`mario-monkey`、`mario-tools`、`mario-test-parent` 等模块里看到大量 `exclusions`。

### 9.28 `resources`

`resources` 用来声明哪些目录下的资源文件要进入构建结果。

例如：

```xml
<resources>
    <resource>
        <directory>src/main/resources</directory>
    </resource>
</resources>
```

在当前项目里，`resources` 不只是放常规资源，还承担了“按环境切换配置”的职责。

例如根工程和 `mario-test-parent` 里都有类似配置：

- `test` profile 指向 `src/test/profiles/test`
- `prod` profile 指向 `src/test/profiles/prod`
- 模板工程里还多了 `stage` profile

这意味着切换 profile 时，进入测试运行时的资源文件也会跟着切换。

### 9.29 `type`

`type` 表示依赖构件的类型。

大多数依赖默认是 `jar`，所以通常不写；但导入 BOM 时经常要显式写：

```xml
<type>pom</type>
```

当前项目根 `pom.xml` 在导入 `mario-bom` 时就是这样写的：

- `<scope>import</scope>`
- `<type>pom</type>`

这两个标签配合起来，才能把一个 BOM 当作“版本规则集合”导入进来。

### 9.30 `classifier`

`classifier` 表示同一个坐标下的不同附属产物。

最常见的例子有：

- `sources`
- `javadoc`
- `tests`

例如一个库可能既有主 jar，也有源码 jar：

- `demo-1.0.0.jar`
- `demo-1.0.0-sources.jar`

这里的 `sources` 就可以理解成 classifier。

当前仓库的 POM 中没有大面积直接使用这个标签，但在排查依赖、看仓库产物时经常会遇到，所以应该知道它的含义。

### 9.31 `optional`

`optional` 表示这个依赖对当前模块可用，但不要默认继续传递给下游使用者。

它常见于“增强能力依赖”“按需依赖”“桥接依赖”这类场景。

虽然本项目 POM 中没有把它作为主角大量使用，但它和 `exclusions` 一样，都是控制依赖传播边界的重要手段。

### 9.32 `finalName`

`finalName` 可以指定最终产物的名字。

例如：

```xml
<build>
    <finalName>demo-app</finalName>
</build>
```

这样最终生成的 jar / war 文件名就不一定完全跟 `artifactId` 一样。

当前项目里更常见的是通过 `maven-jar-plugin` 的 `manifestEntries` 注入版本信息，而不是大面积自定义 `finalName`。但你在企业项目里依然会经常遇到它。

### 9.33 用当前项目把这些标签串起来看一遍

如果把根工程 `pom.xml` 的设计思想浓缩一下，可以大致理解成下面这个结构：

```xml
<project>
    <groupId>com.example.toolchain.mario</groupId>
    <artifactId>mario-test-framework</artifactId>
    <version>${mario.version}</version>
    <packaging>pom</packaging>

    <modules>
        <module>mario-core</module>
        <module>mario-testng</module>
        <!-- 还有其他模块 -->
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.example.toolchain.mario</groupId>
                <artifactId>mario-bom</artifactId>
                <version>${mario.version}</version>
                <scope>import</scope>
                <type>pom</type>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <profiles>
        <profile>
            <id>prod</id>
        </profile>
        <profile>
            <id>test</id>
        </profile>
    </profiles>

    <distributionManagement>
        <!-- 发布到 Pixel 的 release / snapshot 仓库 -->
    </distributionManagement>
</project>
```

而 `mario-test-parent/pom.xml` 又在这个基础上继续补了一层“测试工程约定”：

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <suiteXmlFiles>
            <suiteXmlFile>${xml.file}</suiteXmlFile>
        </suiteXmlFiles>
        <reportsDirectory>${testreportsDirectory}</reportsDirectory>
    </configuration>
</plugin>
```

把这两层放在一起看，你就能更直观地理解：

- 根工程管“整个仓库怎么统一构建”
- BOM 管“版本怎么统一”
- 测试父工程管“测试项目怎么执行”
- 模板工程管“新项目怎么生成”

---

## 10. `dependencies` 和 `dependencyManagement` 的区别

这是新手最容易混淆的一对概念。

### `dependencies`

作用：**真正把依赖引进来。**

如果写在这里，项目通常就能直接使用该依赖。

### `dependencyManagement`

作用：**只负责统一管理版本，不一定直接引入。**

子模块如果要真正使用，通常还要在自己的 `dependencies` 中声明。

一句话记忆：

- `dependencies`：现在就要用
- `dependencyManagement`：先把规则定好

---

## 11. `plugins` 和 `pluginManagement` 的区别

这也是常见易混点。

### `plugins`

表示当前项目实际要使用这些插件。

### `pluginManagement`

表示给子模块统一管理插件版本和默认配置，但不一定立刻执行。

一句话记忆：

- `plugins`：真正参与构建
- `pluginManagement`：统一管理插件规则

---

## 12. Maven 仓库到底是什么

“仓库”这个词听起来抽象，但其实非常具体。

仓库就是：**存放 Maven 构件的地方。**

常见构件包括：

- `.jar`
- `.war`
- `.pom`
- 校验文件
- 元数据文件

### 12.1 本地仓库

默认路径：

```text
~/.m2/repository
```

作用：

- 缓存远程下载下来的依赖
- 保存本机 `install` 进去的构件
- 提高构建速度

### 12.2 远程仓库

在服务器上的仓库，例如：

- Maven Central
- 公司私服
- 其他公开仓库

### 12.3 私服仓库

团队或公司内部搭建的 Maven 仓库，常见产品有：

- Nexus
- Artifactory

它的作用通常是：

- 缓存公网依赖
- 管理公司内部构件
- 控制权限
- 提高下载速度

---

## 13. Maven 下载依赖时，背后发生了什么

假设你第一次执行：

```bash
mvn compile
```

Maven 通常会做下面这些事情：

1. 读取 `pom.xml`
2. 解析项目坐标和依赖信息
3. 查看本地仓库里有没有这些依赖
4. 如果没有，就去远程仓库下载
5. 下载 jar、pom、元数据等文件
6. 保存到本地仓库
7. 再继续执行编译

所以你第一次构建往往比较慢，第二次会快很多，因为很多依赖已经缓存到本地了。

---

## 14. 依赖传递是什么意思

依赖传递是 Maven 非常重要的一个能力。

假设你的项目依赖 A：

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>a-lib</artifactId>
    <version>1.0</version>
</dependency>
```

如果 `a-lib` 自己又依赖 `b-lib`，那么你的项目通常也会自动间接得到 `b-lib`。

这就叫：**依赖传递。**

好处是方便，不需要每个依赖都手写。

问题是也可能带来：

- 版本冲突
- 引入不需要的依赖
- 包冲突

---

## 15. 依赖冲突是什么意思

例如：

- 你的项目依赖 A
- A 依赖 `x:1.0`
- 你的项目又依赖 B
- B 依赖 `x:2.0`

这时 Maven 需要决定到底用 `x:1.0` 还是 `x:2.0`。

这就叫依赖冲突。

排查依赖冲突常用命令：

```bash
mvn dependency:tree
```

如果发现冲突，常见处理办法有：

- 显式指定依赖版本
- 使用 `dependencyManagement` 统一版本
- 使用 `exclusions` 排除某些传递依赖

---

## 16. 生命周期、阶段、Goal，到底是什么关系

这是 Maven 的核心概念之一。

### 16.1 生命周期（Lifecycle）

生命周期表示“一整套构建流程”。

Maven 主要有三套生命周期：

- `default`
- `clean`
- `site`

平时最常用的是 `default` 和 `clean`。

### 16.2 阶段（Phase）

生命周期中的每一步，叫阶段。

例如 default 生命周期中常见阶段：

- `validate`
- `compile`
- `test`
- `package`
- `verify`
- `install`
- `deploy`

### 16.3 Goal

Goal 是插件提供的具体动作。

例如：

- `compiler:compile`
- `surefire:test`
- `jar:jar`

### 16.4 三者关系

可以这样理解：

- 生命周期：整套流程
- 阶段：流程中的站点
- goal：在站点执行的具体动作

也可以记成：

**生命周期 > 阶段 > 插件 goal**

---

## 17. Maven 最常见阶段的作用

### 17.1 `clean`

清理上一次构建结果，通常删除 `target` 目录。

```bash
mvn clean
```

### 17.2 `validate`

验证项目是否正确、配置是否完整。

### 17.3 `compile`

编译主代码，把 `src/main/java` 编译成 `.class` 文件。

```bash
mvn compile
```

### 17.4 `test`

运行测试。

```bash
mvn test
```

### 17.5 `package`

打包。

```bash
mvn package
```

例如：

- `jar` 项目打成 jar
- `war` 项目打成 war

### 17.6 `verify`

执行更完整的检查。

### 17.7 `install`

把构件安装到本地仓库。

```bash
mvn install
```

### 17.8 `deploy`

把构件发布到远程仓库。

```bash
mvn deploy
```

---

## 18. 为什么执行一个命令会做很多事情

例如你执行：

```bash
mvn package
```

Maven 并不是只做打包这一步，而是会从前面的必要阶段开始做到 `package`：

- `validate`
- `compile`
- `test`
- `package`

如果你执行：

```bash
mvn install
```

通常会依次走到：

- `validate`
- `compile`
- `test`
- `package`
- `install`

所以很多初学者觉得 Maven “怎么一条命令干了很多事”，根本原因就是 Maven 是按生命周期推进的。

---

## 19. Maven 执行命令时到底做了什么

这里以 `mvn package` 为例，梳理一次背后过程。

### 第 1 步：读取 `pom.xml`

Maven 先读取项目配置，知道：

- 项目坐标是什么
- 项目依赖了什么
- 用什么插件
- 打包方式是什么

### 第 2 步：合并配置来源

Maven 真正执行时，不只看当前这个 `pom.xml`，还会综合：

- Super POM
- 父 POM
- 当前 POM
- profile
- 命令行参数

最终得到一个“生效后的完整配置”。

### 第 3 步：解析属性

把 `${...}` 这样的变量替换成真正的值。

### 第 4 步：解析依赖树

Maven 会分析：

- 直接依赖
- 间接依赖
- 版本冲突
- 最终采用哪个版本

### 第 5 步：下载缺失依赖

本地没有的依赖会从远程仓库下载。

### 第 6 步：确定执行到哪个阶段

如果命令是 `package`，那就执行到 `package` 为止。

### 第 7 步：调用对应插件

Maven 会根据阶段调用合适的插件，例如：

- 编译时用编译插件
- 测试时用测试插件
- 打包时用 jar 或 war 插件

### 第 8 步：把结果输出到 `target`

例如：

- `.class`
- 测试报告
- `.jar`
- `.war`

---

## 20. `dependencies` 和 `plugins` 的区别

这是非常重要的一个区分。

### `dependencies`

表示：**项目代码本身要用的库。**

例如：

- Spring
- Jackson
- JUnit
- MySQL 驱动

### `plugins`

表示：**Maven 构建过程中使用的工具。**

例如：

- 编译插件
- 测试插件
- 打包插件

一句话总结：

- `dependencies`：给项目代码用
- `plugins`：给 Maven 构建过程用

---

## 21. `pom.xml` 和 `settings.xml` 的区别

这也是初学者非常容易混淆的地方。

### 21.1 `pom.xml`

项目级配置，描述的是：

- 这个项目本身怎么构建
- 这个项目依赖什么
- 这个项目的版本和插件是什么

### 21.2 `settings.xml`

用户或机器级配置，描述的是：

- 你这台机器上 Maven 怎么工作
- 本地仓库路径
- 仓库镜像地址
- 用户名密码
- 代理配置

通常在：

```text
~/.m2/settings.xml
```

一句话记忆：

- `pom.xml`：项目说明书
- `settings.xml`：你这台机器上的 Maven 使用设置

---

## 22. Super POM 是什么

Super POM 可以理解成：

**Maven 自带的一份默认父配置。**

即使你的 `pom.xml` 没写太多配置，Maven 也不是完全没规则可用。它内部有一套默认规则，这些规则很多就来自 Super POM。

所以真正的最终配置，往往不是眼前这个 `pom.xml` 单独决定的，而是多种来源叠加后的结果。

---

## 23. 为什么有时不写插件，Maven 也能编译

因为 Maven 有很多默认约定和默认绑定。

例如你执行：

```bash
mvn compile
```

即使没有手写 `maven-compiler-plugin`，Maven 也常常知道该怎么编译。

原因是：

- Maven 有默认生命周期
- 生命周期的某些阶段有默认插件绑定
- Super POM 和约定机制也在起作用

这体现了 Maven 的一个重要理念：

**约定优于配置。**

意思就是：

- 常见场景采用默认规则
- 只有特殊情况才需要显式配置

---

## 24. 什么是父工程、子工程、多模块

在大型项目中，一个系统通常不会只用一个模块。

例如：

```text
parent-project/
  pom.xml
  common/
    pom.xml
  user-service/
    pom.xml
  order-service/
    pom.xml
```

这里一般会这样理解：

- 顶层 `pom.xml`：父工程或聚合工程
- `common`、`user-service`、`order-service`：子模块

### 24.1 父工程的作用

父工程通常负责：

- 统一依赖版本
- 统一插件版本
- 统一 Java 版本
- 统一编码方式
- 统一公共配置

### 24.2 子工程的作用

子工程负责自己的具体业务和具体依赖。

### 24.3 聚合和继承

多模块项目里常会同时出现：

- 聚合：通过 `modules` 把多个模块组织起来
- 继承：通过 `parent` 让子模块继承父模块配置

这两个概念经常一起出现，但本质上不是一回事。

---

## 25. 一个典型父工程示例

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>parent-project</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>common</module>
        <module>user-service</module>
        <module>order-service</module>
    </modules>

    <dependencyManagement>
        <!-- 统一依赖版本 -->
    </dependencyManagement>
</project>
```

这个示例里最重要的特点有两个：

- `packaging` 是 `pom`
- 使用 `modules` 管理多个子模块

---

## 26. 什么是 Maven 模板（Archetype）

你提到“模板中干了什么”，这通常对应 Maven 的 `archetype`。

可以把它理解成：

**项目骨架模板。**

例如执行：

```bash
mvn archetype:generate
```

Maven 可能会做这些事：

- 下载项目模板
- 根据模板创建目录结构
- 生成初始 `pom.xml`
- 生成示例代码
- 生成测试代码

所以 `archetype` 就像“快速生成一套标准项目骨架”的工具。

---

## 27. 最常见 Maven 命令及作用

下面整理最常见、最值得掌握的一批命令。

### 27.1 `mvn clean`

作用：删除旧构建产物，通常清空 `target`。

### 27.2 `mvn compile`

作用：

- 解析依赖
- 下载缺失依赖
- 编译主代码

输出通常在：

```text
target/classes
```

### 27.3 `mvn test`

作用：

- 编译主代码
- 编译测试代码
- 运行测试

### 27.4 `mvn package`

作用：

- 编译
- 测试
- 打包

产物通常在：

```text
target/
```

### 27.5 `mvn clean package`

作用：

- 先清理旧结果
- 再重新编译、测试、打包

这是一条非常常见的命令。

### 27.6 `mvn install`

作用：

- 完成 package
- 将构件安装到本地仓库

安装位置通常是：

```text
~/.m2/repository
```

### 27.7 `mvn deploy`

作用：

- 完成前面的必要构建步骤
- 将构件发布到远程仓库

### 27.8 `mvn dependency:tree`

作用：查看依赖树。

非常适合排查：

- 依赖传递
- 依赖冲突
- 版本来源

### 27.9 `mvn dependency:copy-dependencies`

作用：把依赖复制到指定目录，便于查看或打包。

### 27.10 `mvn help:effective-pom`

作用：查看最终生效的 POM。

这个命令对理解 Maven 非常有帮助，因为它会把：

- 父 POM
- Super POM
- 当前 POM
- profile

综合后的结果展示出来。

### 27.11 `mvn help:effective-settings`

作用：查看最终生效的 Maven 设置。

### 27.12 `mvn archetype:generate`

作用：通过模板创建新项目。

### 27.13 `mvn -DskipTests package`

作用：打包，但跳过测试执行。

### 27.14 `mvn -Pdev package`

作用：启用 `dev` 这个 profile 后再打包。

---

## 28. `package`、`install`、`deploy` 的区别

### `package`

只负责把项目打包，产物通常在 `target` 目录。

### `install`

先打包，再把构件安装到本地仓库。

### `deploy`

先完成必要构建步骤，再把构件上传到远程仓库。

一句话记忆：

- `package`：做出来
- `install`：放到自己家仓库
- `deploy`：发到公共仓库

---

## 29. `clean` 为什么常常和其他命令连着用

例如：

```bash
mvn clean package
mvn clean install
```

这是因为 `target` 中可能残留旧结果。

先 `clean` 再构建，能减少旧文件带来的干扰，构建结果更干净。

---

## 30. `target` 目录里通常会看到什么

根据项目类型不同，`target` 目录内容会有差异，但常见有：

- `classes/`：主代码编译产物
- `test-classes/`：测试代码编译产物
- `generated-sources/`：生成的源码
- `surefire-reports/`：测试报告
- `*.jar`：jar 包
- `*.war`：war 包

你可以把 `target` 理解成：**Maven 每次构建后的工作成果区。**

---

## 31. `install` 之后本地仓库里会发生什么

当你执行：

```bash
mvn install
```

Maven 会把当前项目的构件安装到本地仓库，通常路径会类似：

```text
~/.m2/repository/com/example/demo/1.0.0/
```

里面可能会出现：

- `demo-1.0.0.jar`
- `demo-1.0.0.pom`

这意味着你本机其他 Maven 项目以后就能依赖这个构件了。

---

## 32. `deploy` 之后远程仓库里会发生什么

当你执行：

```bash
mvn deploy
```

Maven 会把当前构件上传到远程仓库。

这样：

- 其他开发人员可以下载它
- CI/CD 可以使用它
- 团队内部系统可以基于它继续构建

这通常用于团队协作、统一版本管理和发布流程。

---

## 33. 初学者最容易混淆的几组概念

### 33.1 依赖 和 插件

- 依赖：项目代码要用的库
- 插件：Maven 构建时要用的工具

### 33.2 `dependencies` 和 `dependencyManagement`

- `dependencies`：真正引入
- `dependencyManagement`：统一定版本规则

### 33.3 `plugins` 和 `pluginManagement`

- `plugins`：真正参与构建
- `pluginManagement`：统一管理插件配置

### 33.4 `package` 和 `install`

- `package`：产物在 `target`
- `install`：额外放到本地仓库

### 33.5 `install` 和 `deploy`

- `install`：本地
- `deploy`：远程

### 33.6 `pom.xml` 和 `settings.xml`

- `pom.xml`：项目级
- `settings.xml`：机器级

### 33.7 聚合 和 继承

- 聚合：`modules`
- 继承：`parent`

---

## 34. 看到一个陌生 Maven 项目时，建议怎么读

如果别人给你一个 Maven 项目，你可以按下面顺序看。

### 第 1 步：看根目录有没有 `pom.xml`

有的话，基本确定它是 Maven 项目。

### 第 2 步：看坐标

先看：

- `groupId`
- `artifactId`
- `version`

了解它是谁、叫什么、什么版本。

### 第 3 步：看 `packaging`

判断它是：

- 普通 jar 模块
- Web 模块
- 父工程

### 第 4 步：看有没有 `parent`

判断它是否继承了上级工程配置。

### 第 5 步：看 `dependencies`

看它主要依赖了哪些库。

### 第 6 步：看 `build/plugins`

看构建时有没有特殊插件。

### 第 7 步：看有没有 `modules`

如果有，说明它很可能是一个聚合父工程。

### 第 8 步：执行命令观察现象

推荐执行：

```bash
mvn clean
mvn compile
mvn test
mvn package
mvn dependency:tree
mvn help:effective-pom
```

这样可以把概念和实际构建过程对应起来。

---

## 35. 一套适合初学者的 Maven 学习路线

如果你现在觉得内容很多，不要试图一次全部背下来。可以按下面顺序学习。

### 第 1 阶段：先认识看得见的东西

先搞明白：

- `pom.xml`
- `src/main/java`
- `src/test/java`
- `target`
- `~/.m2/repository`

### 第 2 阶段：先学最关键的 4 个标签

先学：

- `groupId`
- `artifactId`
- `version`
- `dependencies`

### 第 3 阶段：先学最关键的 4 个命令

先练：

```bash
mvn compile
mvn test
mvn package
mvn install
```

### 第 4 阶段：学生命周期

重点理解：

- 为什么一个命令会顺带执行前面的步骤
- 为什么 `install` 比 `package` 做得更多

### 第 5 阶段：学插件

重点理解：

- Maven 本身是调度者
- 插件才是真正干活的人

### 第 6 阶段：学多模块和父工程

理解大型项目为什么需要统一管理。

### 第 7 阶段：学排查命令

重点学会：

```bash
mvn dependency:tree
mvn help:effective-pom
mvn help:effective-settings
```

---

## 36. 一个帮助记忆的比喻：把 Maven 想成工厂系统

如果你对技术术语不熟，可以把 Maven 想成一个工厂。

- `pom.xml`：生产计划书
- `dependencies`：原材料清单
- `repository`：仓库
- `plugins`：机器或工人
- `lifecycle`：生产流程
- `mvn package`：下令开始生产并打包成品
- `target`：成品区
- `install`：把成品放到自己家的仓库
- `deploy`：把成品送到公共仓库

这个比喻虽然不严谨，但对初学者非常有帮助。

---

## 37. 最后做一个总复习

如果把 Maven 全部压缩成最核心的几点，可以记住下面这些话：

1. Maven 是 Java 项目的构建和依赖管理工具。
2. Maven 项目最核心的文件是 `pom.xml`。
3. `pom.xml` 里最关键的是项目坐标、依赖、插件、构建配置。
4. Maven 会根据坐标去仓库找依赖，根据生命周期执行构建流程。
5. 生命周期是流程，阶段是流程中的节点，插件是真正干活的执行者。
6. 本地仓库通常在 `~/.m2/repository`。
7. `package` 是打包，`install` 是装进本地仓库，`deploy` 是发布到远程仓库。
8. 大项目往往会用父工程和多模块来统一管理配置。
9. `dependencies` 和 `plugins` 不是一回事，前者给项目代码用，后者给 Maven 构建过程用。
10. 学 Maven 不要只背概念，要把 `pom.xml`、`target`、本地仓库、命令执行结果对应起来看。

---

## 38. 给初学者的最后建议

如果你刚开始学习 Maven，不要急着记住所有标签和命令。最好的方式是：

- 一边看一个简单 Maven 项目
- 一边打开 `pom.xml`
- 一边执行 `mvn compile`、`mvn package`、`mvn install`
- 一边观察 `target` 和 `~/.m2/repository` 的变化

当你把“概念”和“眼前看到的现象”一一对应起来，Maven 就不再抽象了。

你真正要建立的是下面这个认知链条：

- `pom.xml` 写了规则
- `mvn` 读取这些规则
- Maven 解析依赖并下载材料
- 生命周期决定执行顺序
- 插件负责具体工作
- 结果输出到 `target` 或仓库

理解了这条主线，Maven 的大部分内容都会越来越清楚。

---

## 39. 用当前项目把 Maven 整体走一遍

前面讲的是 Maven 通用知识，这一节专门把它和当前仓库对上。

如果你现在站在 `mario-test-framework` 根目录执行 Maven，脑子里最好形成下面这条主线：

### 第 1 步：根工程先决定“这是一棵多模块树”

根 `pom.xml` 的 `packaging` 是 `pom`，并且声明了多个 `module`。

这意味着它本身主要不是为了产出业务 jar，而是为了：

- 聚合构建
- 统一配置
- 统一版本
- 统一发布规则

### 第 2 步：根工程导入 `mario-bom` 统一版本

当前仓库最值得学习的一点是：**根工程不是把所有第三方版本都直接摊在各个子模块里，而是借助 BOM 统一治理。**

所以当子模块写：

```xml
<dependency>
    <groupId>com.example.toolchain.mario</groupId>
    <artifactId>mario-common</artifactId>
</dependency>
```

很多时候版本号并不是“漏写了”，而是“交给父工程和 BOM 来兜底了”。

### 第 3 步：子模块继承根工程

像 `mario-common`、`mario-core`、`mario-testng`、`mario-tools` 这类模块，通常会通过 `parent` 继承根工程。

这样做的结果是：

- 统一 `groupId`
- 统一 `${mario.version}`
- 统一编码和 Java 版本
- 统一部分插件和发布策略

### 第 4 步：测试父工程再补“测试项目怎么跑”

`mario-test-parent/pom.xml` 比普通业务模块多了一层“测试执行约定”，例如：

- 测试套件入口文件默认是 `src/test/apiTest.xml`
- 报告输出目录默认是 `src/test/reports`
- `test` profile 默认激活
- `prod` profile 需要显式开启

这和普通 Java 库项目很不一样，它已经在 Maven 里固化了自动化测试项目的运行方式。

### 第 5 步：模板模块负责生成新项目

`mario-test-parent/mario-test-spec` 的 README 和部署脚本说明了完整链路：

1. 先把模板项目整理干净
2. 用 `mvn archetype:create-from-project` 生成 archetype
3. 本地 `install` 或远程 `deploy`
4. 再通过 `mvn archetype:generate` 创建新测试项目

这一步非常重要，因为它说明 Maven 在这个仓库里的角色不只是“编译框架”，而是“维护一套可复制的测试项目模板体系”。

---

## 40. 本项目里最关键的 Maven 插件在干什么

很多人学 Maven 时只记得“有插件”，但不知道插件在实际项目里到底承担什么职责。这个仓库里可以看到非常典型的几类插件。

### 40.1 `flatten-maven-plugin`

这个插件在根工程和 `mario-bom` 里都很关键。

它的核心作用可以理解成：

- 在发布前把 POM 里的属性、继承关系、CI 友好版本等解析成更适合发布的形式
- 避免下游消费者拿到一个“还要继续猜变量值”的 POM

当前项目里你能看到像这样的配置：

- `flattenMode=resolveCiFriendliesOnly`
- `updatePomFile=true`
- 在 `process-resources` 阶段执行 `flatten`

这说明项目在发布坐标和版本时，比较重视产物 POM 的稳定性和可消费性。

### 40.2 `maven-compiler-plugin`

它负责 Java 编译。

当前仓库整体使用的是 JDK 1.8 语义，你能在根工程中看到：

- `maven.compiler.source=1.8`
- `maven.compiler.target=1.8`

有些模块又显式声明了 `maven-compiler-plugin`，这代表：

- 父工程可以给默认规则
- 具体模块也可以覆盖或再次明确配置

### 40.3 `maven-surefire-plugin`

这个插件是 Maven 跑测试最常见的插件。

在 `mario-test-parent/pom.xml` 里，它被用来绑定 TestNG 套件文件：

- `${xml.file}` 默认是 `src/test/apiTest.xml`
- 测试报告目录是 `${testreportsDirectory}`

这就把“测试从哪里开始跑”这件事做成了 Maven 约定。

### 40.4 `maven-jar-plugin`

这个插件在多个模块里都出现了，主要用来：

- 生成 jar
- 向 manifest 里写入版本信息
- 控制打包内容

例如本项目会往 manifest 里写：

- `mario-core-version`
- `mario-common-version`
- `mario-testng-version`
- `mario-test-framework`

这是一种很常见的企业实践：让产物本身带上可追踪的版本元数据。

### 40.5 `maven-source-plugin`

根工程里配置了 source jar 的附加打包。

它的作用是：

- 附带生成源码包
- 便于 IDE 跳源码
- 便于下游依赖方调试

### 40.6 `xmdlog-maven-plugin`

这是一个比较偏项目定制化的插件。

从配置上看，它在 `compile` 阶段执行 `check` goal，说明它更像是一个构建期校验器，而不是普通打包插件。

当你看到这种插件时，要意识到：

- Maven 不是只能调官方插件
- 企业项目里经常会把内部规范校验也接进 Maven 生命周期

### 40.7 `archetype:create-from-project`

虽然它不是以长期写在根 POM 里的方式出现，但部署脚本直接调用了这个 goal。

它的作用是：

- 从现有项目反向生成 archetype
- 把一个真实工程抽成模板
- 之后供 `archetype:generate` 使用

这正是当前模板工程发布流程的核心步骤。

---

## 41. README 里的 Maven 命令，应该怎么理解

这个仓库的几个 README 已经把一部分常用命令给出来了，但如果不解释，新手很容易只是“会复制命令，不知道为什么”。

### 41.1 根 README 里的全量构建命令

根 README 提供了类似这样的命令：

```bash
mvn clean install -pl mario-bom,.,mario-common,mario-core,mario-testng,mario-tools,mariocasestats,mario-diff,mario-monkey,mario-testx,mario-scenario,mario-test-parent -DskipTests
```

这条命令可以拆成几部分看：

- `clean`：先清理旧产物
- `install`：构建并安装到本地仓库
- `-pl ...`：只构建列出来的项目列表
- `.`：表示当前根工程本身
- `-DskipTests`：跳过测试执行

这里的重点不是“命令很长”，而是它体现了多模块项目里很常见的一个思路：

**我不一定每次让 Maven 自己推导全仓库，而是明确告诉它这次要构建哪一批模块。**

### 41.2 忽略测试失败继续安装

README 里还有：

```bash
mvn clean install -pl mario-bom,.,mario-common,mario-core,mario-testng,mario-tools,mariocasestats,mario-diff,mario-monkey,mario-testx,mario-scenario,mario-test-parent -Dmaven.test.failure.ignore=true
```

这里和 `-DskipTests` 完全不是一回事。

它表示：

- 仍然运行测试
- 但即使测试失败，也先不要让整个构建立刻终止

这个参数常用于：

- 想先把其他模块尽量编完
- 想收集完整失败信息
- 本地排查测试稳定性时先保留构建结果

### 41.3 模板 README 里的生成命令

`mario-test-parent/README.md` 和 `mario-test-parent/mario-test-spec/README.md` 里重点给的是 archetype 流程。

例如：

```bash
mvn archetype:generate -B \
  -DarchetypeArtifactId=mario-test-archetype \
  -DarchetypeGroupId=com.example.toolchain.mario.archetypes \
  -DarchetypeVersion=RELEASE \
  -DarchetypeCatalog=remote \
  -DgroupId=com.demo.service \
  -DartifactId=demo
```

这条命令的含义是：

- 从远程 archetype 仓库拉取 Mario 模板
- 用批处理模式创建项目
- 指定新项目自己的 `groupId`、`artifactId`

也就是说，Maven 在这里不是“构建已有项目”，而是在“生成一个新的项目骨架”。

### 41.4 部署脚本里的命令链

`mario-test-parent/deploy/mario-test-spec-deploy.sh` 里还有一条非常值得学习的流程：

```bash
mvn clean
mvn archetype:create-from-project
cd target/generated-sources/archetype/
mvn -U clean install
```

如果加上 `DEPLOY=true`，脚本会走 `deploy`，并显式指定 release / snapshot 远程仓库。

这说明本项目对模板发布的理解不是“人工拷贝一个模板目录”，而是“把模板也纳入标准 Maven 生命周期和仓库体系”。

---

## 42. `-DskipTests` 和 `-Dmaven.test.skip=true` 的区别

这是实际工作里最容易混淆的问题之一，本项目 README 里已经用了 `-DskipTests`，所以这里必须单独讲清楚。

### `-DskipTests`

通常表示：

- 不执行测试
- 但测试代码仍然可能被编译

适合场景：

- 你主要想验证主代码能不能打包
- 你不想花时间跑完整测试
- 但你仍希望测试源码层面的编译问题尽量暴露出来

### `-Dmaven.test.skip=true`

通常表示：

- 不编译测试
- 也不执行测试

适合场景：

- 你只想最快速地构建主产物
- 测试代码当前不是关注重点
- 你明确接受“连测试代码编译错误都暂时不看”

### 在当前项目里怎么选

如果你是在维护 Mario 框架自身，通常更建议优先使用：

```bash
mvn clean install -DskipTests
```

原因是：

- 比完全跳过测试编译更稳妥
- 不容易把测试源码中的明显编译错误一并放过去
- 也符合根 README 给出的使用方式

只有在明确追求极致速度、并且你知道自己在做什么的时候，才考虑 `maven.test.skip=true`。

---

## 43. 多模块项目里最实用的 3 个参数：`-pl`、`-am`、`-rf`

当前仓库是典型多模块项目，所以这三个参数非常值得掌握。

### 43.1 `-pl`：只构建指定模块

例如：

```bash
mvn -pl mario-common,mario-core test
```

表示只处理这两个模块。

适合场景：

- 你只改了少数模块
- 不想全仓库都跑一遍
- 想快速验证局部改动

### 43.2 `-am`：把依赖它的上游一起带上

例如：

```bash
mvn -pl mario-core -am install -DskipTests
```

如果 `mario-core` 依赖 `mario-common`，那么 Maven 会把上游需要的模块一起构建。

一句话记忆：

- `-pl`：圈定目标
- `-am`：把目标所需的上游一起补齐

### 43.3 `-rf`：从失败处继续

例如一次全量构建在 `mario-testng` 失败了，修复后可以尝试：

```bash
mvn -rf :mario-testng install -DskipTests
```

这在大仓库里非常实用，因为它能减少从头再跑一次的时间。

### 43.4 给当前项目的几个推荐例子

```bash
mvn -pl mario-common,mario-core -am test
mvn -pl mario-test-parent -am install -DskipTests
mvn -rf :mario-testng install -DskipTests
```

---

## 44. 当前项目的 Profile 和资源切换，是怎么工作的

如果你只从概念上理解 profile，很容易觉得它只是“环境名列表”。但在当前仓库里，它是直接影响测试资源加载的。

### 44.1 根工程里的 `test` / `prod`

根 `pom.xml` 中有两个 profile：

- `test`：默认激活
- `prod`：需要显式开启

并且它们分别把资源目录指向：

- `src/test/profiles/test`
- `src/test/profiles/prod`

### 44.2 `mario-test-parent` 里的测试工程约定

`mario-test-parent/pom.xml` 延续了同样的思路。

这意味着由它派生出来的测试项目在运行 `mvn test` 时，默认就会带上 test 环境的资源文件。

### 44.3 `mario-test-spec` 里又多了一个 `stage`

模板工程里额外声明了：

- `stage`

这说明模板生成出来的测试项目，天然考虑了多环境测试的需要。

### 44.4 一个最直观的理解方式

可以把当前项目的 profile 理解成：

- 不同环境不是靠手工改配置文件
- 而是靠 Maven 在构建 / 测试时选择不同资源目录

例如在根工程或基于 `mario-test-parent` 的普通测试项目里，常见的是：

```bash
mvn clean test
mvn clean test -P prod
```

而 `stage` 这个 profile 是模板工程 `mario-test-spec` 额外声明出来的，所以更准确地说，它适用于模板工程或由模板扩展出的对应项目场景：

```bash
mvn -f mario-test-parent/mario-test-spec/pom.xml clean test -P stage
```

这些命令的“测试代码”可能没变，但“被带进去的资源配置”是会变的。

### 44.5 怎么查看当前到底激活了哪些 profile

很推荐你顺手学会这个命令：

```bash
mvn help:active-profiles
```

当你怀疑“为什么读到的是 prod 配置而不是 test 配置”时，它特别有用。

---

## 45. `SNAPSHOT`、发布仓库、`flatten` 在这个项目里是怎么联动的

当前根工程和 `mario-bom` 都用了：

```text
3.11.4-SNAPSHOT
```

这不是普通字符串，它直接影响 Maven 的发布行为。

### 45.1 `SNAPSHOT` 代表什么

它表示：

- 这是一个开发中的可变版本
- 适合持续迭代
- 通常应该发布到 snapshot 仓库，而不是正式 release 仓库

### 45.2 根工程里的 `distributionManagement`

根 `pom.xml` 已经把两个目标仓库分开了：

- release 仓库
- snapshot 仓库

这意味着 Maven 在执行 `deploy` 时，会结合版本号判断应该往哪边发。

### 45.3 为什么部署脚本还额外传了 `altReleaseDeploymentRepository`

模板部署脚本里显式传了：

- `-DaltReleaseDeploymentRepository=...`
- `-DaltSnapshotDeploymentRepository=...`

这通常表示脚本希望：

- 在命令层面再明确一遍发布目的地
- 降低对默认发布配置的依赖
- 让模板发布流程更可控

### 45.4 `flatten` 在这里解决什么问题

当项目大量使用：

- `${mario.version}`
- 父 POM 继承
- BOM 管理

如果直接把未经处理的 POM 发出去，下游有时会更难理解最终版本关系。

`flatten-maven-plugin` 的作用之一就是把这些信息“摊平”到更适合消费的形式，再参与发布。

所以这几个概念在当前仓库里不是分散的，而是一条完整链路：

- 用 `SNAPSHOT` 表示开发中版本
- 用 `distributionManagement` 指向发布仓库
- 用 `flatten` 让最终发布出去的 POM 更稳定
- 用 `deploy` 或模板脚本把产物送到 Pixel

---

## 46. 为什么这个项目很多依赖不写版本，很多依赖却写了 `exclusions`

这是当前仓库里非常典型、也非常值得初学者观察的现象。

### 46.1 不写版本，是因为版本已经被统一管理了

如果一个依赖的版本已经在父工程 / BOM 中统一管理，那么子模块里就可以只写：

```xml
<dependency>
    <groupId>com.example.toolchain.mario</groupId>
    <artifactId>mario-common</artifactId>
</dependency>
```

这不是偷懒，而是标准做法。

### 46.2 写很多 `exclusions`，是因为企业项目的依赖树通常很复杂

当前仓库接入了不少公司内部组件，也有很多测试框架、HTTP、日志、RPC 相关库。

这类项目特别容易出现：

- 日志实现冲突
- 同名库不同版本冲突
- 传递依赖过多
- 某些依赖链把不需要的重量级组件也带进来

所以 `exclusions` 在这里不是“偶尔修补”，而是一种常态化治理手段。

### 46.3 这对你读 POM 有什么启发

当你看到一个企业 POM 时，不要只盯着 `dependencies` 列表本身，还要重点看：

- 有没有 `dependencyManagement`
- 有没有 `exclusions`
- 哪些依赖是 test scope
- 哪些依赖来自父工程/BOM

真正决定依赖结果的，往往是这些“边界控制配置”。

---

## 47. 读这个仓库的 Maven 配置时，推荐按什么顺序看

如果你想真正看懂当前仓库，而不是只看懂一些零散标签，建议按下面顺序读。

### 第 1 步：先看根 `pom.xml`

重点看：

- `packaging`
- `modules`
- `properties`
- `dependencyManagement`
- `pluginManagement`
- `profiles`
- `distributionManagement`

目标：先搞清楚这个仓库总体怎么组织。

### 第 2 步：再看 `mario-bom/pom.xml`

目标：搞清楚哪些版本是被统一管理的。

### 第 3 步：挑一个普通模块看

推荐先看：

- `mario-common/pom.xml`
- `mario-core/pom.xml`

目标：看子模块如何继承父工程、如何复用 BOM 版本。

### 第 4 步：再看 `mario-test-parent/pom.xml`

目标：理解测试工程运行约定是如何被写进 Maven 的。

### 第 5 步：最后看模板 README 和部署脚本

重点看：

- `mario-test-parent/README.md`
- `mario-test-parent/mario-test-spec/README.md`
- `mario-test-parent/deploy/mario-test-spec-deploy.sh`

目标：理解 archetype 生成和发布链路。

### 第 6 步：配合命令观察

最推荐你边看边执行这些命令：

```bash
mvn help:effective-pom
mvn help:active-profiles
mvn dependency:tree
mvn -pl mario-core -am test
```

这样你会把“文件里的规则”和“命令执行的结果”对应起来。

---

## 48. 这份文档原来没展开，但你实际工作里很需要的内容

最后把几个在当前仓库里非常有用、但原文没有专门展开的点补上。

### 48.1 为什么 POM 里没写 `repositories`，依赖仍然能下载

这是很多新同学看到企业项目时的第一反应。

常见原因有：

- Maven 自带 Super POM 默认仓库规则
- 机器上的 `settings.xml` 配了 mirror
- 公司统一环境里已经把私服做成默认镜像

也就是说：

**能不能下载依赖，不一定只由项目里的 `pom.xml` 决定。**

这也是为什么你必须同时理解 `pom.xml` 和 `settings.xml`。

### 48.2 为什么有的项目会有 `mvnw`，这个项目却没有

`mvnw` 是 Maven Wrapper，用来让不同机器使用更一致的 Maven 版本。

当前仓库没有把 `mvnw` / `mvnw.cmd` 一起提交，所以默认依赖开发机本地安装好的 Maven。

这意味着：

- 你需要自己保证本地 Maven 可用
- 如果团队环境差异较大，构建行为可能更依赖机器配置

### 48.3 实战案例：如果你要给 Mario 新增一个模块，通常要想到什么

假设你要新增一个 `mario-foo` 模块，通常至少要检查这些点：

1. 根 `pom.xml` 的 `modules` 要不要加入它
2. 模块自己的 `parent` 是否指向根工程
3. 如果希望被统一管理，`artifactId` 和版本规则是否接入现有体系
4. 如果这个模块要被其他模块按“无版本号”依赖，是否应该纳入 `mario-bom`
5. 如果它需要参与统一发布，当前 `packaging`、插件、发布规则是否合适

这就是多模块仓库里最常见的 Maven 维护动作。

### 48.4 实战案例：如果你要发布一个新的模板版本，通常要怎么做

根据根 README、模板 README 和部署脚本，流程可以概括成：

1. 修改根工程和 `mario-bom` 中的 `mario.version`
2. 做一轮本地多模块构建验证
3. 进入 `mario-test-spec` 目录
4. 设置 `MARIO_VERSION`
5. 执行模板部署脚本，先本地 install 或远程 deploy
6. 用 `mvn archetype:generate` 真实生成一个 demo 工程验证模板是否可用

这条流程本身就是一个很好的 Maven 综合练习题，因为它同时覆盖了：

- 版本管理
- 多模块构建
- archetype
- install
- deploy
- 仓库发布

### 48.5 真正学会 Maven 的标志是什么

不是你能背出多少标签，而是你遇到一个陌生仓库时，能快速回答下面这些问题：

- 根 POM 是聚合工程还是普通模块
- 版本是散落管理还是 BOM 统一管理
- 子模块靠什么继承父配置
- 测试是怎么绑定到 surefire / failsafe / TestNG 的
- profile 切换时究竟换了什么
- 构件最终发到哪里
- 出现依赖冲突时该看哪棵依赖树

如果你已经能用这些问题去读 `mario-test-framework`，那你对 Maven 的理解就不再停留在“会用几个命令”的阶段了。
