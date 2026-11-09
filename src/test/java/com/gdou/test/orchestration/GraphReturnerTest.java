package com.gdou.test.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.orchestration.GraphReturner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机统一出参 DTO。把「构造路径唯一」这个设计约定固化成测试：
 * 外部只能通过 builder() 具名构造，避免两个 String 位置传参传反。
 */
class GraphReturnerTest {

    @Test
    @DisplayName("追问出口只填 questionToUser")
    void questionOnly() {
        GraphReturner r = GraphReturner.builder().questionToUser("「这次」指哪次考试？").build();
        assertThat(r.getQuestionToUser()).isEqualTo("「这次」指哪次考试？");
        assertThat(r.getAnswer()).isNull();
    }

    @Test
    @DisplayName("答案出口只填 answer")
    void answerOnly() {
        GraphReturner r = GraphReturner.builder().answer("上周退款率 3.2%").build();
        assertThat(r.getAnswer()).isEqualTo("上周退款率 3.2%");
        assertThat(r.getQuestionToUser()).isNull();
    }

    @Test
    @DisplayName("Builder 不支持位置传参，两个字段不会串")
    void fieldsDoNotCross() {
        GraphReturner r = GraphReturner.builder()
                .questionToUser("问题")
                .answer("答案")
                .build();
        assertThat(r.getQuestionToUser()).isEqualTo("问题");
        assertThat(r.getAnswer()).isEqualTo("答案");
    }

    @Test
    @DisplayName("构造可见性：无参 public、全参 private、Builder public static 且构造器 private")
    void constructorVisibility() throws Exception {
        Constructor<GraphReturner> noArg = GraphReturner.class.getDeclaredConstructor();
        assertThat(Modifier.isPublic(noArg.getModifiers())).isTrue();

        Constructor<GraphReturner> allArgs =
                GraphReturner.class.getDeclaredConstructor(String.class, String.class);
        assertThat(Modifier.isPrivate(allArgs.getModifiers())).isTrue();

        Class<?> builder = Class.forName(GraphReturner.class.getName() + "$Builder");
        assertThat(Modifier.isPublic(builder.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(builder.getModifiers())).isTrue();
        assertThat(Modifier.isPrivate(builder.getDeclaredConstructor().getModifiers())).isTrue();
    }

    @Test
    @DisplayName("作为出参可被 Jackson 正常序列化")
    void serializeToJson() {
        com.fasterxml.jackson.databind.JsonNode node = new ObjectMapper().valueToTree(
                GraphReturner.builder().answer("答案").build());
        assertThat(node.get("answer").asText()).isEqualTo("答案");
        assertThat(node.has("questionToUser")).isTrue();
        assertThat(node.get("questionToUser").isNull()).isTrue();
    }

    @Test
    @DisplayName("无参构造 + setter 让反序列化可用（字段缺省为 null）")
    void deserializeFromPartialJson() throws Exception {
        GraphReturner r = new ObjectMapper().readValue(
                "{\"questionToUser\":\"追问内容\"}", GraphReturner.class);
        assertThat(r.getQuestionToUser()).isEqualTo("追问内容");
        assertThat(r.getAnswer()).isNull();
    }
}
