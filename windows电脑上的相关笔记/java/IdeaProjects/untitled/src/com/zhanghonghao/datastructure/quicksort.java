package com.zhanghonghao.datastructure;

import java.util.Arrays;

public class quicksort {
    public static void main(String[] args) {
        //快速排序第一轮把0索引的数字作为基准数，确定基准数在数组中的正确位置
        //比基准数小的全部放在左边，大的全部放在右边
        //完成快速排序，不错，起码现在有思路了，而且能完整地顺下来，牛逼，今天先这样吧
        int []arr={2,5,3,7,9,23,12,35,16};
        quicksort(arr,0,arr.length-1);
        System.out.println(Arrays.toString(arr));

    }
    public static void quicksort(int []arr,int left,int right){
        if(left<right){
            int value=quicksort2(arr,left,right);
            quicksort(arr,value+1,right);
            quicksort(arr,left,value-1);
        }
        else{
            return;
        }
    }
    public static int quicksort2(int []arr,int left,int right){
        int pivot=arr[left];
        int i=left;
        int j=right;
        while(i<j){
            while(arr[j]>=pivot&&i<j){
                j--;
            }
            while(arr[i]<=pivot&&i<j){
                i++;
            }
            if(i<j){
                int temp=arr[j];
                arr[j]=arr[i];
                arr[i]=temp;
            }
        }
        int temp=arr[left];
        arr[left]=arr[j];
        arr[j]=temp;
        return i;
    }
}
