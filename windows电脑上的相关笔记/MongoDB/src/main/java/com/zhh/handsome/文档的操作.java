package com.zhh.handsome;

public class 文档的操作 {




   /* 一、插入文档（Create）
    向集合中插入一个或多个文档，使用 insertOne() 或 insertMany() 方法。
            1. 插入单个文档（insertOne）
    javascript
            运行
// 语法：db.集合名.insertOne(文档对象)
db.users.insertOne({
        name: "张三",
                age: 25,
                email: "zhangsan@example.com",
                hobbies: ["篮球", "游戏"],
        isStudent: false
    })
    返回结果：包含插入的文档 ID（_id）和操作状态（acknowledged: true 表示成功）。
    若未指定 _id，MongoDB 会自动生成一个唯一的 ObjectId 作为主键。
            2. 插入多个文档（insertMany）
    javascript
            运行
// 语法：db.集合名.insertMany([文档1, 文档2, ...])
db.users.insertMany([
    { name: "李四", age: 30, email: "lisi@example.com" },
    { name: "王五", age: 28, email: "wangwu@example.com", hobbies: ["读书"] }
])
    批量插入效率更高，适合初始化数据。
    若其中一个文档出错，默认会终止整个操作；可添加 { ordered: false } 选项忽略错误继续插入：
    javascript
            运行
db.users.insertMany([...], { ordered: false })
    二、查询文档（Read）
    从集合中查询文档，核心方法是 find()，配合条件参数实现精准查询。
            1. 查询所有文档
            javascript
    运行
// 语法：db.集合名.find()
db.users.find()  // 返回集合中所有文档（默认显示20条，可按回车键加载更多）
    格式化输出（更易读）：
    javascript
            运行
db.users.find().pretty()  // 以缩进格式显示文档
2. 按条件查询（find({条件})）
    javascript
            运行
// 1. 等于条件（=）：查询age=25的文档
db.users.find({ age: 25 })

// 2. 范围条件：查询age>25且age<=30的文档
            db.users.find({ age: { $gt: 25, $lte: 30 } })  // $gt:>，$lte:<=

// 3. 包含条件：查询hobbies包含"篮球"的文档
            db.users.find({ hobbies: "篮球" })  // 数组包含指定元素

// 4. 逻辑条件：查询age>25 或 isStudent=true的文档（$or）
            db.users.find({ $or: [{ age: { $gt: 25 } }, { isStudent: true }] })

// 5. 字段存在：查询包含email字段的文档
            db.users.find({ email: { $exists: true } })
            3. 限制返回字段（投影，Projection）
    默认返回文档所有字段，可指定只返回需要的字段（_id 默认为返回，需显式排除）：
    javascript
            运行
// 语法：find(条件, { 字段1: 1, 字段2: 1, _id: 0 })  // 1:返回，0:不返回
db.users.find(
    { age: { $gt: 25 } },  // 条件：age>25
    { name: 1, age: 1, _id: 0 }  // 只返回name和age，不返回_id
)
        4. 其他常用查询方法
    findOne()：返回第一个匹配的文档（无需遍历）：
    javascript
            运行
db.users.findOne({ name: "张三" })  // 返回name=张三的第一个文档
    sort()：排序（1: 升序，-1: 降序）：
    javascript
            运行
db.users.find().sort({ age: 1 })  // 按age升序排列
    limit(n)：限制返回前 n 条：
    javascript
            运行
db.users.find().limit(3)  // 只返回前3条
    skip(n)：跳过前 n 条（用于分页）：
    javascript
            运行
db.users.find().skip(2).limit(2)  // 跳过前2条，返回接下来的2条（第3-4条）
    三、更新文档（Update）
    修改已有文档，使用 updateOne()（更新第一个匹配）、updateMany()（更新所有匹配）或 replaceOne()（替换整个文档）。注意：更新需使用更新操作符（如 $set、$inc），否则会替换整个文档。
            1. 更新单个文档（updateOne）
    javascript
            运行
// 语法：db.集合名.updateOne(条件, { 更新操作符 })
// 例：将name=张三的文档，age改为26，添加address字段
db.users.updateOne(
    { name: "张三" },  // 条件：匹配name=张三的第一个文档
    {
        $set: { age: 26, address: "北京市" },  // $set：修改字段（不存在则新增）
        $inc: { loginCount: 1 }  // $inc：数值字段自增1（若字段不存在则初始化为1）
    }
)
        2. 更新多个文档（updateMany）
    javascript
            运行
// 例：将所有age<28的文档，添加"isYoung": true字段
db.users.updateMany(
    { age: { $lt: 28 } },  // 条件：age<28
    { $set: { isYoung: true } }
)
        3. 替换文档（replaceOne）
    完全替换匹配的文档（保留 _id，其他字段全替换）：
    javascript
            运行
db.users.replaceOne(
    { name: "王五" },  // 条件
    { name: "王五", age: 29, job: "工程师" }  // 新文档（无需操作符）
)
    常用更新操作符
    操作符	作用	示例
    $set	修改字段值（新增不存在的字段）	{ $set: { age: 26 } }
    $inc	数值字段自增 / 减	{ $inc: { score: 10 } }（+10）
    $push	向数组添加元素	{ $push: { hobbies: "游泳" } }
    $pull	从数组删除指定元素	{ $pull: { hobbies: "游戏" } }
    $unset	删除字段	{ $unset: { email: 1 } }（删除 email）
    四、删除文档（Delete）
    删除文档使用 deleteOne()（删除第一个匹配）或 deleteMany()（删除所有匹配）。
            1. 删除单个文档（deleteOne）
    javascript
            运行
// 语法：db.集合名.deleteOne(条件)
db.users.deleteOne({ name: "张三" })  // 删除name=张三的第一个文档
            2. 删除多个文档（deleteMany）
    javascript
            运行
// 例：删除所有isStudent=true的文档
db.users.deleteMany({ isStudent: true })

// 危险！删除集合中所有文档（保留集合结构）
            db.users.deleteMany({})  // 条件为空，匹配所有文档
    五、其他常用文档操作
1. 计数（countDocuments）
    统计匹配条件的文档数量：
    javascript
            运行
db.users.countDocuments({ age: { $gt: 25 } })  // 统计age>25的文档数
            2. 去重（distinct）
    获取指定字段的不重复值：
    javascript
            运行
db.users.distinct("age")  // 返回所有不重复的age值（如 [25, 28, 30]）
    总结
    文档操作是 MongoDB 的核心，核心命令对应 CRUD 场景：
    插入：insertOne、insertMany
    查询：find、findOne（配合 sort、limit 等）
    更新：updateOne、updateMany（依赖更新操作符）
    删除：deleteOne、deleteMany*/
}
