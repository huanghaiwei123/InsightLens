package com.gdou.agent.api.controller;

import com.gdou.agent.infra.ApiKeyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设置页 API Key 接口：验证 JSON 请求体能绑到 record、响应只含掩码。
 * 这里用 standalone MockMvc，不启动 Spring、不碰真实配置文件。
 */
class PersonalControllerApiKeyTest {

    private MockMvc mvc(ApiKeyService service) {
        PersonalController controller = new PersonalController();
        ReflectionTestUtils.setField(controller, "apiKeyService", service);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("读取配置：只返回掩码，不回传完整 Key")
    void readReturnsMaskedKeyOnly() throws Exception {
        ApiKeyService service = mock(ApiKeyService.class);
        when(service.status()).thenReturn(new ApiKeyService.ApiKeyStatus("sk-a****mnop", "https://api.example", true));

        mvc(service).perform(get("/personal/settings/api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.masked").value("sk-a****mnop"))
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }

    @Test
    @DisplayName("修改配置：JSON 请求体的 apiKey 能绑到 record 并交给服务")
    void updateBindsJsonBody() throws Exception {
        ApiKeyService service = mock(ApiKeyService.class);
        when(service.update("new-secret-key"))
                .thenReturn(new ApiKeyService.ApiKeyStatus("new-****-key", "https://api.example", true));

        String body = mvc(service).perform(put("/personal/settings/api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"new-secret-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.masked").value("new-****-key"))
                .andReturn().getResponse().getContentAsString();

        verify(service).update("new-secret-key");
        assertThat(body).doesNotContain("new-secret-key");
    }
}
