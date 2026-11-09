package com.gdou.agent.orchestration;

import lombok.Data;

/**
 * 用于GraphRunner里面作为返回的类型
 */
@Data
public  class GraphReturner {
    private  String questionToUser;
    private  String answer;
    private GraphReturner() {
    }
    private GraphReturner(String questionToUser, String answer) {
        this.questionToUser = questionToUser;
        this.answer = answer;
    }

    public static Builder builder(){
        return new Builder();
    }

    /**
     * Builder内部类
     * 用于建造者模式
     */
     public  static class Builder{
         private String questionToUser;
         private String answer;
         public Builder questionToUser(String questionToUser){
            this.questionToUser = questionToUser;
             return this;
         }
         public Builder answer(String answer){
             this.answer = answer;
             return this;
         }
         public GraphReturner build(){
             return new GraphReturner(questionToUser,answer);
         }
    }
}
