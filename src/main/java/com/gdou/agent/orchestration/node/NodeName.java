package com.gdou.agent.orchestration.node;


/**
 * 状态机的七个状态节点
 */
public class NodeName {
    public static final String CLARIFY = "clarify";   //意图澄清，它把问题理解成了什么、替你假设了什么口径
    public static final String SCHEMA_RETRIEVE = "schema_retrieve";  //选表选对了吗（Text-to-SQL 第一瓶颈）
    public static final String SQL_GENERATE = "sql_generate";  //写出来的 SQL 长什么样、第几次重写
    public static final String SQL_GUARD = "sql_guard";  //被拦了几次、拦的理由是什么
    public static final String SQL_EXECUTE = "sql_execute";  //跑通没有、返回几行、库本身耗多久
    public static final String JUDGE = "judge";  //判成 ok 还是打回重写
    public static final String SUMMARIZE = "summarize";  //结论是否带了口径回显与可追溯信息
    public static final String DONE = "done";
}
