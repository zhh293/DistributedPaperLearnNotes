package org.example.es.test;


import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.GetIndexResponse;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

@Slf4j
public class Rest风格 {
    public static void main(String[] args) throws IOException {
        RestHighLevelClient esClient = new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost("localhost", 9200, "http")
                )
        );
        CreateIndexRequest createIndexRequest = new CreateIndexRequest("user");
        CreateIndexResponse createIndexResponse = esClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
        System.out.println(createIndexResponse.isAcknowledged());

        GetIndexRequest getIndexRequest = new GetIndexRequest("user");
        GetIndexResponse getIndexResponse = esClient.indices().get(getIndexRequest, RequestOptions.DEFAULT);
        Map<String, MappingMetadata> mappings =
                getIndexResponse.getMappings();
        mappings.forEach((k, v) -> {
            System.out.println(k);
            System.out.println(v);
        });

        DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest("user");
        AcknowledgedResponse delete = esClient.indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
        System.out.println(delete.isAcknowledged());

        User user=User.builder()
                .name("张飞")
                .sex("男")
                .age(18)
                .build();
        IndexRequest indexRequest = new IndexRequest("user");
        indexRequest.source(JSON.toJSONString(user), XContentType.JSON);
        indexRequest.id("1");
        IndexResponse index = esClient.index(indexRequest, RequestOptions.DEFAULT);
        System.out.println(index);


        GetRequest getRequest = new GetRequest("user", "1");
        GetResponse getResponse = esClient.get(getRequest, RequestOptions.DEFAULT);
        System.out.println(getResponse.isExists());


        UpdateRequest updateRequest = new UpdateRequest("user","1");
        UpdateRequest doc = updateRequest.doc(JSON.toJSONString(user), XContentType.JSON);

        doc.id("1");
        UpdateResponse updateResponse = esClient.update(updateRequest, RequestOptions.DEFAULT);
        System.out.println(updateResponse);





        DeleteRequest deleteRequest = new DeleteRequest("user", "1");
        DeleteResponse deleteResponse = esClient.delete(deleteRequest, RequestOptions.DEFAULT);
        System.out.println(deleteResponse);



        BulkRequest bulkRequest = new BulkRequest();
        //批量执行请求
        bulkRequest.add(new IndexRequest("user").id("1"),new DeleteRequest("user").id("1"));








       /* //创建索引（建表）
        CreateIndexRequest request = new CreateIndexRequest("my_index");
        CreateIndexResponse createIndexResponse = esClient.indices().create(request, RequestOptions.DEFAULT);
        System.out.println(createIndexResponse);
        //获取索引
        GetIndexRequest getIndexRequest = new GetIndexRequest("my_index");
        boolean exists = esClient.indices().exists(getIndexRequest, RequestOptions.DEFAULT);
        System.out.println(exists);
        //删除索引
        AcknowledgedResponse delete = esClient.indices().delete(new DeleteIndexRequest("my_index"), RequestOptions.DEFAULT);
        System.out.println(delete.isAcknowledged());
        //文档的操作
        User user = User.builder()
                .name("张三")
                .age(18)
                .sex("男")
                .tags(new String[]{"技术", "教程"}).build();
        IndexRequest request1 = new IndexRequest("my_index");
        request1.id("1");
        request1.timeout("1s");
        request1.timeout(TimeValue.timeValueSeconds(1));
        IndexRequest source = request1.source(JSON.toJSONString(user), XContentType.JSON);
        IndexResponse index = esClient.index(source, RequestOptions.DEFAULT);
        System.out.println(index.getResult());





        //模糊查询文档
        IndexRequest request2 = new IndexRequest("my_index");
        request1.id("1");
        GetRequest myIndex = new GetRequest("my_index", "1");
        //获取想要的字段和过滤不想要的字段
        myIndex.fetchSourceContext(new FetchSourceContext(true, new String[]{"name"}, null));
        myIndex.storedFields("name");
        boolean exists1 = esClient.exists(myIndex, RequestOptions.DEFAULT);
        GetResponse documentFields = esClient.get(myIndex, RequestOptions.DEFAULT);
        String sourceAsString = documentFields.getSourceAsString();
        System.out.println(sourceAsString);
        Map<String, Object> sourceAsMap = documentFields.getSourceAsMap();
        Set<Map.Entry<String, Object>> entries = sourceAsMap.entrySet();
        for(Map.Entry<String, Object> entry : entries){
            System.out.println(entry.getKey() + ":" + entry.getValue());
        }
        System.out.println(exists1);



        UpdateRequest myIndex1 = new UpdateRequest("my_index", "1");
        myIndex1.timeout("1s");
        UpdateRequest source1 = myIndex1.doc(JSON.toJSONString(user), XContentType.JSON);
        UpdateResponse update = esClient.update(source1, RequestOptions.DEFAULT);
        System.out.println(update.getResult());


        DeleteRequest myIndex2 = new DeleteRequest("my_index", "1");
        myIndex2.timeout("1s");
        DeleteResponse delete1 = esClient.delete(myIndex2, RequestOptions.DEFAULT);
        System.out.println(delete1.getResult());


        BulkRequest bulkRequest = new BulkRequest();
        bulkRequest.timeout("1s");
        ArrayList<User> users = new ArrayList<>();
        users.add(User.builder()
                .name("张三")
                .age(18)
                .sex("男")
                .tags(new String[]{"技术", "教程"}).build());
        users.add(User.builder()
                .name("张三")
                .age(199)
                .sex("男")
                .tags(new String[]{"技术", "教程"}).build());
        for(User user1 : users){
            IndexRequest request3 = new IndexRequest("my_index");
            request3.timeout("1s");
            request3.timeout(TimeValue.timeValueSeconds(1));
            bulkRequest.add(request3.source(JSON.toJSONString(user1), XContentType.JSON));
        }
        BulkResponse bulk = esClient.bulk(bulkRequest, RequestOptions.DEFAULT);
        System.out.println(bulk.status());
        System.out.println(bulk.hasFailures());*/


        //查询

    }
}
/*一、GetRequest 的核心方法及作用
GetRequest 的方法主要用于配置获取文档的参数（如索引名、文档 ID、返回字段等），以下是常用方法及用法：
        1. 构造方法
用于初始化请求，指定要获取文档的索引名和文档 ID：

java
        运行
// 方式1：直接在构造时指定索引和ID
GetRequest getRequest = new GetRequest("索引名", "文档ID");

// 方式2：先创建对象，再设置索引和ID
GetRequest getRequest = new GetRequest();
getRequest.index("索引名"); // 设置索引
getRequest.id("文档ID");   // 设置文档ID
2. 控制返回字段（按需获取字段，减少网络传输）
fetchSourceContext(FetchSourceContext)：通过 FetchSourceContext 配置需要返回或排除的字段。
java
        运行


// 示例：只返回 "name" 和 "age" 字段
String [] includeFields = new String []{"name", "age"};
String [] excludeFields = new String []{}; // 不排除任何字段
FetchSourceContext fetchSourceContext = new FetchSourceContext (true, includeFields, excludeFields);
getRequest.fetchSourceContext (fetchSourceContext);

plaintext

- 简化写法：`setFetchSource(String[] includes, String[] excludes)`
        ```java
// 只返回 "title" 字段，排除 "content" 字段
getRequest.setFetchSource(new String[]{"title"}, new String[]{"content"});
        3. 版本控制（乐观锁控制）
ES 中每个文档有版本号（version），可通过版本控制避免并发修改冲突：

version(long version)：指定要获取的文档版本。
versionType(VersionType type)：指定版本类型（如 EXTERNAL、INTERNAL 等）。
java
        运行


// 只获取版本号为 3 的文档（如果版本不匹配，会抛出异常）
getRequest.version (3);
getRequest.versionType (VersionType.EXTERNAL);

plaintext


#### 4. 实时性控制
- `realtime(Boolean realtime)`：是否实时获取文档（默认 `true`）。
ES 文档写入后会先存到内存，实时获取（realtime=true）会直接从内存读取；若为 `false`，则等待文档刷新到磁盘后再获取（可能有延迟，但更可靠）。
        ```java
getRequest.realtime(false); // 不实时获取，等待刷新



5. 其他常用方法
routing(String routing)：如果索引使用了路由（routing）策略，需指定路由值才能正确找到文档。
java
        运行




getRequest.routing ("user123"); // 按路由值 "user123" 查找文档

plaintext
- `preference(String preference)`：指定查询偏好（如优先从主分片还是副本分片获取）。
        ```java
getRequest.preference("_primary"); // 只从主分片获取文档*/







/*所有复杂查询的 JSON 结构都遵循以下基本框架（各字段可选，按需添加）：

json
{
    "query": {...},        // 核心查询条件（计算相关性得分）
    "filter": {...},       // 过滤条件（不计算得分，仅筛选，性能更好）
    "sort": [...],         // 排序规则
    "from": 0,             // 分页起始位置（默认0，即第1条）
        "size": 10,            // 每页返回数量（默认10）
        "aggs": {...},         // 聚合分析（统计、分组等）
    "highlight": {...},    // 结果高亮（标记匹配的关键词）
    "_source": ["字段1", "字段2"]  // 指定返回的字段（默认返回所有字段）
}*/



/*
二、核心部分：query 子句（相关性查询）
query 用于描述 “按什么条件查询”，并会计算每条结果的 相关性得分（_score），得分越高说明匹配度越高，默认按得分降序排序。
根据查询场景不同，query 支持多种查询类型，可分为 3 大类：
        1. 全文查询（针对 text 类型字段，支持分词匹配）
用于对 “文本内容” 进行模糊 / 关键词搜索（如搜索文章中的 “ Elasticsearch 教程”）。

查询类型	作用	示例（查询 title 中包含 “Elasticsearch” 或 “教程” 的文档）
match	对字段进行分词后匹配，支持模糊搜索	json { "query": { "match": { "title": "Elasticsearch 教程" } } }
match_phrase	精确匹配短语（分词后顺序一致，中间可插入少量其他词）	json { "query": { "match_phrase": { "title": "Elasticsearch 教程", "slop": 1 } } } （slop=1 允许中间插入 1 个词）
multi_match	同时在多个字段中匹配关键词	json { "query": { "multi_match": { "query": "教程", "fields": ["title", "content"] } } } （在 title 和 content 中搜 “教程”）
query_string	支持通配符、逻辑运算符（AND/OR）的高级搜索	json { "query": { "query_string": { "query": "(Elasticsearch OR Kibana) AND 教程", "default_field": "title" } } }
        2. 术语级查询（针对精确值，如 keyword/ 数值 / 日期等，不分词）
用于 “精确匹配”“范围匹配” 等场景（如匹配标签 tag: "技术"、年龄 age: 30）。

查询类型	作用	示例
term	精确匹配单个值（适合 keyword/ 数值）	json { "query": { "term": { "tag": "技术" } } } （精确匹配 tag 为 “技术”）
terms	匹配多个值中的任意一个	json { "query": { "terms": { "tag": ["技术", "教程"] } } } （tag 是 “技术” 或 “教程”）
range	范围匹配（数值 / 日期）	json { "query": { "range": { "age": { "gte": 18, "lte": 30 } } } } （age ≥18 且 ≤30，gte=≥，lte=≤）
exists	匹配 “字段存在” 的文档	json { "query": { "exists": { "field": "email" } } } （包含 email 字段的文档）
missing	匹配 “字段不存在” 的文档（已过时，推荐用 bool + must_not + exists）	json { "query": { "bool": { "must_not": { "exists": { "field": "email" } } } }
    3. 复合查询（组合多个条件，实现复杂逻辑）
    最常用的是 bool 查询，通过 must/must_not/should/filter 四个子句组合条件，类似 SQL 中的 AND/NOT/OR。

    子句	作用	是否影响相关性得分
    must	必须满足的条件（类似 AND）	是（参与得分计算）
    must_not	必须不满足的条件（类似 NOT）	否
    should	满足任意一个即可（类似 OR）	是（满足越多得分越高）
    filter	过滤条件（类似 WHERE，但不计算得分）	否（性能更好，可缓存）

    示例：查询 “age 在 18-30 之间，tag 包含 “技术”，且 title 不包含 “广告” 的文档”

    json
    {
        "query": {
        "bool": {
            "must": [
            { "term": { "tag": "技术" } }  // 必须满足：tag=技术
      ],
            "must_not": [
            { "match": { "title": "广告" } }  // 必须不满足：title包含广告
      ],
            "filter": [  // 过滤条件（不影响得分）
            { "range": { "age": { "gte": 18, "lte": 30 } } }
      ]
        }
    }
    }
    三、filter 子句（高效过滤，不计算得分）
    filter 与 query 中的 filter 子句功能一致，用于 “只过滤不评分” 的场景（如按状态、时间范围筛选）。单独的 filter 子句在旧版本中常用，现在更推荐在 bool 查询中使用 filter 子句（结构更清晰）。

    示例：过滤出 status: "active" 且 create_time 在 2023 年的文档

            json
    {
        "filter": {
        "bool": {
            "must": [
            { "term": { "status": "active" } },
            { "range": { "create_time": { "gte": "2023-01-01", "lte": "2023-12-31" } } }
      ]
        }
    }
    }
    四、sort 子句（排序）
    默认按相关性得分（_score）降序排序，sort 可指定按字段排序，支持多字段排序。

    示例：先按 create_time 降序（最新的在前），再按 age 升序

            json
    {
        "query": { "match_all": {} },  // 匹配所有文档
        "sort": [
        { "create_time": { "order": "desc" } },  // 第一排序字段：create_time降序
        { "age": { "order": "asc" } }            // 第二排序字段：age升序
  ]
    }*/




/*highlight 子句（结果高亮）
在查询结果中，用 <em> 等标签标记匹配的关键词（方便前端展示）。

示例：高亮 title 中匹配 “Elasticsearch” 的部分

        json
{
        "query": { "match": { "title": "Elasticsearch" } },
        "highlight": {
        "fields": { "title": {} },  // 对title字段高亮
        "pre_tags": ["<strong>"],   // 自定义前缀标签（默认<em>）
        "post_tags": ["</strong>"]  // 自定义后缀标签
        }
        }


返回结果中会新增 highlight 字段：

json
{
    "hits": {
    "hits": [
    {
        "_source": { "title": "Elasticsearch 入门教程" },
        "highlight": { "title": ["<strong>Elasticsearch</strong> 入门教程"] }
    }
    ]
}
}*/





/*
Kibana 是 Elastic Stack（由 Elasticsearch、Logstash、Beats 等组成的数据分析与搜索套件）的 “可视化与管理中心”，可以理解为 “Elasticsearch 的图形化操作界面 + 数据仪表盘 + 运维监控面板”。它的核心作用是让技术人员（开发者、运维、分析师）和业务人员能 **“看懂数据、管理集群、排查问题”**，无需编写复杂代码就能发挥 Elasticsearch 的威力。
一、对 “非技术人员”：把数据变成 “一眼看懂的图表”
如果把 Elasticsearch 比作 “仓库”（存储海量数据），Kibana 就是 “仓库的可视化展厅”—— 用图表、地图、仪表盘把枯燥的数字变成直观的趋势图。

场景 1：看销售趋势
把 Elasticsearch 里的 “订单数据” 拖进 Kibana，瞬间生成 “近 30 天销售额折线图”“各地区销量柱状图”，甚至能在地图上标出发货量最高的城市。
场景 2：分析用户行为
把 “网站访问日志” 导入后，用 Kibana 做 “用户点击热图”“不同渠道访客转化率对比”，不用写公式，拖一拖字段就能生成报表。
二、对 “运维 / 管理员”：不用命令行，也能管理整个 Elastic 集群
Kibana 是 Elasticsearch 的 “控制面板”，能一站式完成 **“数据导入、集群监控、用户权限管理”**—— 相当于 “ Elasticsearch 的操作系统界面”。

场景 1：导入数据
不用写代码，通过 Kibana 的 “数据导入向导”，把 CSV/Excel 文件、数据库数据（如 MySQL）直接导入 Elasticsearch，自动识别字段类型。
场景 2：监控服务器状态
实时查看 Elasticsearch 集群的 “CPU 使用率”“内存占用”“分片分布”，如果某个节点宕机，Kibana 会弹出警报（类似 “手机电量低提醒”）。
场景 3：管理用户权限
给不同团队设置权限（如 “运维组能看服务器日志，市场组只能看销售数据”），避免敏感信息泄露。

五、一句话总结 Kibana 的价值
Kibana 是 **“让 Elasticsearch 从‘黑盒’变成‘透明车间’的工具”**—— 无论你是想 “看数据趋势”“管服务器” 还是 “修代码 bug”，都能在一个界面里完成，
不用再记复杂命令或写 SQL。它把 “需要技术门槛的数据分析” 变成了 “拖一拖、点一点就能懂的可视化故事”。*/


/*一、核心数据类型
1. 字符串类型
text：用于全文搜索，会被分词器解析为多个词条（如 “hello world” 会拆分为 “hello”“world”），适合模糊查询，但不支持排序 / 聚合。
keyword：用于精确值（如标签、ID），不会分词，支持排序、聚合和过滤（如精确匹配 “hello world”）。
        2. 数值类型
整数型：long（64 位）、integer（32 位）、short（16 位）、byte（8 位）、unsigned_long（无符号长整型，预览特性）。
浮点型：double（64 位）、float（32 位）、half_float（16 位）、scaled_float（缩放浮点，通过 “缩放因子” 将小数转为整数存储，如缩放因子为 100 时，1.23 会存为 123）。
        3. 日期与布尔
date：存储日期时间，支持格式化字符串（如 “2025-08-31”）、时间戳（毫秒 / 秒级），默认转换为 UTC 存储。
boolean：存储布尔值（true/false），也支持字符串（“true”/“false”）或数字（1/0）的自动转换。
        4. 二进制与空值
binary：存储 Base64 编码的二进制数据（如图片），默认不索引，需显式启用存储。
        null：表示字段无值（ES 中字段默认可为空，无需显式声明）。
二、复杂数据类型
1. 对象与嵌套
object：存储嵌套 JSON 对象（如{"user": {"name": "Alice", "age": 30}}），但内部对象会被 “扁平化” 存储（如user.name、user.age），导致跨对象查询时可能出现意外（如 “name=Alice 且 age=30” 会匹配所有包含 Alice 或 30 的对象，而非同一对象）。
nested：解决object的扁平化问题，将每个嵌套对象视为独立文档存储，支持 “同一嵌套对象内多条件同时匹配”（如 “user.name=Alice 且 user.age=30” 仅匹配同时满足的对象）。
        2. 数组
ES 无专门 “数组类型”，但任何字段默认支持多值（数组），且数组内所有元素必须为同一类型（如"tags": ["red", "blue"]）。
三、地理与空间数据类型
1. 地理点（geo_point）
存储经纬度坐标（如{"lat": 39.9042, "lon": 116.4074}），支持：

地理范围查询（如 “距离某点 5 公里内的文档”）；
地理聚合（如按区域分组统计）。
        2. 地理形状（geo_shape）
存储复杂地理形状（如多边形、线），支持 “形状包含 / 相交” 等空间关系查询（如 “查询与某多边形相交的区域”）。*/



/*
在 Elasticsearch（ES）中，索引（Index） 和 文档 ID（Document ID） 是数据组织的两个核心概念，二者既有层级关联，又有功能差异，具体关系和区别如下：
一、核心定义与关系
1. 索引：文档的 “容器” 与 “逻辑分组”
索引是 ES 中存储数据的顶层逻辑单元，类似传统关系型数据库的 “数据库” 或 “表”，用于组织具有相似特征的文档（如 “用户索引” 存储所有用户数据，“订单索引” 存储所有订单数据）。
每个索引有唯一名称（如 users），且可配置分片（Shard）、副本（Replica）、映射（Mapping）等规则，决定数据的存储和检索策略。
        2. 文档 ID：单文档的 “唯一标识”
文档是 ES 中最小的数据单元（类似数据库的 “行”），以 JSON 格式存储；每个文档必须有一个唯一 ID（_id），用于精准定位某一个文档。
文档 ID 可以由用户手动指定（如 PUT /users/_doc/1 中的 1），也可由 ES 自动生成（如 POST /users/_doc 时，ES 会生成类似 a1b2c3d4... 的随机 UUID）。
        3. 层级关系：文档属于索引
文档必须归属到某个索引下，通过元数据字段 _index 记录其所属索引（如某文档的 _index: "users" 表示它属于 users 索引）。
索引是文档的 “父容器”，一个索引可包含数百万甚至数十亿文档，但单个文档只能属于一个索引（除非通过 “跨索引搜索” 或 “别名” 间接关联）。

*/



/*
method	url 地址	描述
PUT	localhost:9200/索引名称 / 类型名称 / 文档 id	创建文档（指定文档 id）
POST	localhost:9200/索引名称 / 类型名称	创建文档（随机文档 id）
POST	localhost:9200/索引名称 / 类型名称 / 文档 id/_update	修改文档
DELETE	localhost:9200/索引名称 / 类型名称 / 文档 id	删除文档
GET	localhost:9200/索引名称 / 类型名称 / 文档 id	查询文档（通过文档 id）
POST	localhost:9200/索引名称 / 类型名称 /_search	查询数据（在指定索引、类型下执行搜索，可通过请求体添加查询条件，获取符合条件的结果，若不指定条件则可查询该范围下的所有数据）*/


//ES8之后类型名称改为_doc，这是系统默认的，跳过不写即可

/*
1. PUT / 索引名 / 类型名 / 文档 id → 创建文档（指定 ID）
设计目的：解决 “需要手动指定文档唯一标识（ID）” 的场景（如数据迁移时，要保留原系统的 ID）。
原理依据：
HTTP 语义适配：HTTP 协议中，PUT 的核心语义是 “全量替换 / 创建资源”，且具备 幂等性（多次执行结果完全一致）。
若该 文档id 不存在：执行 “创建”，生成新文档；
若该 文档id 已存在：执行 “全量覆盖”（原文档所有字段被新内容替换）—— 这正是 “指定 ID 时，创建 / 更新二合一” 的需求。
资源定位精准：URL 中的 文档id 直接绑定资源，避免歧义（明确知道操作的是 “哪一个文档”）。*/



/*

POST / 索引名 / 类型名 → 创建文档（随机 ID）
设计目的：解决 “无需手动指定 ID，让 ES 自动生成唯一标识” 的场景（如日志采集、实时数据写入，无需关心具体 ID）。
原理依据：
HTTP 语义适配：HTTP 中 POST 的核心语义是 “提交数据、创建新资源”，且 非幂等性（多次执行会生成多个不同资源）。
因为不指定 文档id，ES 会自动生成一个 20 位的 UUID（如 1a2b3c...），每次调用 POST 都会生成新 UUID 和新文档 —— 完全符合 POST 非幂等的特性。
降低用户成本：无需用户手动保证 文档id 的唯一性（避免 ID 冲突），ES 自动处理，适合高频、批量的写入场景。*/



/*
3. POST / 索引名 / 类型名 / 文档 id/_update → 修改文档
设计目的：解决 “部分字段更新” 的需求（区别于 PUT 的 “全量覆盖”）。
原理依据：
与 PUT 做功能区分：若用 PUT /索引名/类型名/文档id 修改，必须传入文档的所有字段（全量覆盖）；而 _update 端点允许只传 “要修改的字段”（如只改 name，不用传 age/gender），更灵活、节省带宽。
HTTP 语义的折中：虽然 _update 是 “修改资源”，但 HTTP 中没有专门的 “部分更新” 方法（PUT 是全量，PATCH 语义模糊且支持度低）。ES 选择用 POST 承载：
一方面，_update 可能包含复杂逻辑（如用脚本修改字段："script": "ctx._source.age += 1"），需要通过 HTTP 请求体 传递（URL 传参长度有限制）；
另一方面，_update 是非幂等的（如 “年龄 + 1” 执行 1 次和 10 次结果不同），符合 POST 非幂等的特性。*/



/*
4. DELETE / 索引名 / 类型名 / 文档 id → 删除文档
设计目的：精准删除某一个具体文档，语义无歧义。
原理依据：
HTTP 语义完全匹配：HTTP 中 DELETE 的唯一语义就是 “删除指定资源”，且具备幂等性 ——
第一次调用：文档存在，删除成功（返回 200）；
后续调用：文档已不存在，返回 404，但 “最终结果（文档不存在）” 一致，不会产生副作用。
操作安全性：DELETE 必须通过 文档id 定位资源，避免 “批量误删”（若要批量删除，需用 _delete_by_query 等特殊端点，且有额外限制），降低操作风险。*/



/*
GET / 索引名 / 类型名 / 文档 id → 查询文档（通过 ID）
设计目的：快速获取 “已知唯一 ID 的单个文档”（类似数据库的 SELECT * FROM 表 WHERE id=xxx）。
原理依据：
HTTP 语义完全匹配：HTTP 中 GET 的核心语义是 “获取资源，不修改任何状态”，且具备幂等性（多次查询结果一致，不影响数据）。
性能最优：ES 会将文档的 id 映射到具体的分片（Shard），通过 GET + 文档id 可直接路由到目标分片，无需遍历，查询速度极快（毫秒级）。
*/


/*

POST / 索引名 / 类型名 /_search → 复杂查询（批量 / 条件搜索）
设计目的：解决 “多条件、大批量、复杂逻辑的查询” 需求（如 “查询年龄 > 30 且城市 = 北京的用户”“按评分排序”）。
原理依据：
规避 GET 请求的局限性：虽然 GET 也能做简单查询（如 GET /索引名/_search?q=age:30），但有两个致命问题：
URL 长度限制：复杂条件（如多字段匹配、过滤、聚合）的参数会非常长，超过浏览器 / 服务器的 URL 长度上限（通常 2KB~8KB）；
可读性差：复杂逻辑用 URL 参数拼接（如 q=age:>30 AND city:北京）容易出错，而 POST 可以通过 JSON 请求体 组织查询条件（如 {"query": {"range": {"age": {"gt": 30}}}}），结构清晰、易维护。
语义的 “灵活适配”：虽然 _search 是 “查询（不修改资源）”，但为了支持复杂请求体，ES 选择用 POST—— 这里的 POST 不表示 “创建资源”，而是 “提交查询条件并获取结果”，属于 RESTful 设计中 “为实用性妥协” 的合理场景。*/
