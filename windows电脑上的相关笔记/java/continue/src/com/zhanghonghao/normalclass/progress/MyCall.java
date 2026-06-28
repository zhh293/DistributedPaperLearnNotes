package com.zhanghonghao.normalclass.progress;

import java.util.concurrent.Callable;

public class MyCall implements Callable<String> {

    @Override
    public String call() throws Exception {
        return "我是你爸爸";
    }
}
