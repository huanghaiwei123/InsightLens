package com.gdou.orchestration;

import com.gdou.orchestration.node.NodeName;

/**
 * 用来编排各个节点状态的控制器
 */
public class GraphRunner {
    public static void run(String question){
        String nodeName = NodeName.CLARIFY;
        AnalysisState state = new AnalysisState();
        state.setQuestion(question);
        while(!nodeName.equals(NodeName.DONE)){
            switch(nodeName){
                case NodeName.CLARIFY->{

                }
                case NodeName.SCHEMA_RETRIEVE->{

                }
                case NodeName.SQL_GENERATE -> {

                }
                case NodeName.SQL_GUARD -> {

                }
                case NodeName.SQL_EXECUTE-> {

                }
                case NodeName.JUDGE -> {

                }
                case NodeName.SUMMARIZE -> {

                }
            }
        }
    }

    public void apply(String nodeName){
        switch(nodeName){
            case NodeName.CLARIFY->{

            }
            case NodeName.SCHEMA_RETRIEVE->{

            }
            case NodeName.SQL_GENERATE -> {

            }
            case NodeName.SQL_GUARD -> {

            }
            case NodeName.SQL_EXECUTE-> {

            }
            case NodeName.JUDGE -> {

            }
            case NodeName.SUMMARIZE -> {

            }
        }
    }

}
