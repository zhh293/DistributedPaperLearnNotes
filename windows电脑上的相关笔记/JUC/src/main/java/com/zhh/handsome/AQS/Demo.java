package com.zhh.handsome.AQS;

import lombok.NonNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Demo extends AbstractQueuedSynchronizer {

    @Override
    protected boolean tryAcquire(int arg) {
        if(compareAndSetState(0,1)){
            setExclusiveOwnerThread(Thread.currentThread());
            return true;
        }
        return false;
    }
    @Override
    protected boolean tryRelease(int arg) {
        setExclusiveOwnerThread(null);
        setState(0);
        return true;
    }
    @Override
    protected int tryAcquireShared(int arg) {
        return super.tryAcquireShared(arg);
    }
    @Override
    protected boolean tryReleaseShared(int arg) {
        return super.tryReleaseShared(arg);
    }
    @Override
    protected boolean isHeldExclusively() {
        return getState() == 1;
    }

}

class myLock implements Lock{
    private Demo demo = new Demo();

    @Override
    public void lock() {
        demo.acquire(1);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        demo.acquireInterruptibly(1);
    }

    @Override
    public boolean tryLock() {
        return demo.tryAcquire(1);
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return demo.tryAcquireNanos(1,unit.toNanos(time));
    }

    @Override
    public void unlock() {
        demo.release(1);
    }

    @Override
    @NonNull
    public Condition newCondition() {
        return demo.new ConditionObject();
    }


}
