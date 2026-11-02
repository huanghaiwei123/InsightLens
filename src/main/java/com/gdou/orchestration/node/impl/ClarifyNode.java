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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
        StringBuilder systemMessage = new StringBuilder();
        try(BufferedReader reader =new BufferedReader(new InputStreamReader(new FileInputStream(new ClassPathResource("message/ClarifyNode.txt").getFile())))) {
            String msg = reader.readLine();
            while (msg != null) {
                systemMessage.append(msg);
                msg = reader.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        List<Message> messages = List.of(
                new SystemMessage(systemMessage.toString()),
                new UserMessage(question));
        String raw = openAiLlmClient.call(messages);
        try {
            return objectMapper.readValue(raw, ClarifyEntity.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
