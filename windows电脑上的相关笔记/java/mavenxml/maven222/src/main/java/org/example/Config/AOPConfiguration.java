package org.example.Config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@ComponentScan("org.example.*")
@EnableAspectJAutoProxy
public class AOPConfiguration {
}
