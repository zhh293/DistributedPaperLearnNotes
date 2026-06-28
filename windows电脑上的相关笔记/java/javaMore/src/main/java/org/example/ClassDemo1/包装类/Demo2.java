package org.example.ClassDemo1.包装类;

public class Demo2 {
    public static void main(String[] args)
    {

    }
}
/*
一、使用包装类的 parseXXX() 静态方法（转换为基本数据类型）
这是最常见的转换方式，适用于将字符串转换为对应的基本数据类型。

java
// 转换为 int（基本数据类型）
String strInt = "123";
int num = Integer.parseInt(strInt); // 结果：123

// 转换为 double（基本数据类型）
String strDouble = "3.14";
double d = Double.parseDouble(strDouble); // 结果：3.14

// 转换为 boolean（基本数据类型）
String strBool = "true";
boolean bool = Boolean.parseBoolean(strBool); // 结果：true*/
/*
三、使用包装类的 valueOf() 静态方法（转换为包装类对象）
这种方法会先调用 parseXXX() 方法将字符串转换为基本数据类型，然后再进行自动装箱，返回包装类对象。

java
// 转换为 Integer（包装类对象）
String strInt = "123";
Integer obj = Integer.valueOf(strInt); // 结果：Integer 对象，值为 123

// 转换为 Double（包装类对象）
String strDouble = "3.14";
Double dObj = Double.valueOf(strDouble); // 结果：Double 对象，值为 3.14

// 转换为 Boolean（包装类对象）
String strBool = "true";
Boolean boolObj = Boolean.valueOf(strBool); // 结果：Boolean 对象，值为 true



四、字符转 Character（特殊情况）
对于 Character 包装类，由于它表示单个字符，所以需要先判断字符串长度是否为 1，然后再获取指定位置的字符。

java
String strChar = "A";
if (strChar.length() == 1) {
char c = strChar.charAt(0); // 获取第一个字符 'A'
Character charObj = c; // 自动装箱为 Character 对象
// 或者直接使用 Character.valueOf()
Character charObj2 = Character.valueOf(c);
}*/


/*
五、处理异常情况
在进行字符串到包装类的转换时，如果字符串格式不符合要求，会抛出 NumberFormatException（对于数值类型）或其他异常，因此需要进行异常处理。

java
String str = "abc";
try {
Integer num = Integer.valueOf(str); // 会抛出 NumberFormatException
} catch (NumberFormatException e) {
        System.err.println("字符串格式错误：" + e.getMessage());
        }

// 更安全的写法：使用正则表达式先验证格式
String str = "123";
if (str.matches("\\d+")) { // 验证是否为纯数字
Integer num = Integer.valueOf(str);
} else {
        System.err.println("不是有效的数字格式");
}*/
