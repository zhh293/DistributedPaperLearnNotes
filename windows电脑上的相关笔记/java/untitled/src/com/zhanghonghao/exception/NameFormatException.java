package com.zhanghonghao.exception;

public class NameFormatException extends RuntimeException {
    //注意命名规范
    //运行时，编译时
    public NameFormatException() {
        super();
    }
    public NameFormatException(String message) {
        super(message);
    }
}
