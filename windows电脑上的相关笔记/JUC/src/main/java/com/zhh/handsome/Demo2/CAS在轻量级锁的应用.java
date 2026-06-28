package com.zhh.handsome.Demo2;

import java.util.Vector;

public class CAS在轻量级锁的应用 {

    Vector<Integer>vector=new Vector<>();


   /*

   轻量级锁中 CAS 的具体体现（分步骤拆解）
    前提：轻量级锁的核心载体
    对象头的 Mark Word：存储对象的锁状态（无锁、轻量级锁、重量级锁等），是 CAS 操作的 “目标内存地址”。
    线程栈帧的 Lock Record：线程在进入同步块时创建的栈内结构，包含Displaced Mark Word（复制的对象原始 Mark Word）和指向同步对象的指针，是 CAS 操作的 “预期值” 来源。
    步骤 1：线程初次获取轻量级锁时的 CAS（核心操作）
    当线程进入同步块，尝试获取轻量级锁时，会执行第一次 CAS：
    操作目标：对象的Mark Word。
    预期值：对象当前的Mark Word（必须是 “无锁状态”，即标志位lock=01，且偏向锁标志biased_lock=0）。
    要替换的值：轻量级锁标志（lock=00） + 指向当前线程Lock Record的指针。
    逻辑：
    plaintext
if (Mark Word == 无锁状态的原始值) {
        将Mark Word更新为“指向当前线程Lock Record的指针 + 00标志”
        成功：线程持有轻量级锁，进入同步块；
        失败：说明锁已被其他线程抢占，进入竞争处理逻辑。
    }
    CAS 的作用：原子性地将对象从 “无锁状态” 转为 “轻量级锁定状态”，确保只有一个线程能成功获取锁。
    步骤 2：其他线程竞争轻量级锁时的 CAS（竞争检测）
    当线程 B 尝试获取已被线程 A 持有的轻量级锁时，会执行第二次 CAS：
    操作目标：对象的Mark Word（此时已被线程 A 修改为 “指向 A 的 Lock Record + 00 标志”）。
    预期值：线程 B 自己的 Lock Record 中复制的Displaced Mark Word（即 “指向 A 的 Lock Record + 00 标志”）。
    要替换的值：指向线程B自己Lock Record的指针 + 00标志。
    逻辑：
    plaintext
if (Mark Word == 线程B复制的Displaced Mark Word) {
        将Mark Word更新为“指向B的Lock Record的指针 + 00标志”
        成功：理论上可获取锁（但轻量级锁不允许同时持有，实际会触发其他逻辑）；
        失败：确认锁被线程A持有，触发锁膨胀或自旋。
    }
    CAS 的作用：检测锁的竞争状态。由于轻量级锁是排他锁，此 CAS 必然失败（因为线程 A 未释放锁），但通过失败结果，线程 B 能确认 “锁已被占用”，进而执行后续处理（自旋或膨胀）。
    步骤 3：线程释放轻量级锁时的 CAS（锁状态恢复）
    当持有轻量级锁的线程（如线程 A）退出同步块时，会执行第三次 CAS：
    操作目标：对象的Mark Word（此时仍为 “指向 A 的 Lock Record + 00 标志”）。
    预期值：指向线程A自己Lock Record的指针 + 00标志（即当前 Mark Word 的值）。
    要替换的值：线程 A 的 Lock Record 中保存的Displaced Mark Word（即对象原始的无锁状态 Mark Word）。
    逻辑：
    plaintext
if (Mark Word == 指向A的Lock Record的指针 + 00标志) {
        将Mark Word恢复为原始的无锁状态（Displaced Mark Word）
        成功：无竞争，锁释放完成；
        失败：说明有其他线程竞争过（如线程B尝试过获取锁），需触发锁膨胀后的唤醒逻辑。
    }
    CAS 的作用：原子性地将对象从 “轻量级锁定状态” 恢复为 “无锁状态”，确保释放过程的线程安全。
    总结：CAS 在轻量级锁中的核心价值
    轻量级锁本质是 “基于 CAS 的自旋锁”，CAS 的作用体现在三个关键节点：
    获取锁：通过 CAS 原子抢占锁，标记对象为 “轻量级锁定”；
    竞争检测：通过 CAS 失败判断锁已被占用，触发后续处理；
    释放锁：通过 CAS 原子恢复锁状态，确保无竞争时的安全释放。

    */

}
