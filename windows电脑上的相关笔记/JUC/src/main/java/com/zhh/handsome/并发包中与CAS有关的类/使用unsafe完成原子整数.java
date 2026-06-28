package com.zhh.handsome.并发包中与CAS有关的类;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

public class 使用unsafe完成原子整数 {
    /*AtomicInteger at=new AtomicInteger(0);
        at.incrementAndGet();
        at.decrementAndGet();
//        at.compareAndSet(a, a + 1);
        at.get();
        at.updateAndGet(a -> a + 1);*/
    private Unsafe unsafe;
    private volatile int value;
    public Unsafe getUnsafe() throws NoSuchFieldException, IllegalAccessException {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (Unsafe) theUnsafe.get(null);
    }
    public 使用unsafe完成原子整数(int initialValue) throws NoSuchFieldException, IllegalAccessException {
        this.unsafe = getUnsafe();
        this.value = initialValue;
    }
    public boolean compareAndSet(int expect, int update) throws NoSuchFieldException {
        long valueOffset = unsafe.objectFieldOffset(使用unsafe完成原子整数.class.getDeclaredField("value"));
        boolean b = unsafe.compareAndSwapInt(this, valueOffset, expect, update);
        return b;
    }

    public int incrementAndGet() throws NoSuchFieldException {
        //自增并且返回
        //自增的前提是先获取预期值，然后CAS更新
        while(true){
            int expect=this.value;
            if(compareAndSet(expect,expect+1)){
                return expect+1;
            }
        }
    }
    public int decrementAndGet() throws NoSuchFieldException {
        //自减并且返回
        //自减的前提是先获取预期值，然后CAS更新
        while(true){
            int expect=this.value;
            if(compareAndSet(expect,expect-1)){
                return expect-1;
            }
        }
    }
    public int get() throws NoSuchFieldException {
        return this.value;
    }
    public int updateAndGet(java.util.function.IntUnaryOperator updateFunction) throws NoSuchFieldException {
        while(true){
            int expect=this.value;
            int update=updateFunction.applyAsInt(expect);
            if(compareAndSet(expect,update)){
                return update;
            }
        }
    }


}
