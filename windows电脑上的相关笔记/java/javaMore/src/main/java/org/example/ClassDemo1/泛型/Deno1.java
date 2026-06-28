package org.example.ClassDemo1.泛型;

import java.util.ArrayList;

public class Deno1 {
    public static void main(String[] args) {
        ArrayList<Integer> list = new ArrayList<>();
    }
}
/*什么是泛型?

泛型就是一个标签:<数据类型>

泛型可以在编译阶段约束只能操作某种数据类型，

注意:JDK 1.7开始之后，泛型后面的申明可以省略不写!!

泛型和集合都只能支持引用数据类型，不支持基本数据类型，*/

