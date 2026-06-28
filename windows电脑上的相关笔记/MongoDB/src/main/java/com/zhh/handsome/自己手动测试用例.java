package com.zhh.handsome;

public class 自己手动测试用例 {

   /* test> show dbs
    admin   40.00 KiB
    config  12.00 KiB
    local   72.00 KiB
    test> user articledb
    Uncaught:
    SyntaxError: Missing semicolon. (1:4)

            > 1 | user articledb
    |     ^
            2 |

    test> use articledb
    switched to db articledb
    articledb> show dbs
    admin   40.00 KiB
    config  12.00 KiB
    local   72.00 KiB
    articledb> db
            articledb
    articledb> db.createCollection("users")
    { ok: 1 }
    articledb> db.users.find()

    articledb> db.users.insertOne({})
    {
        acknowledged: true,
                insertedId: ObjectId('68cd2c279d84e08d64ce5f47')
    }
    articledb> db.users.fina()
    TypeError: db.users.fina is not a function
    articledb> db.users.find()
            [ { _id: ObjectId('68cd2c279d84e08d64ce5f47') } ]
    articledb> db.users.find({_id:68cd2c279d84e08d64ce5f47})
    Uncaught:
    SyntaxError: Identifier directly after number. (1:21)

            > 1 | db.users.find({_id:68cd2c279d84e08d64ce5f47})
            |                      ^
            2 |

    articledb> db.users.find({_id:'68cd2c279d84e08d64ce5f47'})

    articledb> db.users.insertMany([{"_id":"1","content":"肚子里面没有墨水"},{"_id":"2","content":"我是你爸爸"},{"_id:3","content":"那咋了"}])
    Uncaught:
    SyntaxError: Unexpected token (1:92)

> 1 | db.users.insertMany([{"_id":"1","content":"肚子里面没有墨水"},{"_id":"2","content":"我是你爸爸"},{"_id:3","content":"那咋了"}])
            |                                                                                             ^
            2 |

    articledb>  db.users.insertMany([{"_id":"1","content":"肚子里面没有墨水"},{"_id":"2","content":"我是你爸爸"},{"_id":"3","content":"那咋了"}])
    { acknowledged: true, insertedIds: { '0': '1', '1': '2', '2': '3' } }
    articledb> db.users.find()
            [
    { _id: ObjectId('68cd2c279d84e08d64ce5f47') },
    { _id: '1', content: '肚子里面没有墨水' },
    { _id: '2', content: '我是你爸爸' },
    { _id: '3', content: '那咋了' }
]
    articledb> db.users.find({"_id":"2"})
            [ { _id: '2', content: '我是你爸爸' } ]
    articledb> try {
...   // 插入3个文档，第三个文档的_id与第一个重复
...   db.users.insertMany([
...     { "_id": "1", "name": "张三" },
...     { "_id": "2", "name": "李四" },
...     { "_id": "1", "name": "王五" } // 重复_id，会抛出异常
...   ]);
...   print("插入成功");
... } catch (error) {
...   print("插入失败：", error.message); // 输出错误信息
...   print("错误类型：", error.name); // 输出错误类型（如 "MongoBulkWriteError"）
...   print("错误代码：", error.code); // 输出错误码（重复键错误码为 11000）
... }
    插入失败： E11000 duplicate key error collection: articledb.users index: _id_ dup key: { _id: "1" }
    错误类型： MongoBulkWriteError
    错误代码： 11000

*/

}
