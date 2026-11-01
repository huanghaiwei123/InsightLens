package com.gdou.infra;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.gdou.api.hook.CustomStopConditionHook;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.*;
import java.util.List;

/**
 * openai专用
 */
@Service("OpenAiLLmClient")
public class OpenAiLLmClient implements LlmClient {
    private final Integer turns;
    public OpenAiLLmClient(@Value("${spring.ai.chat.turns}") Integer turns) {
        this.turns = turns;
    }

    @Override
    public Object run(String userMsg) {
        OpenAiApi openaiApiKey = OpenAiApi.builder().apiKey(System.getenv("OPENAI_API_KEY")).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openaiApiKey)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(OpenAiApi.ChatModel.GPT_5_CHAT_LATEST)
                        .temperature(0.7)    // 控制随机性
                        .maxTokens(200000)     // 最大输出长度
                        .topP(0.9)           // 核采样参数
                        .build()
                )
                .build();
       StringBuilder systemMessage=new StringBuilder();
       try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(new ClassPathResource("message/SystemMessage.txt").getFile())))) {
           String line;
          while(true) {
              line = reader.readLine();
              if(line == null) break;
              systemMessage.append(line);
          }
       } catch (IOException e) {
           throw new RuntimeException(e);
       }
       List<Message> messages = List.of(
               new SystemMessage(systemMessage.toString()),
               new UserMessage(userMsg)
       );
       // 创建 Agent
       ReactAgent agent = ReactAgent.builder()
                .name("my_agent")
                .model(chatModel)
                .hooks(ModelCallLimitHook.builder().runLimit(turns).build()  // 限制最多调用 5 次
                        ,new CustomStopConditionHook()
                )
                .saver(new MemorySaver())
                .build();
        try {
            Flux<NodeOutput> stream = agent.stream(messages);
            streamOutPut(stream);
            return stream;
        } catch (GraphRunnerException e) {
            throw new RuntimeException(e);
        }
    }

    public void streamOutPut(Flux<NodeOutput> stream){
        stream.subscribe(
                output -> {
                    // 检查是否为 StreamingOutput 类型
                    if (output instanceof StreamingOutput streamingOutput) {
                        OutputType type = streamingOutput.getOutputType();

                        // 处理模型推理的流式输出
                        if (type == OutputType.AGENT_MODEL_STREAMING) {
                            // 流式增量内容，逐步显示
                            System.out.print(streamingOutput.message().getText());
                        } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                            // 模型推理完成，可获取完整响应
                            System.out.println("\n模型输出完成");
                        }

                        // 处理工具调用完成（目前不支持 STREAMING）
                        if (type == OutputType.AGENT_TOOL_FINISHED) {
                            System.out.println("工具调用完成: " + output.node());
                        }

                        // 对于 Hook 节点，通常只关注完成事件（如果Hook没有有效输出可以忽略）
                        if (type == OutputType.AGENT_HOOK_FINISHED) {
                            System.out.println("Hook 执行完成: " + output.node());
                        }
                    }
                },
                error -> System.err.println("错误: " + error),
                () -> System.out.println("Agent 执行完成")
        );
    }

}
