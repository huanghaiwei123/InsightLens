package com.gdou.agent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.*;


// ModelInterceptor - 内容安全检查
public class GuardrailInterceptor extends ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
//        // 前置：检查输入
//        if (containsSensitiveContent(request.getMessages())) {
//            return ModelResponse.of(AssistantMessage.builder().content("检测到不适当的内容").build());
//        }
//
        // 执行调用
        ModelResponse response = handler.call(request);
//
//        // 后置：检查输出
//        return sanitizeIfNeeded(response);
        return response;
    }

    @Override
    public String getName() {
        return "";
    }
}

// ToolInterceptor - 监控和错误处理
class ToolMonitoringInterceptor extends ToolInterceptor {
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        long startTime = System.currentTimeMillis();
        try {
            ToolCallResponse response = handler.call(request);
            return response;
        } catch (Exception e) {
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
                    "工具执行遇到问题，请稍后重试");
        }
    }

    @Override
    public String getName() {
        return "";
    }
}