package com.zhanghonghao.doublearraylist;

import java.util.Arrays;

public class lambdapractice {
    public static void main(String[] args) {
        String[] names ={"1234567","123","5678","890870"};
        Arrays.sort(names,(o1,o2)->{
            return o1.length()-o2.length();
        });
        for(int i=0;i<names.length;i++){
            System.out.println(names[i]);
        }
    }
}
