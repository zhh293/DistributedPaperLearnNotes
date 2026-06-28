package com.zhanghonghao.search;

public class searchmethod {
    public static void main(String[] args) {
        //查找算法
        //基本查找,从零索引开始挨个往后查找
        int[]arr={2,4,13,245,678,367,987};
        boolean s=basicSearch(arr,678);
        System.out.println(s);
        //二分查找，数组中数据必须是有序的，每次排除一半查找范围
        //这个也是有手就行
        int[]arr2={1,2,3,4,5,6,7,8,9};
        int index=biosearch(arr2,6);
        System.out.println(index);
        //插值排序（二分查找的改进），提高效率，让mid值直接就靠近或者为所找目标的索引，这个方法对于线性变化的数组有奇效，分布需要比较均匀，对于不均匀的就难办了，服了
        //第二种优化，利用黄金分割点，斐波那契查找

    }
    public static boolean basicSearch(int[]arr,int x){
        for(int i=0;i<arr.length;i++){
            if(arr[i]==x){
                return true;
            }
        }
        return false;
    }
    public static int[] search(int[]arr,int x){
        int []arr1=new int[arr.length];
        for(int i=0;i<arr.length;i++){
            arr1[i]=-1;
        }
        int count=0;
        for(int i=0;i<arr.length;i++){
            if(arr[i]==x){
                arr1[count++]=i;
            }
        }
        return arr1;
    }
    public static  int biosearch(int[]arr,int x){
        int min=0,max=arr.length-1;
        while(min<=max){
            int mid=(max+min)/2;
            if(arr[mid]==x){
                return mid;
            }
            if(arr[mid]<x){
                min=mid+1;
            }
            if(arr[mid]>x){
                max=mid-1;
            }
        }
        return -1;
    }
}
