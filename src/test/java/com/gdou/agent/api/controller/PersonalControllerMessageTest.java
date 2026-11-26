package com.gdou.agent.api.controller;

import com.gdou.agent.api.service.MessageService;
import com.gdou.agent.meta.entity.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

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
}
