package com.zhh.handsome.JDK提供的线程池实现;

import java.util.ArrayList;
import java.util.List;

public class 基数排序 {
    public static void main(String[] args) {
        int[] arr = {170, 45, 75, 90, 802, 24, 2, 66};
        radixSort(arr);
        for (int num : arr) {
            System.out.print(num + " ");
        }
    }
    public static void radixSort(int[] arr) {
        int max = getMax(arr);
        for (int exp = 1; max / exp > 0; exp *= 10) {
            countingSortByDigit(arr, exp);
        }
    }
    private static int getMax(int[] arr) {
        int max = arr[0];
        for (int num : arr) {
            if (num > max) {
                max = num;
            }
        }
        return max;
    }
    private static void countingSortByDigit(int[] arr, int exp) {
        int[]output=new int[arr.length];
        List<List<Integer>>list=new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            list.add(new ArrayList<>());
        }
        for(int i=0;i<arr.length;i++){
            int pre=arr[i]/exp%10;
            List<Integer> list1 = list.get(pre);
            if(list1==null){
                list1=new ArrayList<>();
                list1.add(i);
                list.add(list1);
            }else{
                list1.add(i);
            }
        }
        for(int i=0,k=0;i<list.size();i++){
            List<Integer> list1 = list.get(i);
            if(list1!=null){
                for (Integer index : list1) {
                    output[k++]=arr[index];
                }
            }
        }
        System.arraycopy(output,0,arr,0,arr.length);
    }
}
