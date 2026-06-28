package org.example;

import java.util.PriorityQueue;
import java.util.Scanner;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {

        public int minStoneSum(int[] piles, int k) {
            PriorityQueue<Integer> pq = new PriorityQueue<>((a, b) -> b - a);
            for (int pile : piles) {
                pq.offer(pile);
            }
            for(int i = 0; i < k; i++){
                int top=pq.poll();
                int pre= (int) Math.floor(top / 2.0);
                top-=pre;
                pq.offer(top);
            }
            int sum=0;
            while (!pq.isEmpty()){
                sum+=pq.poll();
            }
            return sum;
        }
        public static void main(String[] args) {
            int a,b=0;
            Scanner sc=new Scanner(System.in);
            a=sc.nextInt();
            b=sc.nextInt();
            System.out.println(a+b);
        }
}
/*

按这么说的话，是不是ES字典树的深度会非常深啊，一般人都接触不到的那种，因为有好多种词元需要存储啊
其实不会。ES 中用于存储词元的 FST（替代传统字典树的结构），其 “深度”（状态转移的最大步数）主要由最长词元的长度决定，而不是词元的总数量，因此深度并不会因为词元多而变得 “深不可测”，反而通常是很有限的。
核心原因：FST 的深度由 “最长词元的长度” 决定，与词元数量无关
传统字典树或 FST 的 “深度”（从根到叶子的最大路径长度），本质上等于语料中最长词元的字符数，和词元的总数量（几万、几十万甚至上百万）没有直接关系。

举个例子：

假设语料中有 100 万个英文词元，但最长的词是 “pneumonoultramicroscopicsilicovolcanoconiosis”（约 45 个字母），那么 FST 的最大深度就是 45（每个字母对应一次状态转移）。
即使再增加 100 万个词元，只要最长的词还是 45 个字母，FST 的深度依然是 45（新增的词元只会通过共享状态增加节点数量，不会增加深度）。
实际场景中，词元的长度通常有限，导致 FST 深度很可控
无论是中文还是英文，实际索引中的词元长度都不会特别极端：

中文：分词后的词元大多是 2-4 个字（如 “自然语言”“人工智能”），极少数长词（如专业术语、机构名）可能到 6-8 个字，超过 10 个字的情况非常罕见。
英文：普通英文单词平均长度在 5-6 个字母，即使是专业领域的长单词（如医学、化学术语），超过 20 个字母的也很少见，且 ES 在索引时可能会对极端长词进行截断（可配置）。
为什么词元数量多不会增加 FST 深度？
FST 的核心是 “状态共享”：多个词元如果有相同的前缀或后缀，会共用同一组状态，而不是每个词元都单独开辟一条完整路径。

例如：

词元 “apple”“app”“application” 的前缀 “app” 会被共享，它们的路径在 “app” 之后才分叉，因此深度仅由最长的 “application”（11 个字母）决定，和这三个词元还是三万个类似词元无关。

这种共享机制使得：

词元数量增加时，FST 的节点数量会增加（但通过压缩后增长缓慢），但深度始终由最长词元的长度 “封顶”。
总结
ES 中 FST 的深度并不 “深不可测”，反而很可控：

深度由最长词元的字符数决定，与词元总数量无关；
实际场景中，最长词元的长度通常有限（中文 10 字内，英文 20 字母内），因此 FST 的深度一般在 10-20 之间（极端情况可能稍长，但远非 “接触不到”）；
FST 通过状态共享，避免了词元数量对深度的影响，只优化节点数量，保证了高效的匹配性能。*/
