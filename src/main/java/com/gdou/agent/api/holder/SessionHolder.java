package com.gdou.agent.api.holder;

/**
 * 会话上下文：用 ThreadLocal 承载本轮请求的会话标识，供节点层无侵入读取。
 * 不放进 RunContext 是因为节点签名已固定为 (state, ctx)，多带一个 categoryId
 * 要改所有节点的调用链和测试；ThreadLocal 在 Controller 入口 set 一次即可。
 */
public class SessionHolder {

    private static final ThreadLocal<Integer> holder = new ThreadLocal<>();

    /** 窗口绑定的大分类；与 sessionId 同线程传递，节点据此限定检索范围 */
    private static final ThreadLocal<Integer> categoryHolder = new ThreadLocal<>();

    public static void set(Integer sessionId) {
        holder.set(sessionId);
    }

    public static Integer get() {
        return holder.get();
    }

    public static void setCategory(Integer categoryId) {
        categoryHolder.set(categoryId);
    }

    public static Integer getCategory() {
        return categoryHolder.get();
    }

    public static void remove() {
        holder.remove();
        categoryHolder.remove();
    }
}
