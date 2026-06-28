# Elasticsearch语句编写实战练习指南

## 为什么ES语句感觉难写？

1. **查询DSL语法复杂** - 需要理解JSON结构和嵌套关系
2. **概念众多** - query vs filter、text vs keyword、分词等概念容易混淆
3. **实践机会少** - 缺乏足够的练习和实际应用场景
4. **错误调试困难** - 语法错误不容易定位和修复

## 学习路径规划

### 阶段一：基础概念掌握（1周）

#### 1.1 理解ES数据模型
- 索引(Index) ≈ 数据库+表
- 文档(Document) ≈ 行
- 字段(Field) ≈ 列

#### 1.2 理解字段类型
```json
{
  "mappings": {
    "properties": {
      "title": {          // text类型：用于全文检索
        "type": "text",
        "analyzer": "ik_max_word"
      },
      "status": {         // keyword类型：用于精确匹配
        "type": "keyword"
      },
      "create_time": {   // date类型：用于日期范围查询
        "type": "date"
      },
      "view_count": {    // integer类型：用于数值查询
        "type": "integer"
      }
    }
  }
}
```

### 阶段二：基础查询练习（1周）

#### 2.1 Match Query（全文检索）
```json
// 搜索title中包含"elasticsearch"的文档
{
  "query": {
    "match": {
      "title": "elasticsearch"
    }
  }
}
```

练习题目：
- 搜索title中包含"java"的文档
- 搜索content中包含"教程"的文档
- 搜索description中包含"高性能"的文档

#### 2.2 Term Query（精确匹配）
```json
// 精确匹配status为"published"的文档
{
  "query": {
    "term": {
      "status.keyword": "published"
    }
  }
}
```

练习题目：
- 精确匹配category为"技术分享"的文档
- 精确匹配author_id为"12345"的文档
- 精确匹配is_deleted为false的文档

#### 2.3 Range Query（范围查询）
```json
// 查询view_count大于1000的文档
{
  "query": {
    "range": {
      "view_count": {
        "gte": 1000
      }
    }
  }
}
```

练习题目：
- 查询create_time在过去30天内的文档
- 查询price在100到500之间的商品
- 查询rating大于4.0的评价

### 阶段三：复合查询练习（1周）

#### 3.1 Bool Query（组合查询）
```json
// 必须满足条件且不能满足条件的组合查询
{
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "title": "elasticsearch"
          }
        }
      ],
      "must_not": [
        {
          "term": {
            "status.keyword": "draft"
          }
        }
      ],
      "filter": [
        {
          "range": {
            "create_time": {
              "gte": "2023-01-01"
            }
          }
        }
      ]
    }
  }
}
```

练习题目：
- 查询title包含"java"且status为"published"且create_time在过去一年内的文档
- 查询content包含"教程"且view_count大于500且not deleted的文档
- 查询category为"技术分享"且rating大于4.0且author_id为特定值的文档

### 阶段四：高级功能练习（1周）

#### 4.1 高亮查询
```json
{
  "query": {
    "match": {
      "title": "elasticsearch"
    }
  },
  "highlight": {
    "fields": {
      "title": {},
      "content": {}
    },
    "pre_tags": ["<mark>"],
    "post_tags": ["</mark>"]
  }
}
```

练习题目：
- 为搜索结果中的title字段添加高亮
- 为content和description字段添加高亮
- 自定义高亮标签样式

#### 4.2 分页查询
```json
{
  "from": 0,
  "size": 10,
  "query": {
    "match_all": {}
  }
}
```

练习题目：
- 实现第一页，每页10条记录的查询
- 实现第二页，每页20条记录的查询
- 实现第三页，每页5条记录的查询

#### 4.3 排序查询
```json
{
  "query": {
    "match": {
      "title": "elasticsearch"
    }
  },
  "sort": [
    {
      "create_time": {
        "order": "desc"
      }
    },
    {
      "view_count": {
        "order": "desc"
      }
    }
  ]
}
```

练习题目：
- 按创建时间降序排列
- 按浏览量降序排列
- 按评分降序、浏览量降序多重排序

### 阶段五：聚合查询练习（1周）

#### 5.1 Terms Aggregation（分组统计）
```json
{
  "aggs": {
    "categories": {
      "terms": {
        "field": "category.keyword"
      }
    }
  }
}
```

练习题目：
- 统计各个分类的文档数量
- 统计各个作者的文章数量
- 统计各个状态的文档数量

#### 5.2 Metrics Aggregation（数值统计）
```json
{
  "aggs": {
    "avg_views": {
      "avg": {
        "field": "view_count"
      }
    },
    "max_views": {
      "max": {
        "field": "view_count"
      }
    }
  }
}
```

练习题目：
- 计算平均浏览量
- 计算最高价格
- 计算最低评分

## 实践练习项目

### 项目1：博客搜索系统
目标：实现一个简单的博客搜索功能

练习任务：
1. 创建blog索引，包含title、content、author、tags、publish_time、view_count等字段
2. 实现按标题搜索的功能
3. 实现按内容搜索的功能
4. 实现按作者精确匹配的功能
5. 实现按标签筛选的功能
6. 实现按发布日期范围筛选的功能
7. 实现搜索结果高亮显示
8. 实现分页功能
9. 实现按发布时间排序
10. 实现按浏览量排序

### 项目2：电商商品搜索系统
目标：实现一个商品搜索功能

练习任务：
1. 创建product索引，包含name、description、category、brand、price、rating等字段
2. 实现商品名称全文搜索
3. 实现商品描述搜索
4. 实现按分类筛选
5. 实现按品牌筛选
6. 实现按价格范围筛选
7. 实现按评分范围筛选
8. 实现价格区间统计
9. 实现品牌统计
10. 实现多条件组合搜索

## 工具推荐

### 1. Kibana Dev Tools
- 最好的ES查询练习工具
- 可以直接编写和测试查询语句
- 有语法提示和错误检查

### 2. Postman
- 可以保存常用的查询模板
- 便于测试和调试

### 3. ES Head Plugin
- 图形化界面查看索引和数据
- 便于理解数据结构

## 练习建议

### 1. 循序渐进
- 从简单的match查询开始
- 逐步增加复杂度
- 每个概念都要亲手实践

### 2. 多做实验
- 修改查询参数观察结果变化
- 对比不同查询的性能
- 理解每个参数的作用

### 3. 查阅文档
- 官方文档是最好的学习资源
- 每个查询类型都有详细说明
- 多看官方示例

### 4. 调试技巧
- 使用explain API理解查询执行过程
- 使用profile API分析查询性能
- 使用validate API验证查询语法

## 常见错误及解决方法

### 1. 字段类型错误
- 错误：在text字段上使用term查询
- 解决：使用keyword子字段或match查询

### 2. 分词问题
- 错误：中文搜索效果不好
- 解决：使用合适的中文分词器（如IK）

### 3. 性能问题
- 错误：深分页查询很慢
- 解决：使用scroll查询或search_after

### 4. 语法错误
- 错误：JSON格式错误
- 解决：使用格式化工具检查JSON

## 学习资源

### 1. 官方文档
- Elasticsearch官方文档
- Query DSL参考手册

### 2. 实践环境
- 本地搭建ES环境
- 使用Docker快速部署

### 3. 学习项目
- Fork开源ES项目
- 阅读别人的查询实现

通过系统性的练习和实践，您一定能够熟练掌握ES语句的编写。记住，关键在于多练多试，不断积累经验！