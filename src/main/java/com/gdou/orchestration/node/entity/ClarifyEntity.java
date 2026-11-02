package com.gdou.orchestration.node.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * clarify 节点的 LLM 返回结构。
 * 提示词要求 snake_case，这里必须显式映射：
 * Jackson 默认按字段名精确匹配，不写 @JsonProperty 会静默得到全 null。
 */
@Data
public class ClarifyEntity {

    @JsonProperty("clarified_question")
    private String clarifiedQuestion;

    @JsonProperty("assumptions")
    private String[] assumptions;

    @JsonProperty("need_clarify")
    private Boolean needClarify;

    @JsonProperty("question_to_user")
    private String questionToUser;
}
