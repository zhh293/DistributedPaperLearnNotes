package org.example.AOP;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component
@Aspect
public class AOPDemo2 {

    @Pointcut("execution(* org.example.dao.impl.BookDaoimpl.update())")
    public void Pointcut() {

    }

    @Before("Pointcut()")
    public void Method(JoinPoint jp) {
        /*Signature signature = jp.getSignature();
        Object[] args = jp.getArgs();
        //获取方法上的注解
        Method method = null;
        try {
            method = jp.getTarget().getClass().getMethod(signature.getName(), new Class[]{});
            MyAnnotation annotation = method.getAnnotation(MyAnnotation.class);
            System.out.println(annotation.value());
        }
        System.out.println(System.currentTimeMillis());*/
    }
}
    /*AOP入门案例思路分析

    案例设定:测定接口执行效率

    简化设定:在接口执行前输出当前系统时间

    开发模式:XML or 注解

            思路分析

1.导入坐标(pom.xm1)

2.

    制作连接点方法(原始操作，Dao接口与实现类)

    制作共性功能(通知类与通知)

3.

    定义切入点

4.

    绑定切入点与通知关系(切面 )
*/

/*
    AOP工作流程

            Spring容器启动

1.

    读取所有切面配置中的切入点

2.

    @Component

    @Aspect

    public class MyAdvice {

        @Pointcut("execution(void com.itheima.dao.BookDao.save())")

        private void ptx(){}

        @Pointcut("execution(void com.itheima.dao.BookDao.update())")

        private void pt(){}

        @Before("pt()")

        public void method(){

            System.out.println(system.currentTimeMillis());


        }
        初始化bean，判定bean对应的类中方法是否匹配到任意切入点
           匹配失败，创建对象
           匹配成功，创建原始对象的代理对象
        获取bean执行方法
        获取bean，调用方法并执行，完成操作
        获取的bean是代理对象时，运行原始方法和增强的内容
         目标对象(Target):原始功能去掉共性功能对应的类产生的对象，这种对象是无法直接完成最终工作的

代理(Proxy):目标对象无法直接完成工作，需要对其进行功能回填，通过原始对象的代理对象实现

*/

/*AOP切入点表达式

切入点表达式标准格式:动作关键字(访问修饰符

                                        返回值

包名.类/接口名.方法名(参数)异常名)

execution (public User com.itheima.service.UserService.findById(int))

动作关键字:描述切入点的行为动作，例如execution表示执行到指定切入点

访问修饰符:public，private等，可以省略

返回值

包名

类/接口名

方法名

参数

异常名:方法定义中抛出指定异常，可以省略*/


