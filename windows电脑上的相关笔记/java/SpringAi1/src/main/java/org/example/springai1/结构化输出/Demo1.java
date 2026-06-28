package org.example.springai1.结构化输出;

public class Demo1 {


  /*  先通俗理解：结构化输出转换器 = LLM 输出的 “翻译官”
    LLM 本身只会输出自然语言（比如大段文字、杂乱的 JSON 片段），但程序需要的是 能直接调用的结构化数据（比如一个 User 对象、一个 List<String> 列表）。
    结构化输出转换器的作用就是做 “双向翻译”：
    调用 LLM 前：自动给你的提示词加 “格式指令”（比如 “必须输出 JSON，字段要和 Java 类 ActorsFilms 一致，不能有多余解释”），让 LLM 知道该怎么输出；
    调用 LLM 后：自动把 LLM 的文本输出（比如 JSON 字符串）解析成你要的结构化类型（比如 ActorsFilms 对象、Map、List），不用你手动写解析代码。
    简单说：它解决的是 “LLM 输出 → 程序可用数据” 的转换问题，让你不用手动处理字符串解析、格式校验（基础校验）。
    文档核心内容提炼（用 “人话” 总结）
            1. 核心目标
    让开发者 “零代码解析 LLM 输出”，快速把 AI 结果转成 JSON、Java 类、Map、List 等结构化格式，直接传给下游函数 / 应用。
            2. 3 个常用转换器（文档重点）
    文档提供了 3 个 “开箱即用” 的转换器，覆盖大部分场景：
    转换器类型	                    作用（通俗版）	                          例子场景
    BeanOutputConverter	转成 Java 类 / 记录（比如 ActorsFilms）	让 AI 生成 “演员 + 电影列表”，直接转成 ActorsFilms 对象
    MapOutputConverter	转成 Map<String, Object> 键值对	让 AI 生成 “数字列表”，直接转成 {"numbers": [1,2,3]}
    ListOutputConverter	转成 List 列表（比如 List<String>）	让 AI 生成 “冰淇淋口味”，直接转成 ["草莓", "巧克力"]
            3. 最常用示例（BeanOutputConverter）
    比如你要 AI 生成 “汤姆・汉克斯的 5 部电影”，直接转成 Java 记录 ActorsFilms：
    java
            运行
    // 1. 定义要转换的 Java 结构（目标格式）
    record ActorsFilms(String actor, List<String> movies) {}

    // 2. 用 ChatClient + 转换器，一步到位
    ActorsFilms result = ChatClient.create(chatModel)
            .prompt(u -> u.text("生成汤姆·汉克斯的5部电影")
                    .param("actor", "Tom Hanks"))
            .call()
            .entity(ActorsFilms.class); // 自动转换，不用手动解析 JSON

// 3. 直接用结果（程序可直接调用字段）
System.out.println(result.actor()); // 输出：Tom Hanks
System.out.println(result.movies()); // 输出：[《阿甘正传》, 《拯救大兵瑞恩》, ...]
    背后发生的事：
    转换器自动给提示词加了格式指令：“必须输出 JSON，字段和 ActorsFilms 一致，无多余解释”；
    LLM 输出 JSON 字符串后，转换器自动用 ObjectMapper 反序列化成 ActorsFilms 对象。
    它和「递归顾问」的关系：分工不同，可搭配使用
    两者都和 “结构化输出” 相关，但解决的是 不同环节的问题，组合起来能实现 “既转得对，又符合要求” 的闭环。
            1. 核心区别（一张表看明白）
    维度	结构化输出转换器	递归顾问（比如 StructuredOutputValidationAdvisor）
    核心目标	解决 “转换问题”：文本 → 结构化数据（Java 类 / Map/List）	解决 “保障问题”：确保结构化输出 符合业务规则，不符合就重试
    作用时机	调用 LLM 后，负责 “解析转换”	转换后（或过程中），负责 “验证 + 重试”
    核心逻辑	格式解析（比如 JSON → Java 对象）	规则校验（比如 “age 必须是 18-60 的整数”）+ 自动重试
    举个例子	把 AI 输出的 JSON 转成 UserInfo 对象	检查 UserInfo 的 age 是不是整数、email 是不是合法格式，不合格就让 AI 重新生成
2. 通俗类比：转换器 = 翻译官，递归顾问 = 质检 + 返工员
    翻译官（转换器）：把外国人（LLM）说的话（文本输出）翻译成中文（结构化数据），确保 “能看懂”；
    质检 + 返工员（递归顾问）：检查翻译后的内容（结构化数据）是不是符合要求（比如 “必须包含 3 个字段，年龄是数字”），不符合就让翻译官（LLM）重新翻译（重试），直到符合要求。
            3. 搭配使用的完整流程（实战场景）
    比如 “让 AI 生成用户信息，必须包含 username/age/email，age 是 18-60 整数，email 合法”：
    用 BeanOutputConverter：让 AI 输出 JSON，自动转成 UserInfo 对象（解决 “转换问题”）；
    用 StructuredOutputValidationAdvisor（递归顾问）：验证 UserInfo 的 age 范围、email 格式（解决 “合规问题”）；
    若验证失败（比如 age 是字符串、email 缺失），递归顾问自动让 AI 重试（带着错误原因），直到生成符合要求的 UserInfo 对象。
    流程闭环：用户请求 → 转换器给 LLM 加格式指令 → LLM 输出文本 → 转换器转成 UserInfo → 递归顾问验证 → 符合要求返回 / 不符合重试
    关键补充：两者的 “边界”
    转换器只做 “基础格式转换”：比如 JSON 字符串转 Java 对象，但不做 “业务规则校验”（比如 age 范围、email 合法性）；
    递归顾问不做 “转换”：它依赖转换器已经把输出转成结构化数据，再做 “业务规则校验 + 重试”；
    转换器可以单独使用（如果 LLM 输出格式 100% 靠谱），但递归顾问通常需要和转换器搭配（否则没法校验结构化数据）。
    总结
    结构化输出转换器：解决 “LLM 文本 → 程序结构化数据” 的转换问题，省掉手动解析代码；
    递归顾问：解决 “结构化数据 → 符合业务规则” 的保障问题，省掉手动重试、提示词优化代码；
    关系：互补工具，搭配使用能实现 “AI 输出 → 合规结构化数据” 的全自动化，是 Spring AI 中构建可靠 AI 应用的核心组合。*/



}
