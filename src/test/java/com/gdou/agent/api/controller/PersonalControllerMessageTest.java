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
 * 用户消息落库：入口线程池只记 user 一条，工具过程与 assistant 回答暂不入库。
 * saveUserMessage 是私有方法，按项目惯例用 ReflectionTestUtils 直接调用。
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

        ReflectionTestUtils.invokeMethod(controller, "saveUserMessage", 7, "上周各品类退款率");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(service).save(captor.capture());
        Message saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo(7);
        assertThat(saved.getRole()).isEqualTo("user");
        assertThat(saved.getContent()).isEqualTo("上周各品类退款率");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("空白问题不落库：message.content 是 NOT NULL")
    void saveUserMessageSkipsBlank() {
        MessageService service = mock(MessageService.class);
        PersonalController controller = controllerWith(service);

        ReflectionTestUtils.invokeMethod(controller, "saveUserMessage", 7, "   ");
        ReflectionTestUtils.invokeMethod(controller, "saveUserMessage", 7, null);

        verify(service, never()).save(any(Message.class));
    }
}
