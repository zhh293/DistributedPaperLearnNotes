package com.zhanghonghao.normalclass.practice;

public class practice2 {
    public static void main(String[] args) {
        int month=3;
        int num=1;
        int[]arr=new int[12];
        arr[0]=0;
        arr[1]=0;
        arr[2]=1;
        for(month=4;month<=12;month++){
            for(int i=0;i<=month-3;i++){
                arr[month-1]+=arr[i];
            }
            arr[month-1]+=num;
        }
        for(int i=0;i<12;i++){
            System.out.print(arr[i]+" ");
        }
        for(int i=0;i<12;i++){
            num+=arr[i];
        }
        System.out.println(num);
    }
}
