package org.example.ClassDemo1.注解;

public @interface Demo3 {
    String value();
}
//特殊属性
// 目标:注解的特殊属性名称:value
//
//value属性，如果只有一个value属性的情况下，
//
//使用value属性的时候可以省略value名称不写!!
//
//但是如果有多个属性,且多个属性没有默认值，那么value是不能省略