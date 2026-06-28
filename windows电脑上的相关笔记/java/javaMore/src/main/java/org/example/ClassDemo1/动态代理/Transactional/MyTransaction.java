package org.example.ClassDemo1.动态代理.Transactional;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface MyTransaction {
}
