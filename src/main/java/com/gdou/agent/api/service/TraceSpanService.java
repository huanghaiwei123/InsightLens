package com.gdou.agent.api.service;

import com.gdou.agent.meta.entity.TraceSpan;
import com.baomidou.mybatisplus.extension.service.IService;

/**
* @author huanghaiwei
* @description 针对表【trace_span(可观测链路表：一次请求的一个节点执行一行（接 Langfuse 时可不建）)】的数据库操作Service
* @createDate 2026-10-30 17:32:59
*/
public interface TraceSpanService extends IService<TraceSpan> {

}
