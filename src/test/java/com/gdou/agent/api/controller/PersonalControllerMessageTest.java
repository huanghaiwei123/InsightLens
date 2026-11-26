package com.gdou.agent.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gdou.agent.api.common.Result;
import com.gdou.agent.api.service.MessageService;
import com.gdou.agent.api.vo.MessageVO;
import com.gdou.agent.meta.entity.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话消息落库：入口记 user 原话，模型收口后记 assistant 文本。
 * 工具调用与 system 提示词不落库。saveMessage 是私有方法，用 ReflectionTestUtils 直接调用。
 */
class PersonalControllerMessageTest {

    private PersonalController controllerWith(MessageService messageService) {
        PersonalController controller = new PersonalController();
        ReflectionTestUtils.setField(controller, "messageService", messageService);
        return controller;
    }

    @Test
    @DisplayName("用户原话落库：sessionId / role=user / content=原文 都正确")
    void saveUserMessagePersistsOriginalText() {
        MessageService service = mock(MessageService.class);
        when(service.save(any(Message.class))).thenReturn(true);
        PersonalController controller = controllerWith(service);

        ReflectionTestUtils.invokeMethod(controller, "saveMessage", 7, "上周各品类退款率", "user");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(service).save(captor.capture());
        Message saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo(7);
        assertThat(saved.getRole()).isEqualTo("user");
        assertThat(saved.getContent()).isEqualTo("上周各品类退款率");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("模型文本落库：澄清提问与最终回答都按 assistant 角色记一条")
    void saveAssistantMessage() {
        MessageService service = mock(MessageService.class);
        when(service.save(any(Message.class))).thenReturn(true);
        PersonalController controller = controllerWith(service);

        ReflectionTestUtils.invokeMethod(controller, "saveMessage", 7, "「这次」指哪次考试？", "assistant");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(service).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("assistant");
        assertThat(captor.getValue().getContent()).isEqualTo("「这次」指哪次考试？");
    }

    @Test
    @DisplayName("空白内容不落库：message.content 是 NOT NULL")
    void saveMessageSkipsBlank() {
        MessageService service = mock(MessageService.class);
        PersonalController controller = controllerWith(service);

        ReflectionTestUtils.invokeMethod(controller, "saveMessage", 7, "   ", "user");
        ReflectionTestUtils.invokeMethod(controller, "saveMessage", 7, null, "assistant");
        ReflectionTestUtils.invokeMethod(controller, "saveMessage", 7, "", "assistant");

        verify(service, never()).save(any(Message.class));
    }

    @Test
    @DisplayName("读历史：按 sessionId 查出的消息转成 role/text，顺序交给 SQL 的 createdAt 升序")
    void historyReturnsRoleAndText() {
        MessageService service = mock(MessageService.class);
        PersonalController controller = controllerWith(service);
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                Message.builder().role("user").content("上周退款率").build(),
                Message.builder().role("assistant").content("上周退款率是 12%").build()
        ));

        Result result = controller.history(7);

        assertThat(result.data).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<MessageVO> data = (List<MessageVO>) result.data;
        assertThat(data).extracting(MessageVO::getRole).containsExactly("user", "assistant");
        assertThat(data).extracting(MessageVO::getText)
                .containsExactly("上周退款率", "上周退款率是 12%");
    }

    @Test
    @DisplayName("recentMessages：时间倒序读取后反转成正序，且只保留最新 12 条")
    void recentMessagesAreChronologicalAndCapped() {
        MessageService service = mock(MessageService.class);
        PersonalController controller = controllerWith(service);
        // 模拟 messageService 按 createdAt 倒序返回 14 条
        List<Message> desc = new ArrayList<>();
        for (int i = 14; i >= 1; i--) {
            desc.add(Message.builder().role(i % 2 == 0 ? "user" : "assistant")
                    .content("第" + i + "条").createdAt(LocalDateTime.of(2026, 11, 26, 0, 0).plusMinutes(i)).build());
        }
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(desc);

        @SuppressWarnings("unchecked")
        List<org.springframework.ai.chat.messages.Message> history =
                (List<org.springframework.ai.chat.messages.Message>)
                        ReflectionTestUtils.invokeMethod(controller, "recentMessages", 7);

        // 上限 12 条，丢弃最旧的「第1条」「第2条」，剩下 3..14 按时间正序
        assertThat(history).hasSize(12);
        assertThat(history.get(0).getText()).isEqualTo("第3条");
        assertThat(history.get(11).getText()).isEqualTo("第14条");
    }
}
