package org.example.ClassDemo1.HomeWork;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
public class Circle extends TwoDShape{
    private double radius;
    @Override
    double getArea() {
        return Math.PI * radius * radius;
    }
}
