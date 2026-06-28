package org.example.ClassDemo1.正则表达式;

public class Demo1 {
    public static void main(String[] args) {

    }
    public static boolean isPhone(String qq)
    {
        if (qq== null){
            return false;
        }else{
            if(qq.length()>=4){
                //判断是否全是数字，这里就不演示for循环了，直接正则表达式
                return qq.matches("[0-9]+");
            }
            return false;
        }
    }
    public static boolean isEmail(String email)
    {
        if (email== null){
            return false;
        }else{
            if(email.length()>=4){
                //判断是否全是数字，这里就不演示for循环了，直接正则表达式
                return email.matches("\\w+@\\w+\\.\\w+");
            }
            return false;
        }
    }
}
