package com.gdou.agent.api.common;

public class Result {
    public String code;
    public String msg;
    public Object data;
    public Result(String code, String msg, Object data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }
    public static Result success(String code, String msg, Object data) {
        return new Result(code, msg, data);
    }
    public static Result error(String code, String msg, Object data) {
        return new Result(code, msg, data);
    }
}
