package com.zhh.handsome;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    public static void main(String[] args) {
        /*项目启动，预估超过10亿的文档数据要存储，那么我们选择Elasticsearch or Mongodb？

        明确两者定位
        MongoDB和Elasticsearch都属于NoSQL范畴的数据库,且都属于文档型数据存储数据库。

        所以这两者的众多功能和特性高度重合, 但其实两者定位还是有所不同。

        MongoDB是文档型数据库, 提供数据存储和管理服务。

        Elasticsearch作为一个搜索引擎,定位是提供数据检索服务,也就是说重点是全文索引，即模糊匹配。

        因此，Elasticsearch的设计会有所偏重，比如Mapping不可变,带来的代价就是es不特别擅长作为纯文档数据的管理者, es可以从其他数据源同步数据过来提供全文检索和查询,不特别擅长自己对数据进行存储和管理。

        MongoDB有多个存储引擎可以选择, 而且MongoDB不仅看重数据的分析, 对数据的管理同样看重, 总的来说MongoDB更倾向于数据的存储和管理, 可以作为数据源对外提供。

        Elasticsearch则有很多插件可以使用,相对来讲Elasticsearch更倾向于数据的查询, 一般情况下elasticsearch仅作为数据检索服务和数据分析平台, 不直接作为源数据管理者.

                所以，如果系统中已有mongodb或其他数据库作为主要数据存储，而Elasticsearch主要负责从其中获取部分数据提供快速全文检索即可，即mongdob+Elasticsearch的方案.

                此文我更想阐述的是，当项目考虑物理资源、运维成本等方面限制时，不想同时引入两套数据库时，二者只能选择一个时，我们选哪个呢？？

        全文检索的需求
        首先，要仔细思考项目需求中，是否存在对全文检索的需求，如果存在，检索的条件是否复杂？是否很花式？检索的性能要求是否非常高？

        如果答案都是yes，那么基本上可以确认就得选Elasticsearch了，一票否决mongodb。

        Mongodb是可以满足基本的模糊查询功能的，我们在实际项目中，3个节点Mongodb集群内存有4000万业务数据，在一个业务内容上用regex模糊查询一个关键词，只模糊查前100条，基本可以1秒内返回。

        但是更高级一点的模糊查询就很难支持了，并且涉及查询count总量时就非常慢，经常10秒以上才能返回结果。

        所以评估项目是否对全文检索有比较高的需求要重点考量。

        字段是否经常变换
        如果业务重点在于数据的增删改查，全文检索的要求不高，那么Mongodb可能更适合。

        比如，电商业务一个基本的功能模块就是存储各种品类的商品信息，各种商品的特性和参数各异，MongoDB灵活的文档模型非常适合于这类业务。由于商品的品类繁多，存入集合中的每一种商品在字段上都有差异，并且未来还会添加新品类的商品。

        这种数据字段预期未来会经常变动，显然mongodb更好，ES字段变动时处理起来比较麻烦，需要经常变更mapping，代价很大，一般需要重新写入一个新的index，做reindexing来处理，在数据量达到一亿以上时，需要大半天才能完成，并且对线上写入的业务是有一定影响的。

        所以对于数据结构经常频繁变化，一个集合中存储多种字段不同的数据时，用monggodb会更好！

        硬件资源方面
        如果从资源占用方面角度看，MongoDB可以支持存储文件类型的数据, 作为数据库也有数据压缩能力, es则因为大量的索引存在需要占用大量的磁盘和内存空间。

        在mongodb不需要建太多索引的情况下，mongodb可能更节省一些资源，当然影响最后占用内存和磁盘空间的因素较多，这个也不完全绝对，所以需要根据实际情况去测一下。

        运维部署
        在运维部署方面，ES的一套工具ELK，现在叫Elastic Stash，自带对集群的状态监控，安装部署也较mongodb方便太多，对运维人员来说相比mongodb容易上手太多。

        在弹性伸缩方面，ES相比mongodb也容易太多，真的容易太多，并且，ES水平扩展更容易，能够自动均衡！

        可以负责的说mongodb对运维部署人员的要求要比ES明显要高很多。用ES集群，你会明显感觉你对它的掌控力更强。

        所以，在监控运维方面，ES明显更具优势。

        作者：洪文聊架构
        链接：https://www.jianshu.com/p/5157bc105803
        来源：简书
        著作权归作者所有。商业转载请联系作者获得授权，非商业转载请注明出处。*/
    }
















/*


 在 MongoDB 中，数据库（Database）和集合（Collection）是核心概念（集合类似关系型数据库的 “表”），以下是常用操作命令及字段类型的详细说明：
    一、数据库（Database）操作常用命令
    MongoDB 中，数据库是集合的容器，一个 MongoDB 实例可以包含多个数据库，每个数据库独立存储数据。
            1. 切换 / 创建数据库
            bash
    use 数据库名
    若数据库不存在，use命令会惰性创建（仅当插入数据时才真正创建数据库）。
    示例：切换到mydb数据库（若不存在，后续插入数据时会创建）：
    bash
    use mydb
2. 查看所有数据库
            bash
    show dbs  # 或 show databases
    仅显示包含数据的数据库（空数据库不会显示）。
            3. 查看当前数据库
            bash
    db  # 无参数，返回当前所在数据库名
4. 删除当前数据库
            bash
db.dropDatabase()
    注意：需先通过use 数据库名切换到目标数据库，再执行此命令。
    示例：删除mydb数据库：
    bash
    use mydb
db.dropDatabase()  # 返回 { "dropped" : "mydb", "ok" : 1 } 表示成功
    二、集合（Collection）操作常用命令
    集合是文档（Document，类似 “行”）的容器，一个数据库可以包含多个集合，集合名区分大小写。
            1. 创建集合
            bash
db.createCollection("集合名", [选项])
    选项（可选）：用于配置集合属性，如capped（固定大小集合）、size（集合最大字节数）、max（最大文档数）等。
    示例：创建一个名为users的普通集合：
    bash
db.createCollection("users")
    简化方式：直接向不存在的集合插入文档，MongoDB 会自动创建集合：
    bash
db.users.insertOne({name: "Alice"})  # 自动创建users集合
2. 查看当前数据库的所有集合
            bash
    show collections  # 或 show tables
3. 删除集合
            bash
db.集合名.drop()
    示例：删除users集合：
    bash
db.users.drop()  # 返回 true 表示成功，false 表示集合不存在
4. 重命名集合
            bash
db.旧集合名.renameCollection("新集合名")
    示例：将users重命名为user_info：
    bash
db.users.renameCollection("user_info")
        5. 查看集合统计信息
            bash
db.集合名.stats()
    返回集合的文档数量、大小、存储空间等信息。
    三、MongoDB 字段类型
    MongoDB 是文档型数据库，文档由键值对（field: value）组成，值的类型灵活多样，支持以下主要类型：
            1. 基本类型
    String（字符串）：最常用类型，存储 UTF-8 编码的字符串。示例：{name: "张三"}
    Number（数值）：支持多种数值类型：
            32 位整数（int）
            64 位整数（long）
            64 位浮点数（double，默认数值类型）
            128 位十进制小数（decimal，用于高精度计算）
    示例：{age: 25, score: 98.5, count: NumberLong(1000000)}
    Boolean（布尔）：存储true或false。示例：{isStudent: true}
    Null（空值）：表示字段不存在或为空。示例：{address: null}
2. 特殊类型
    ObjectId：文档的唯一标识（类似主键），通常作为_id字段自动生成。
    结构：12 字节（包含时间戳、机器 ID、进程 ID、自增计数器），确保全球唯一。
    示例：{_id: ObjectId("650a8b3f6f1e8c3d4e5f6a7b")}
    Date（日期）：存储时间戳（毫秒级，从 1970-01-01 UTC 开始）。示例：{createTime: new Date()}（当前时间）或{birth: new Date("2000-01-01")}
    Array（数组）：存储有序列表，元素可以是不同类型。示例：{hobbies: ["reading", "running", 2023]}（字符串、数字混合）
    Object（嵌入式文档）：文档中嵌套另一个文档，用于表示复杂结构。示例：{user: {name: "李四", age: 30}}
    Regular Expression（正则表达式）：用于字符串匹配查询。示例：{name: /^张/}（匹配以 “张” 开头的 name）
    Binary Data（二进制数据）：存储二进制数据（如图片、文件），通常用于小文件（大文件建议用 GridFS）。
    Timestamp（时间戳）：用于记录文档修改或创建的时间（内部使用，精度高于 Date）。


    */







   /* 1. admin 库：系统管理与权限核心库
    admin 库是 MongoDB 的最高权限管理库，主要用于存储系统级配置、用户账号与权限信息，是 MongoDB 权限控制的核心载体。
    核心作用：
    存储全局用户与权限：MongoDB 的 “全局用户”（对所有数据库有权限的用户）和 “管理员用户”（如root角色用户）的账号信息（如用户名、加密密码）、角色权限均存储在 admin 库的 system.users 集合中。例：创建一个拥有所有数据库权限的root用户时，必须指定在admin库下：
    javascript
            运行
    use admin;
db.createUser({
        user: "root",
                pwd: "123456",
                roles: [{ role: "root", db: "admin" }] // root角色仅在admin库生效
    });
    集群级管理操作入口：MongoDB 中部分 “集群级命令”（如副本集初始化、分片集群启用、关闭 MongoDB 服务）必须在 admin 库下执行，否则权限不足。例：
    关闭 MongoDB 服务：use admin; db.shutdownServer();
    启用分片集群：use admin; sh.enableSharding("目标数据库名");
    存储系统级配置：部分 MongoDB 的系统配置（如分片集群的认证密钥、全局参数）也会存储在 admin 库的特定集合中（如 system.version 存储 MongoDB 版本信息）。
            2. local 库：本地数据存储库（不复制）
    local 库是 MongoDB 的本地实例专属库，核心特点是：库内所有数据仅存储在当前 MongoDB 实例中，不会被复制到副本集的其他节点或分片集群的其他分片。
    核心作用：
    存储副本集的操作日志（oplog）：这是 local 库最核心的功能。在副本集（Replica Set）环境中，主节点（Primary）会将所有写操作（插入、更新、删除）记录到 local 库的 oplog.rs 集合中，从节点（Secondary）通过读取该集合的日志来同步主节点的数据，保证副本集数据一致性。
    oplog.rs 是一个固定大小的集合（类似 “循环日志”），超出容量后会覆盖旧日志，因此需合理配置其大小（默认约为磁盘容量的 5%）。
    存储本地专属数据：如果需要存储 “仅当前实例可用、无需复制到其他节点” 的数据（如单实例的临时日志、本地配置），可存放在 local 库中。例如：单机环境下的临时计算结果，无需同步到其他节点，直接写入 local.temp_data 集合即可。
    注意：
    副本集环境中，每个节点的 local 库数据独立，从节点的 oplog.rs 仅用于同步主节点数据，不可直接修改。
    单机环境下，local 库可用于存储无需持久化或仅本地使用的数据，但通常很少手动操作。
            3. config 库：分片集群（Sharded Cluster）配置库
    config 库仅在 分片集群环境 中发挥作用，单机或副本集环境下，config 库通常为空且无实际用途。它的核心功能是存储分片集群的所有元数据（配置信息），供 mongos 路由节点读取，以实现数据的正确路由。
    核心作用：
    存储分片集群的拓扑信息：包括分片节点（shard）、mongos 路由节点、配置服务器（config server）的地址与状态，存储在 config.shards、config.mongos 等集合中。
    存储数据分片规则：
    哪些数据库 / 集合开启了分片（config.databases 集合）；
    集合的分片键（Shard Key）配置（config.collections 集合）；
    数据块（Chunk）的分布（config.chunks 集合）：记录每个数据块属于哪个分片、数据范围等，mongos 正是通过该集合判断 “用户请求的数据在哪个分片上”，从而实现路由。*/

}