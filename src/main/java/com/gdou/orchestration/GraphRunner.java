package com.gdou.orchestration;

import com.gdou.orchestration.node.Node;
import com.gdou.orchestration.node.NodeName;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用来编排各个节点状态的控制器
 */

@Component
public class GraphRunner {
    private String nodeName = NodeName.CLARIFY;
    private final Map<String, Node> node;
    public GraphRunner(Map<String, Node> node) {
        this.node = node;
    }
    public Object run(String question){
        AnalysisState state = new AnalysisState();
        state.setQuestion(question);
        while(!nodeName.equals(NodeName.DONE)){
            switch(nodeName){
                case NodeName.CLARIFY->{
                    node.get(NodeName.CLARIFY).apply(state,null);
                    nextClarify(state);
                    if(nodeName.equals(NodeName.DONE)){
                        return state.getQuestionToUser();
                    }
                }
                case NodeName.SCHEMA_RETRIEVE->{
                    node.get(NodeName.SCHEMA_RETRIEVE).apply(state,null);
                    nextSchemaRetrieve();
                }
                case NodeName.SQL_GENERATE -> {
                    node.get(NodeName.SQL_GENERATE).apply(state,null);
                    nextSqlGenerate();
                }
                case NodeName.SQL_GUARD -> {
                    node.get(NodeName.SQL_GUARD).apply(state,null);
                    nextSqlGuard(state);
                }
                case NodeName.SQL_EXECUTE-> {
                    node.get(NodeName.SQL_EXECUTE).apply(state,null);
                    nextSqlExecute(state);
                }
                case NodeName.JUDGE -> {
                    node.get(NodeName.JUDGE).apply(state,null);
                    nextSqlJudge(state);
                }
                case NodeName.SUMMARIZE -> {
                    node.get(NodeName.SUMMARIZE).apply(state,null);
                }
            }
        }
        return null;
    }

    private void nextClarify(AnalysisState state) {
        if(state.getNeedClarify().equals(Boolean.TRUE)){
            nodeName = NodeName.DONE;
        }else{
            state.setClarifiedQuestion(state.getQuestion());
        }
        nodeName = NodeName.SCHEMA_RETRIEVE;
    }

    private void nextSchemaRetrieve() {
        nodeName = NodeName.SQL_GENERATE;
    }

    private void nextSqlGenerate() {
        nodeName = NodeName.SQL_GUARD;
    }

    private void nextSqlGuard(AnalysisState state){
        nodeName = state.getValidationResult().equals("pass")?NodeName.SQL_GENERATE:NodeName.SQL_EXECUTE;
    }

    private void nextSqlExecute(AnalysisState state) {
        nodeName = NodeName.JUDGE;
    }

    private void nextSqlJudge(AnalysisState state) {
        nodeName = state.getError() == null?NodeName.SQL_GENERATE:NodeName.SUMMARIZE;
    }

}
