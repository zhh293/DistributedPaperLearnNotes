package com.zhanghonghao.search;

public class searchmethod1 {
    public static void main(String[] args) {
        //分块，哈希查找
        /*分块的原则 1：前一块中的最大数据，小于后一块中所有的数据（块内无序，块间有序）
分块的原则 2：块数数量一般等于数字的个数开根号。比如：16 个数字一般分为 4 块左右。
核心思路：先确定要查找的元素在哪一块，然后在块内挨个查找。*/
        int []arr={16,5,9,12,21,18,32,23,37,26,45,34,50,48,61,52,73,66};

    }
}
