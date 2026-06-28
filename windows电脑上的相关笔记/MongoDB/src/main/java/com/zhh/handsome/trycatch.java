package com.zhh.handsome;

public class trycatch {

    /*一、MongoDB Shell 中使用 try/catch 的语法
            javascript
    运行
try {
        // 可能抛出异常的操作（如插入、更新、删除等）
        操作代码;
    } catch (error) {
        // 异常处理逻辑（如打印错误信息、回滚操作等）
        print("捕获到异常：", error);
        print("错误信息：", error.message);
        print("错误代码：", error.code); // MongoDB 错误码（如重复键错误码为 11000）
    }
    二、实际示例（常见错误场景）
    场景 1：捕获插入重复 _id 的异常
    当插入文档的 _id 已存在时，会触发 DuplicateKey 错误，可用 try/catch 捕获：
    javascript
            运行
try {
        // 插入3个文档，第三个文档的_id与第一个重复
        db.users.insertMany([
                { "_id": "1", "name": "张三" },
        { "_id": "2", "name": "李四" },
        { "_id": "1", "name": "王五" } // 重复_id，会抛出异常
  ]);
        print("插入成功");
    } catch (error) {
        print("插入失败：", error.message); // 输出错误信息
        print("错误类型：", error.name); // 输出错误类型（如 "MongoBulkWriteError"）
        print("错误代码：", error.code); // 输出错误码（重复键错误码为 11000）
    }
    执行结果：
    plaintext
    插入失败： E11000 duplicate key error collection: test.users index: _id_ dup key: { _id: "1" }
    错误类型： MongoBulkWriteError
    错误代码： 11000
    场景 2：捕获更新时的异常（如字段类型错误）
    javascript
            运行
try {
        // 尝试将 age 字段更新为字符串（假设已有数值类型的 age 索引，可能触发类型错误）
        db.users.updateOne(
                { "_id": "1" },
        { $set: { "age": "二十岁" } } // 若 age 有数值索引，会报错
  );
        print("更新成功");
    } catch (error) {
        print("更新失败：", error.message);
    }
    三、关键说明
    适用环境：try/catch 主要在 MongoDB Shell 中使用（因为 Shell 基于 JavaScript 引擎）。在其他编程语言（如 Python、Java）的 MongoDB 驱动中，需使用对应语言的异常处理语法（如 Python 的 try/except，Java 的 try/catch）。*/


}
