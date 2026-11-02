package com.gdou.orchestration.node.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.infra.LlmClient;
import com.gdou.orchestration.AnalysisState;
import com.gdou.orchestration.node.Node;
import com.gdou.orchestration.node.entity.ClarifyEntity;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图澄清状态节点执行
 */
@Service
public class ClarifyNode implements Node {
    @Resource(name = "OpenAiLLmClient")
    private LlmClient openAiLlmClient;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public Object apply(AnalysisState state) {
        String question = state.getQuestion();
        List<Message> messages = List.of(
                new SystemMessage(
                        """
                                
                                
                                """
                ),
                new UserMessage(question));
        Object run = openAiLlmClient.run(messages,ClarifyEntity.class);
        try {
            return objectMapper.readValue(run.toString(), ClarifyEntity.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
