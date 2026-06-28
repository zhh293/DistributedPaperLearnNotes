package servlet;

import java.util.PriorityQueue;

public class queue {
    public static void main(String[] args) {
        PriorityQueue<Integer> queue=new PriorityQueue<>();

    }
    public static int minOperations(int[] nums, int k) {
        PriorityQueue<Long> pq = new PriorityQueue<>();
        for (int num : nums) {
            pq.offer((long) num);
            if (pq.size() > k) {
                pq.poll();
            }
        }
        long sum = 0;
        while (pq.size() > 1) {
            long a = pq.poll();
            long b = pq.poll();
            sum += a + b;
            pq.offer(a + b);
        }
        return (int) sum;
    }
}
