package com.gdou.orchestration.node.entity;

import lombok.Data;

@Data
public class ClarifyEntity {
    private String clarifiedQuestion;
    private String assumptions;
    private String needClarify;
    private String questionToUser;
}
