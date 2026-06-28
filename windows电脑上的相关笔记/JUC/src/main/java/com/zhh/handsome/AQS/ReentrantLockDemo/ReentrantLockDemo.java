package com.zhh.handsome.AQS.ReentrantLockDemo;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

public class ReentrantLockDemo implements Lock {
//    ReentrantLock
    abstract class Sync extends AbstractQueuedSynchronizer{
        @Override
        protected boolean tryRelease(int releases) {
            if (getState() == 0){
                throw new IllegalMonitorStateException();
            }
            if(getExclusiveOwnerThread()!=Thread.currentThread()){
                throw new IllegalMonitorStateException();
            }
            int c = getState() - releases;
            if (c == 0) {
                setExclusiveOwnerThread(null);
                setState(0);
                return true;
            }
            setState(c);
            return false;
        }
        protected abstract boolean tryAcquire(int arg);
        abstract boolean initialTryLock();
    }
    class NonfairSync extends Sync{
        public boolean initialTryLock(){
            if(compareAndSetState(0,1)){
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            } else if (getExclusiveOwnerThread() == Thread.currentThread()&&getState() > 0) {
                int c = getState() + 1;
                if (c < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(c);
                return true;
            }
            return false;
        }
        @Override
        protected boolean tryAcquire(int arg) {
            //所有的一块去抢
            if(getState()==0&&compareAndSetState(0,arg)){
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }
        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        // Methods relayed from outer class

        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        final boolean isLocked() {
            return getState() != 0;
        }

        /**
         * Reconstitutes the instance from a stream (that is, deserializes it).
         */
        private void readObject(java.io.ObjectInputStream s)
                throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); // reset to unlocked state
        }
    }
    class FairSync extends Sync{
        @Override
        protected boolean tryAcquire(int arg) {
            if(getState()==0&&!hasQueuedPredecessors()&&compareAndSetState(0,arg)){
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }
        @Override
        boolean initialTryLock() {
            //当同步队列为空的时候才去枪锁
            if(!hasQueuedThreads()&&compareAndSetState(0,1)){
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }else if(getExclusiveOwnerThread() == Thread.currentThread()){
                int c = getState() + 1;
                if (c < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(c);
                return true;
            }
            return false;
        }
    }
    private final Sync sync;
    private boolean fair;
    public ReentrantLockDemo(){
        this.sync = new NonfairSync();
    }
    public ReentrantLockDemo(boolean fair){
        this.fair = fair;
        this.sync = fair ? new FairSync() : new NonfairSync();
    }

    @Override
    public void lock() {
        //公平锁的话直接调用，否则的话先尝试抢锁
        if(!sync.initialTryLock()){
            sync.acquire(1);
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        ///可打断锁
        sync.acquireInterruptibly(1);
    }

    @Override
    public boolean tryLock() {
        return sync.initialTryLock();
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(1,unit.toNanos(time));
    }

    @Override
    public void unlock() {
        sync.release(1);
    }

    @Override
    public Condition newCondition() {
        return sync.new ConditionObject();
    }
}
