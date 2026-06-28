package com.zhanghonghao.normalclass.正则表达式;

public class 正则表达式 {
    public static void main(String[] args) {
        //正则表达式可以校验字符串是否满足一定的规则
        //核心思想：先把异常数据处理了，下面就是满足要求的数据了
         String qq="928198963";
         boolean w=checkqq(qq);
         System.out.println(w);
         //或者这样写
        System.out.println(qq.matches("[1-9]\\d{5,19}"));
        //是不是超级高级，现在来细说一下
        //[abc],只能是a,b,或者c
        //[^abc]  除了a,b,c之外的任何字符
        //[a-zA-Z] a到z,A到Z,包括范围
        //[a-d[m-p]] a到d，或者m到p
        //如果写成一个&,那么此时&表示的就不是交集了，而是一个简单的&符号
        //[a-z&&[def]] a-z和def的交集。为d,e,f
        //[a-z&&[^bc]] a-z和非bc的交集。等同于[ad-z]
        //[a-z&&[^m-p]] a到z和除了m到p的交集
        System.out.println("".matches("[^abc]"));
        System.out.println("a".matches("[^abc]")); // false
        System.out.println("z".matches("[^abc]")); // true
        System.out.println("zz".matches("[^abc]")); // false
        System.out.println("zz".matches("[^abc][^abc]")); // true

        // a到z A到Z(包括头尾的范围)
        System.out.println("".matches("[a-zA-Z]"));
        System.out.println("a".matches("[a-zA-Z]")); // true
        System.out.println("aa".matches("[a-zA-Z]")); // false
        System.out.println("zz".matches("[a-zA-Z]")); // false
        System.out.println("0".matches("[a-zA-Z]")); // false

        // [a-d[m-p]] a到d，或m到p
        System.out.println("".matches("[a-d[m-p]]"));
        System.out.println("a".matches("[a-d[m-p]]")); // true
        System.out.println("d".matches("[a-d[m-p]]")); // true
        System.out.println("m".matches("[a-d[m-p]]")); // true
        System.out.println("p".matches("[a-d[m-p]]")); // true
        System.out.println("e".matches("[a-d[m-p]]")); // false
        System.out.println("0".matches("[a-d[m-p]]")); // false
// [a-z&&[def]]  a-z和def的交集。为：d，e，f
//细节：如果要求两个范围的交集，那么需要写符号 &&
//如果写成了一个&，那么此时&表示就不是交集了，而是一个简简单单的&符号
        System.out.println("-----------5-----------");
        System.out.println("a".matches("[a-z&[def]]")); //true
        System.out.println("&".matches("[a-z&[def]]")); //true
        System.out.println("8".matches("[a-z&678[def]]"));
        System.out.println("&".matches("[a-z&&[def]]")); //false
        System.out.println("d".matches("[a-z&&[def]]")); //true
        System.out.println("0".matches("[a-z&&[def]]")); //false

// [a-z&&[^bc]]  a-z和非bc的交集。（等同于[ad-z]）
        System.out.println("-----------6-----------");
        System.out.println("a".matches("[a-z&&[^bc]]")); //true
        System.out.println("b".matches("[a-z&&[^bc]]")); //false
        System.out.println("0".matches("[a-z&&[^bc]]")); //false

// [a-z&&[^m-p]] a到z和除了m到p的交集。（等同于[a-lq-z]）
        System.out.println("-----------7-----------");
        System.out.println("a".matches("[a-z&&[^m-p]]")); //true
        System.out.println("m".matches("[a-z&&[^m-p]]")); //false
        System.out.println("0".matches("[a-z&&[^m-p]]")); //false
        //预定义字符
        //.代表任何字符，\d代表一个数字，\D代表非数字，\s代表一个空白字符，\S代表非空白字符，\w代表英文，数字，下划线
        //\W代表一个非单词字符，即[^\w].
        //System.out.println("\"");
        //.表示任意一个字符
        System.out.println("你".matches("..")); //false
        System.out.println("你".matches(".")); //true
        System.out.println("你a".matches("..")); //true

// \\d只能是任意的一位数字
// 简单来记：两个\\表示一个\
        System.out.println("a".matches("\\d")); // false
        System.out.println("3".matches("\\d")); // true
        System.out.println("333".matches("\\d")); // false

// \\w只能是一位单词字符 [a-zA-Z_0-9]
        System.out.println("z".matches("\\w")); // true
        System.out.println("2".matches("\\w")); // true
        System.out.println("21".matches("\\w")); // false
        System.out.println("你".matches("\\w")); //false

// 非单词字符
        System.out.println("你".matches("\\W")); // true
        System.out.println("---------------------------");
// 以上正则匹配只能校验单个字符。
        //数量词 x?  一次或者零次  x*零次或者多次  x+ 一次或者多次，x{n},正好出现n次
        //x{n,}  至少出现n次，x{n,m} 至少出现n次但不超过m次
        // 必须是数字 字母 下划线 至少 6位
        System.out.println("2442fsfsf".matches("\\w{6,}")); //true
        System.out.println("244f".matches("\\w{6,}")); //false

// 必须是数字和字符 必须是4位
        System.out.println("23dF".matches("[a-zA-Z0-9]{4}")); //true
        System.out.println("23_F".matches("[a-zA-Z0-9]{4}")); //false
        System.out.println("23dF".matches("[\\w&&[^_]]{4}")); //true
        System.out.println("23_F".matches("[\\w&&[^_]]{4}")); //false


    }
    public static boolean checkqq(String qq) {
        int len=qq.length();
        if(len<6||len>20){
            return false;
        }
        char ch=qq.charAt(0);
        if(ch=='0'){
            return false;
        }
        for(int i=0;i<len;i++){
            char ch1=qq.charAt(i);
            if(ch1<'0'||ch1>'9'){
                return false;
            }
        }
        return true;
    }
}
