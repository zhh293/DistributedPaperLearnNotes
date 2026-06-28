package org.example.AOP;

public class AOPDemo3 {
    /*AOP通知类型

    AOP通知描述了抽取的共性功能，根据共性功能抽取的位置不同，最终运行代码时要将其加入到合理的位置

            AOP通知共分为5种类型

    前置通知 原始方法执行前执行

            后置通知  原始方法执行后执行

    环绕通知(重点) 这里面必须写对原始方法的调用
                      Processing(ProceedingJoinPoint pjp)
                      pjp.proceed();//对原始操作进行调用
                      这个函数的返回值就是原函数的返回值

    返回后通知(了解  原方法不跑异常的时候才运行，其他跟后置通知没啥区别 )
                       抛出异常后通知(了解)
                       ●@Around注意事项

环绕通知必须依赖形参ProceedingjoinPoint才能实现对原始方法的调用，进而实现原始方法调用前后同时添加通知

2

通知中如果未使用ProceedingJoinPoint对原始方法进行调用将跳过原始方法的执行

对原始方法的调用可以不接收返回值，通知方法设置成void即可，如果接收返回值，必须设定为Object类型

3.

原始方法的返回值如果是void类型，通知方法的返回值类型可以设置成void，也可以设置成Object


由于无法预知原始方法运行后是否会抛出异常，因此环绕通知方法必须抛出Throwable对象

*/








//    AOP通知获取数据
/*AOP通知获取数据

        获取切入点方法的参数

    JoinPoint:适用于前置、后置、返回后、抛出异常后通知

    ProceedJointPoint:适用于环绕通知

            获取切入点方法返回值

    返回后通知

            环绕通知

    获取切入点方法运行异常信息

            抛出异常后通知

    环绕通知*/

/*    在 Spring AOP 中，获取数据对象主要依赖以下核心 API：
            1. JoinPoint 接口
    作用：连接点信息的抽象，提供方法签名、目标对象、参数等元数据。
    常用方法：
    getArgs()：获取方法参数数组。
    getSignature()：获取方法签名（包含方法名、参数类型、返回类型）。
    getTarget()：获取目标对象（被代理的对象）。
    getThis()：获取代理对象本身。
    ProceedingJoinPoint 接口
作用：JoinPoint 的子接口，仅用于 环绕通知，提供对目标方法的显式调用控制。
常用方法：
proceed()：执行目标方法，返回其结果。
proceed(Object[] args)：带参数执行目标方法（可修改参数）
@AfterReturning 注解参数
作用：获取目标方法的返回值。
常用属性：
returning：指定返回值的绑定变量名。

示例：

java
@AfterReturning(pointcut = "execution(* com.service.*.*(..))", returning = "result")
public void logReturnValue(JoinPoint joinPoint, Object result) {
    // result 即为目标方法的返回值
}*/


   /* Signature 接口
    作用：方法签名的抽象，用于获取方法信息。
    常用方法：
    getName()：获取方法名。
    getReturnType()：获取返回类型。
    getParameterTypes()：获取参数类型数组。*/


}
