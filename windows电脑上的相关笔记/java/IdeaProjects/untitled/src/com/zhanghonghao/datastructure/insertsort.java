package com.zhanghonghao.datastructure;

import java.util.Arrays;

public class insertsort {
    public static void main(String[] args) {
        int[]arr={2,4,1,6,8,9};
        int index=bisearch(arr,9,0,arr.length-1);
        System.out.println(index);
        insertsort1(arr);
        System.out.println(Arrays.toString(arr));
        //明白插入排序和冒泡排序之间的思路差别即可，代码实现按照不同的思路设计即可，完美

    }
    public static int bisearch(int[]arr,int x,int left,int right){
        int midth=arr.length/2;
        int midth1=midth;
        while(left<=midth&&midth1<=right){
            if(arr[midth]==x){
                return midth;
            }
            else if(arr[midth1]==x){
                return midth1;

            }
            else{
                midth--;
                midth1++;
                midth=(left+midth)/2+1;
                midth1=(right+midth1)/2+1;
            }
        }

        return -1;
    }
    public static void insertsort(int[] arr) {
        // 从第二个元素开始
        for (int i = 1; i < arr.length; i++) {
            int currentValue = arr[i];
            int j = i - 1;
            // 当已排序序列中元素大于当前元素时，将元素向后移动一位
            while (j >= 0 && arr[j] > currentValue) {
                arr[j + 1] = arr[j];
                j--;
            }
            // 将当前元素插入到正确位置
            arr[j + 1] = currentValue;
        }
    }
    public static void insertsort1(int[] arr) {
        // 从第二个元素开始
        for (int i = 1; i < arr.length; i++) {
            int currentValue = arr[i];
            int j = i - 1;
            // 当已排序序列中元素大于当前元素时，将元素向后移动一位
            while (j >= 0 && arr[j] > currentValue) {
                int temp = arr[j+1];
                arr[j + 1] = arr[j];
                arr[j] = temp;
                j--;
            }
        }
    }
    public static void insertsort2(int[]arr){
        int j=1;
        for(int k=1;k<arr.length;k++){
            for(int i=j-1;i>=0;i--){
                if(arr[k]<arr[i]){
                    int temp=arr[k];
                    arr[k]=arr[i];
                    arr[i]=temp;
                }
            }
            j++;
        }
    }
    public static void insertsort3(int[] arr){
        for(int i=0;i<arr.length;i++){
            for(int j=i+1;j<arr.length;j++){
                if(arr[j]<arr[i]){
                    int temp=arr[j];
                    arr[j]=arr[i];
                    arr[i]=temp;
                }

            }
        }
    }
}
