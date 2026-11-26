package com.gdou.agent.api.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 对话历史回显：只暴露前端需要的角色与正文。
 * 工具调用、system 提示词本来就不落库，这里也不会出现。
 */
@Data
@Builder
public class MessageVO {
    /** user / assistant */
    private String role;
    /** 消息正文 */
    private String text;
}
