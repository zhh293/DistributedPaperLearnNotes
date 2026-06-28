package org.example.ClassDemo1.动态代理;

import org.example.ClassDemo1.反射.Student;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class ProxyUtil {
    private Object proxy1;
    public ProxyUtil(Object proxy1) {
        this.proxy1 = proxy1;
    }
    public  Object getProxy() {
        return Proxy.newProxyInstance(
                proxy1.getClass().getClassLoader(),
                proxy1.getClass().getInterfaces(),
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if(proxy instanceof star){
                            System.out.println("星星来了");
                            return method.invoke(proxy1, args);
                        }else{
                            System.out.println("你不是星星");
                            return method.invoke(proxy1, args);

                        }
                    }
                }
        );
    }
}
