package org.example.springbootwebquick;

public class result {
    private int code;//1为成功，0为失败
    private String msg;//ok为正确，error为错误
    private Object data;
    public result() {}
    public result(int code, String msg, Object data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }
    public int getCode() {
        return code;

    }
    public void setCode(int code) {
        this.code = code;
    }
    public String getMsg() {
        return msg;
    }
    public void setMsg(String msg) {
        this.msg = msg;
    }
    public Object getData() {

        return data;
    }
    public void setData(Object data) {
        this.data = data;
    }
    public static result ok() {
        return new result(200, "ok", null);
    }
    public static result ok(Object data) {
        return new result(200, "ok", data);
    }

    public static result ok(String msg) {
        return new result(200, msg, null);
    }
    public static result ok(String msg, Object data) {
        return new result(200, msg, data);
    }
    public static result error() {
        return new result(500, "error", null);
    }
    public static result error(String msg) {
        return new result(500, msg, null);
    }
    public static result error(String msg, Object data) {
        return new result(500, msg, data);
    }



}
