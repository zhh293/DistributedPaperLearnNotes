package org.example.springai1.Configuration;

import org.springframework.cglib.proxy.NoOp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.FormLoginConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public UserDetailsService userDetailsService(){
        UserDetails user= User.withUsername("user").password("123456").roles("USER").build();
        UserDetails admin= User.withUsername("admin").password("123456").authorities("ADMIN").build();
        return new InMemoryUserDetailsManager(user,admin);
    }
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception{
        http.csrf(c->c.disable())
                .authorizeHttpRequests(a->{
            a.requestMatchers("/ai/**").permitAll()
                    .anyRequest().authenticated();
        }).with(new FormLoginConfigurer<>(), Customizer.withDefaults());
        return http.build();
    }
    @Bean
    public static PasswordEncoder passwordEncoder(){
        return NoOpPasswordEncoder.getInstance();
    }
}


/*
二、详细流程：从用户提交密码到信息存入 SecurityContext
1. 用户提交登录请求（触发过滤器）
当用户在登录页输入用户名 / 密码并提交时，请求会被 Spring Security 过滤器链中的 **UsernamePasswordAuthenticationFilter** 拦截（默认处理POST /login请求）。

该过滤器会从请求中提取关键信息：

用户名（默认从请求参数username获取）
密码（默认从请求参数password获取）
        2. 创建「未认证」的 Authentication 对象
UsernamePasswordAuthenticationFilter会用提取到的用户名和密码，创建一个未认证状态的UsernamePasswordAuthenticationToken对象（这是Authentication接口的实现类）：

java
        运行
// 伪代码：过滤器内部逻辑
String username = request.getParameter("username");
String password = request.getParameter("password");
Authentication authRequest = new UsernamePasswordAuthenticationToken(username, password);

此时这个对象的authenticated属性为false（未认证），且不包含权限信息。
        3. 交给 AuthenticationManager 进行认证
过滤器会将这个未认证的Authentication对象传递给 **AuthenticationManager**（认证管理器，Spring Security 的认证核心接口）。

你的配置中没有显式定义AuthenticationManager，但 Spring Security 会自动创建一个默认实现ProviderManager，它会委托给 **DaoAuthenticationProvider**（处理用户名密码认证的具体实现）。
        4. 调用 UserDetailsService 查询用户信息
DaoAuthenticationProvider会执行以下操作：

调用你定义的UserDetailsService（即InMemoryUserDetailsManager）的loadUserByUsername(username)方法，根据用户名查询用户信息。
比如用户输入username=user，就会返回你配置的UserDetails user对象（包含用户名user、密码123456、角色ROLE_USER）。
得到UserDetails对象后，DaoAuthenticationProvider会检查该用户是否被锁定、禁用等（你的配置中都是正常用户，这一步通过）。
        5. 密码校验（核心！）
DaoAuthenticationProvider会使用 **PasswordEncoder** 比对用户提交的密码和UserDetails中存储的密码：

你提交的密码：用户输入的明文123456（从请求中提取）。
存储的密码：UserDetails对象中的123456（你配置的明文，实际开发中必须加密）。
        ⚠️ 注意：你的代码中没有配置PasswordEncoder，Spring Security 会默认使用DelegatingPasswordEncoder，而它要求密码必须加密（格式如{bcrypt}$2a$10...），所以你的代码实际运行时会登录失败，需要补充PasswordEncoder配置（参考之前的补充说明）。
如果密码一致，校验通过；否则抛出BadCredentialsException（认证失败）。
        6. 生成「已认证」的 Authentication 对象
密码校验通过后，DaoAuthenticationProvider会创建一个已认证状态的UsernamePasswordAuthenticationToken对象：

包含用户名（principal）
权限集合（从UserDetails的getAuthorities()获取，比如ROLE_USER或ROLE_ADMIN）
authenticated属性设为true
7. 将认证结果存入 SecurityContext
DaoAuthenticationProvider将已认证的Authentication对象返回给UsernamePasswordAuthenticationFilter，过滤器会执行关键操作：

java
        运行
// 伪代码：过滤器内部逻辑
SecurityContext context = SecurityContextHolder.getContext();
context.setAuthentication(authenticatedToken); // 将已认证对象存入上下文


SecurityContextHolder是一个工具类，内部通过线程局部变量（ThreadLocal） 存储SecurityContext，确保当前请求线程中随时可以获取认证信息。
此时SecurityContext中就包含了用户的完整认证信息（用户名、权限等）。
        8. 后续：登录成功处理与上下文持久化
登录成功后，默认会触发SimpleUrlAuthenticationSuccessHandler，重定向到首页（或配置的defaultSuccessUrl）。
同时，SecurityContextPersistenceFilter会将SecurityContext存入HttpSession（以便后续请求复用），并在请求结束时清理线程局部变量。*/




/*
Spring Security 默认的登录流程（通过UsernamePasswordAuthenticationFilter处理/login请求）会自动完成认证并将结果存入SecurityContext。
但如果你自己定义了/login接口（即绕过了默认过滤器），则需要手动实现以下步骤，



ProviderManager默认仍会创建：只要不自定义AuthenticationManager，Spring Security 会自动提供，你可以在自定义接口中注入并复用它完成认证。
自定义/login必须手动处理两件事：
调用认证逻辑（推荐用ProviderManager.authenticate()，而非自己写密码校验）；
将认证成功的Authentication对象存入SecurityContext（否则后续请求无法识别登录状态）。

简单说：自定义登录接口只是 “接管了请求入口”，但核心的认证逻辑（用户查询、密码校验）仍可复用 Spring Security 的组件，只是最后一步的 “上下文存储” 需要手动完成。
*/


/*
哦哦哦，也就是说springsecurity默认配置中的UserDetailService实现类是InMemoryUserDetailsManager，并且可以使用@Bean注解来自定义初始化用户和密码，就像这样
@Bean
public UserDetailsService userDetailsService(){
    UserDetails user= User.withUsername("user").password("123456").roles("USER").build();
    UserDetails admin= User.withUsername("admin").password("123456").roles("ADMIN").build();
    return new InMemoryUserDetailsManager(user,admin);
}
如果不使用注解的话，springsecurity会自己创建用户名和密码并打印在控制台，对吗
你的理解完全正确！

当你没有自定义UserDetailsService Bean时，Spring Security 的自动配置会：

默认创建InMemoryUserDetailsManager实例作为UserDetailsService的实现类；
自动生成一个默认用户：
用户名固定为 user；
密码是随机生成的 UUID（格式类似 6833a7b1-7f5c-493a-871c-7447d3975853），会在项目启动时打印到控制台（日志级别为INFO）；
角色固定为 ROLE_USER。

而当你通过@Bean自定义UserDetailsService（如你代码中返回InMemoryUserDetailsManager并初始化user和admin用户）时，Spring Security 会优先使用你的自定义配置，不再生成默认用户和随机密码。

补充一个细节：默认生成的密码是经过加密的（Spring Security 自动使用PasswordEncoder处理），而你代码中直接用明文密码（"123456"）需要显式配置PasswordEncoder（否则登录会失败），这一点之前也提到过～*/







