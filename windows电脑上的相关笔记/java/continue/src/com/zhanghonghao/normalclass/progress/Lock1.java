package com.zhanghonghao.normalclass.progress;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Lock1 implements Runnable {
    String name;
    Lock lock=new ReentrantLock();
    int count=0;
    @Override
    public void run() {
        try {
            while(true){
                lock.lock();
                if(count<100){
                    Thread.sleep(10);
                    count++;
                    System.out.println(Thread.currentThread().getName()+" "+count);
                }else{
                    lock.unlock();
                    break;
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
        /*虽然我们可以理解同步代码块和同步方法的锁对象问题
但是我们并没有直接看到在哪里加上了锁，在哪里释放了锁
为了更清晰的表达如何加锁和释放锁，IDK5以后提供了一个新的锁对象Lock
Lock实现提供比使用synchronized方法和语句可以获得更广泛的锁定操作
Lock中提供了获得锁和释放锁的方法
void lock():获得锁
void unlock():释放锁
手动上锁、手动释放锁
Lock是接口不能直接实例化
这里采用它的实现类ReentrantLock来实例化
ReentrantLock的构造方法
ReentrantLock():
创建一个ReentrantLock的实例

*/
        /*while(true){
            lock.lock();
            if(count==100){
                lock.unlock();
                break;
            }else {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }finally {
                    lock.unlock();
                }
                count++;
                System.out.println(Thread.currentThread().getName()+"count:"+count);
            }
            //lock.unlock();
        }*/
    }
}
/*如果我继承的是thread类，那么记得在lock前面加上static共享数据
* 但是这么写会导致死锁，可能unlock没有执行，只执行了lock方法，应该能想到对应的场景，就不举例了*/
//finally就是不管会不会抛出异常，总之里面的代码一定会执行，复习一下嘻嘻，忘记了