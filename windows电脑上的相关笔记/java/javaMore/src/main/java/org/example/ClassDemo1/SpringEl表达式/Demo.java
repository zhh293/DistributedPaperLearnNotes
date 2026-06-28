package org.example.ClassDemo1.SpringEl表达式;

import org.springframework.beans.factory.annotation.Value;

public class Demo {
    @Value("#{20 + 30}")
    private int sum;
}
