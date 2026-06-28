package com.zhh.handsome.Demo2;

import sun.misc.Unsafe;

public class CAS效率分析 {
    Unsafe unsafe;
    long valueOffset;
    public CAS效率分析() throws Exception {
        unsafe.compareAndSwapInt(this,valueOffset,0,1);
    }

}
