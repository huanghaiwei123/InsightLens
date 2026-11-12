package com.gdou.agent.api.holder;

public class SessionHolder {
    private static final ThreadLocal<Integer> holder = new ThreadLocal<>();
    public static void set(Integer sessionId) {
        holder.set(sessionId);
    }
    public static Integer get() {
        return holder.get();
    }
    public static void remove() {
        holder.remove();
    }
}
