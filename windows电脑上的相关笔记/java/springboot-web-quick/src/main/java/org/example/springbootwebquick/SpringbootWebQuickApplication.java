package org.example.springbootwebquick;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication

@ComponentScan("org.example.拦截器")
public class SpringbootWebQuickApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringbootWebQuickApplication.class, args);
    }

}
