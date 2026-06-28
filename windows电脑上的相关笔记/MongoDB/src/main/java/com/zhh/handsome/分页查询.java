package com.zhh.handsome;

public class 分页查询 {





   /* 在 MongoDB 中，分页查询用于将大量数据按页返回（如每页显示 10 条数据），核心通过 limit()、skip() 配合 sort() 实现，同时还有基于索引的高效分页方案。以下是详细语法及使用场景：
    一、基础分页：skip() + limit()（简单场景）
    最常用的分页方式，通过 skip(n) 跳过前 n 条文档，limit(m) 限制返回 m 条文档，需配合 sort() 保证分页顺序稳定。
    语法格式：
    javascript
            运行
// 分页公式：第 page 页（从1开始），每页 size 条
db.集合名.find(查询条件)
            .sort(排序字段)  // 必须排序，否则分页结果可能混乱
  .skip((page - 1) * size)  // 跳过前 (page-1)*size 条
            .limit(size)  // 每页显示 size 条
    示例：查询用户列表，每页 10 条，取第 2 页
            javascript
    运行
// 条件：查询所有年龄>18的用户
// 排序：按注册时间（createTime）降序（最新的在前）
// 分页：第2页，每页10条
db.users.find({ age: { $gt: 18 } })
            .sort({ createTime: -1 })  // -1表示降序，1表示升序
            .skip(10)  // 跳过前10条（第1页的10条）
  .limit(10)  // 返回第11-20条（第2页）
    优缺点：
    优点：实现简单，适合页数少、数据量小的场景（如后台管理系统的分页列表）。
    缺点：skip(n) 在 n 很大时（如 skip(100000)）效率极低，因为 MongoDB 需要扫描并跳过前 n 条文档，耗时随 n 增长而增加。
    二、高效分页：基于索引的范围查询（大数据场景）
    针对大数据量（百万级以上）或高页数场景，推荐使用范围查询 + 索引，避免 skip() 的性能问题。核心思路是：以上一页的最后一条文档的某个有序字段（如 _id、时间戳）作为条件，直接定位到下一页的起始位置。
    语法格式：
    javascript
            运行
// 分页公式：以上一页最后一条文档的「锚点字段值」作为条件
db.集合名.find({
        查询条件,
                锚点字段: { $gt: 上一页最后一条的锚点值 }  // 定位下一页起点
    })
            .sort(排序字段)  // 排序字段需与锚点字段一致（或包含锚点字段）
  .limit(size)  // 每页显示 size 条
    示例：用 _id 作为锚点，高效分页
    _id 是 MongoDB 自动生成的唯一有序字段（包含时间戳），适合作为锚点，且默认有索引，性能极佳。
    javascript
            运行
    // 第1页：查询前10条，按 _id 升序
    let page1 = db.users.find({ age: { $gt: 18 } })
        .sort({ _id: 1 })
        .limit(10)
  .toArray();

// 获取第1页最后一条文档的 _id（作为下一页的锚点）
let lastId = page1[page1.length - 1]._id;

// 第2页：以上一页的 lastId 为起点，查询下10条
let page2 = db.users.find({
        age: { $gt: 18 },
_id: { $gt: lastId }  // 直接定位到 lastId 之后的文档
        })
        .sort({ _id: 1 })  // 排序字段必须与锚点字段一致
        .limit(10)
  .toArray();
优缺点：
优点：利用索引直接定位起始位置，效率极高，适合大数据量、高页数场景（如社交媒体的滚动加载）。
缺点：无法直接跳转到任意页（如直接跳到第 100 页），只能按顺序翻页；依赖有序字段和索引。
三、关键注意事项
必须排序：分页查询必须配合 sort()，否则 MongoDB 返回文档的顺序是不确定的（受存储引擎影响），会导致分页结果混乱（如同一文档出现在多页）。
索引优化：
排序字段必须建立索引，否则 sort() 会耗时（尤其是大数据量时）。例如：db.users.createIndex({ createTime: -1 })。
范围查询的锚点字段（如 _id、createTime）必须有索引，才能发挥高效分页的优势。
避免 skip() 大数值：当页数超过 1000 页时，skip((page-1)*size) 的性能会急剧下降，此时必须改用「范围查询分页」。
处理数据变更：
用 skip() 分页时，若分页过程中数据被新增 / 删除，可能导致重复或遗漏（如第 1 页删除一条，第 2 页会多一条重复）。
范围查询分页更稳定，因为基于锚点值定位，不受中间数据变更影响。
四、Java 代码实现示例
1. 基础分页（skip() + limit()）
java
        运行
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;

// 分页参数：第2页，每页10条
int page = 2;
int size = 10;

// 查询年龄>18的用户，按createTime降序，分页返回
MongoCollection<Document> collection = mongoDatabase.getCollection("users");
collection.find(Filters.gt("age", 18))
        .sort(new Document("createTime", -1))  // 排序
        .skip((page - 1) * size)  // 跳过前10条
        .limit(size)  // 取10条
         .forEach(doc -> System.out.println(doc.toJson()));  // 遍历结果
        2. 高效分页（范围查询）
java
        运行
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.util.List;

// 第1页：获取前10条，按_id升序
MongoCollection<Document> collection = mongoDatabase.getCollection("users");
List<Document> page1 = collection.find(Filters.gt("age", 18))
        .sort(new Document("_id", 1))
        .limit(10)
        .into(new ArrayList<>());

// 获取第1页最后一条的_id作为锚点
ObjectId lastId = page1.get(page1.size() - 1).getObjectId("_id");

// 第2页：基于lastId查询下10条
List<Document> page2 = collection.find(Filters.and(
                Filters.gt("age", 18),
                Filters.gt("_id", lastId)  // 范围查询定位起点
        ))
        .sort(new Document("_id", 1))
        .limit(10)
        .into(new ArrayList<>());*/
}




/*

是的，第二种基于「范围查询 + 锚点字段」的分页方式，本质上就是滚动分页（也叫游标分页、无限滚动分页），非常适合移动端或 Web 端的 “滚动加载更多” 场景（比如朋友圈、商品列表向下滚动时加载下一页数据）。
为什么它是滚动分页？
滚动分页的核心特点是：不需要显式的页码（如第 1 页、第 2 页），而是通过 “上一页的最后一条数据” 作为锚点，不断加载下一批数据，完全符合用户 “滚动到底部加载更多” 的交互习惯。
对比传统分页（skip() + limit()）和滚动分页的区别：
特点	传统分页（skip() + limit()）	滚动分页（范围查询）
交互方式	基于页码跳转（如 “第 1 页”“第 5 页”）	基于滚动加载（如 “加载更多” 按钮）
定位方式	通过 skip(n) 跳过前 n 条	通过上一页最后一条的锚点值（如_id）定位
适用场景	后台管理系统（需自由跳页）	移动端 / 内容流（顺序浏览，不跳页）
大数据量性能	差（skip(n) 扫描前 n 条）	优（索引直接定位锚点后的数据）
数据变更影响	可能重复 / 遗漏（如中间数据被删除）	更稳定（锚点值唯一，不受中间数据影响）
滚动分页的典型应用场景
社交媒体动态流：如朋友圈、微博，用户向下滚动时加载更早的动态，用发布时间或_id作为锚点。
电商商品列表：用户浏览商品时滚动加载更多，用商品 ID 或上架时间作为锚点。
日志 / 记录查询：按时间顺序展示的系统日志，滚动加载更早的记录，用时间戳作为锚点。
滚动分页的实现关键
锚点字段必须有序且唯一：通常选择_id（自带时间戳，唯一且有序）、创建时间（createTime）等，确保每次查询能准确定位下一页起点。
锚点字段必须有索引：比如对createTime建立索引，避免全表扫描，保证查询效率。
前端需保存上一页的最后一个锚点值：每次请求下一页时，将这个值传给后端，作为查询条件（如_id: { $gt: lastId }）。
例如，前端滚动加载的交互流程：

*/
