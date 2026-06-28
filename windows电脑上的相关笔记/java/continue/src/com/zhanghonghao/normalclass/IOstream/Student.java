//package com.zhanghonghao.normalclass.IOstream;

//import java.io.Serial;
import java.io.Serializable;

//public class Student implements Serializable {
   // @Serial
    //private static final long serialVersionUID = -5265251822669223059L;
    //private static final long serialVersionUID = 1L;
    /*private String name;
    private int age;
    public Student(String name, int age) {
        this.name = name;
        this.age = age;
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public int getAge() {
        return age;
    }
    public void setAge(int age) {
        this.age = age;
    }
}*/
/*一、serialVersionUID 的核心作用
serialVersionUID 是一个用于标识类版本的唯一标识符，它在序列化和反序列化过程中扮演关键角色：
序列化时：Java 将 serialVersionUID 写入字节流，作为类的 “指纹”。
反序列化时：Java 会比对字节流中的 serialVersionUID 与当前类的 serialVersionUID：
若一致：认为类版本兼容，正常反序列化。
若不一致：抛出 InvalidClassException，终止反序列化。
二、为什么需要版本一致性？
1. 类结构变更的风险
当类被修改（如新增字段、删除字段、修改字段类型），其 “隐式版本号” 会改变。若没有显式指定 serialVersionUID，可能导致：
序列化时：使用旧类结构生成字节流（包含旧版本号）。
反序列化时：若新类结构的隐式版本号与旧版本号不同，即使逻辑兼容，Java 仍会判定不兼容。
示例：
java
// 初始类（未显式指定UID）
class User implements Serializable {
    private String name;
}

// 后续修改（新增age字段）
class User implements Serializable {
    private String name;
    private int age; // 新增字段
}


此时，新旧类的隐式 serialVersionUID 不同，反序列化会失败。
2. 显式指定 serialVersionUID 的优势
通过显式指定 serialVersionUID，可强制 Java 认为类版本兼容，即使类结构发生变化：
java
class User implements Serializable {
    private static final long serialVersionUID = 1L; // 固定版本号
    private String name;
}

// 后续修改（新增字段）
class User implements Serializable {
    private static final long serialVersionUID = 1L; // 保持版本号一致
    private String name;
    private int age; // 新增字段
}


此时，反序列化时会：
成功恢复已有字段（如 name）。
为新增字段赋默认值（如 age 为 0）。



1. 手动修改文件内容（非程序修改）
结果：极可能导致反序列化失败。
原因：
序列化文件是二进制格式，包含类元数据和对象状态的特定编码。
手动修改（如用文本编辑器添加字符）会破坏字节流结构，导致校验失败。*/
