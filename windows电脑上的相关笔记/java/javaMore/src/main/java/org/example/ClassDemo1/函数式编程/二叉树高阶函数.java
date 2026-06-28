package org.example.ClassDemo1.函数式编程;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class 二叉树高阶函数 {
    public record TreeNode(int value,TreeNode left,TreeNode right) {
        public String toString() {
            return "%d".formatted(value);
        }
    }
    public static void traver(TreeNode node, Type type, Supplier<String> message) {
        if(node==null) {
            return;
        }
        if(type==Type.front){
            String s = message.get();
            System.out.println(s);
            traver(node.left,type,message);
            traver(node.right,type,message);
        }else if(type==Type.back){
            traver(node.left,type,message);
            traver(node.right,type,message);
            String s = message.get();
            System.out.println(s);
        }else {
            traver(node.left,type,message);
            String s = message.get();
            System.out.println(s);
            traver(node.right,type,message);
        }

    }
    public static void main(String[] args) {
        Consumer<String> consumer = new Consumer<>() {
            @Override
            public void accept(String s) {
                System.out.println(s);
            }
        };
        consumer.accept("哈哈哈");
        TreeNode node = new TreeNode(1,null,null);
        traver(node, Type.front, new Supplier<String>() {
            @Override
            public String get() {
                return "卧槽你打吧"+Type.front.name();
            }
        });
    }

}
enum Type{
    front,middle,back
}
