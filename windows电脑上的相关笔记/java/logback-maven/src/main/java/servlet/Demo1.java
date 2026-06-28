package servlet;

import java.util.Arrays;
import java.util.PriorityQueue;

public class Demo1 {
    public static void main(String[] args) {
       int []nums={999999999,999999999,999999999};
       int k=1000000000;
       System.out.println(minOperations(nums,k));

    }
    public static int minOperations(int[] nums, int k) {
        int count=0;
        int length=nums.length;
        System.out.println("这个数组的长度为"+length);
        int i=0;
        long [] nums1=new long[nums.length];
        for(int j=0;j<nums.length;j++){
            nums1[j]=nums[j];
            System.out.println("这个数组的元素为"+nums1[j]);
        }
        Arrays.sort(nums1);
        while(length>1){
            if(nums1[i]>=k){
                return count;
            }
            long pivot1,pivot2=0L;
            pivot1=nums1[i];
            pivot2=nums1[i+1];
            System.out.println("这个pivot1和pivot2的值为"+pivot1+"和"+pivot2);
            i+=1;
            length-=1;
            count++;
            long max= Math.max(pivot1,pivot2);
            long min=Math.min(pivot1,pivot2);
            long result=min*2+max;
            System.out.println("这个每一次的结果是"+result+"他要插入到的数组的下表索引为"+i);
            nums1[i]=result;
            Arrays.sort(nums1,i,nums1.length);
            PriorityQueue<Long> queue=new PriorityQueue<>();
        }
        return count;
    }
}
