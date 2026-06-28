package com.zhanghonghao.normalclass.实时语音通话;

import java.util.Arrays;
import java.util.PriorityQueue;

public class testDataStructure {
    public static void main(String[] args) {
//        堆的复习要开始了，重新学习堆的知识，忘完了

        int []arr={4, 10, 3, 5, 1};
        buildHeap(arr);
        System.out.println(Arrays.toString( arr));
        //下面实现优先队列
        PriorityQueue<Integer> queue = new PriorityQueue<>();









        /*Java 中的 PriorityQueue 类
        PriorityQueue是 Java 集合框架中的一个实现类，它基于最小堆实现优先队列。以下是它的核心用法：

        java-priority-queue
        Java PriorityQueue基本用法
        V1
        生成 PriorityQueueExample.java
        PriorityQueue 核心方法
        添加元素：
        offer(E e): 添加元素到队列，成功返回 true
        add(E e): 同上，但可能抛出异常
        移除元素：
        poll(): 移除并返回队列头部元素（优先级最高的元素）
        remove(Object o): 移除指定元素
        查看元素：
        peek(): 返回队列头部元素但不移除
        element(): 同上，但队列为空时抛出异常
        其他常用方法：
        size(): 返回队列中元素个数
        isEmpty(): 判断队列是否为空
        contains(Object o): 判断队列是否包含指定元素
                注意事项
        不保证排序顺序：
        PriorityQueue只保证peek()和poll()操作返回的是优先级最高的元素
        遍历队列时（如使用迭代器），元素顺序不保证是有序的
        自定义排序：
        对于自定义对象，需要实现Comparable接口或提供Comparator
        可以通过Comparator.reverseOrder()创建最大堆
        性能特点：
        插入和删除操作的时间复杂度为 O (log n)
        查找操作的时间复杂度为 O (n)
                适用于需要频繁获取最值的场景
        线程安全：
        PriorityQueue是非线程安全的
        如需线程安全，可使用PriorityBlockingQueue

        优先队列在许多算法问题中非常有用，如 Dijkstra 最短路径算法、哈夫曼编码、任务调度等场景。*/
    }
    //看我这串代码的健壮性，考虑了边界条件，非常的健壮，哈哈哈哈哈
    public static void buildHeap(int[] arr){
        for(int i=(arr.length-1)/2;i>=0;i--){
            buildHeap(arr,i);
        }
    }
    private static void buildHeap(int[] arr,int i){
        if(2*i+1>=arr.length){
            return;
        }
        if(arr[i]<arr[2*i+1]&&2*i+2>=arr.length){
            if(arr[2*i+1]>arr[i]){
                int temp=arr[i];
                arr[i]=arr[2*i+1];
                arr[2*i+1]=temp;
            }
            buildHeap(arr,2*i+1);
        }
        //比较大小并且换位置
        if(arr[i]<arr[2*i+1]||arr[i]<arr[2*i+2]){
            int temp,index=0;
            if(arr[2*i+1]>arr[2*i+2]){
                temp=arr[2*i+1];
                index=2*i+1;
            }else{
                temp=arr[2*i+2];
                index=2*i+2;
            }
            arr[index]=arr[i];
            arr[i]=temp;
            buildHeap(arr,index);
        }
    }
}
