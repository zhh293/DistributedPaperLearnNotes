package com.zhanghonghao.normalclass.practice;

public class practice3 {
    public static void main(String[] args) {
        int []arr=new int[10];
        arr[0]=1;
        int num=1;
        for(int i=1;i<arr.length;i++){
            arr[i]=(arr[i-1]+1)*2;
            num+=arr[i];
        }
        System.out.println(arr[arr.length-1]);
    }
}
