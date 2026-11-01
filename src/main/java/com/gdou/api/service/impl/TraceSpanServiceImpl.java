package com.gdou.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.meta.entity.TraceSpan;
import com.gdou.api.service.TraceSpanService;
import com.gdou.api.mapper.TraceSpanMapper;
import org.springframework.stereotype.Service;

/**
* @author huanghaiwei
* @description 针对表【trace_span(可观测链路表：一次请求的一个节点执行一行（接 Langfuse 时可不建）)】的数据库操作Service实现
* @createDate 2026-10-30 17:32:59
*/
@Service
public class TraceSpanServiceImpl extends ServiceImpl<TraceSpanMapper, TraceSpan>
    implements TraceSpanService{

}




