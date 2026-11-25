package com.gdou.agent.api.service.impl;

import com.gdou.agent.api.exception.BusinessException;
import com.gdou.agent.api.service.FileDownloadService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gdou.agent.api.service.FileViewService;
import com.gdou.agent.meta.entity.FileView;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件下载的路径白名单测试：只能打开 file_view 登记且仍在 workspace 内的文件。
 */
class FileDownloadServiceImplTest {

    @TempDir
    Path workspace;

    private FileViewService fileViewService;
    private FileDownloadServiceImpl service;

    @BeforeEach
    void setUp() {
        fileViewService = mock(FileViewService.class);
        service = new FileDownloadServiceImpl();
        ReflectionTestUtils.setField(service, "fileViewService", fileViewService);
        ReflectionTestUtils.setField(service, "workSpace", workspace.toString());
    }

    @Test
    @DisplayName("登记过的文件：返回 workspace 下的绝对路径与展示文件名")
    void resolveRegisteredFile() throws IOException {
        Path folder = workspace.resolve("电商运营/历史订单");
        Files.createDirectories(folder);
        Path file = folder.resolve("订单_a1b2c3d4.xlsx");
        Files.writeString(file, "x");
        when(fileViewService.list(ArgumentMatchers.<Wrapper<FileView>>any())).thenReturn(List.of(FileView.builder()
                .uploadId("u1")
                .origName("订单_a1b2c3d4.xlsx")
                .filePath("电商运营/历史订单/订单_a1b2c3d4.xlsx")
                .build()));

        FileDownloadService.DownloadFile result = service.resolve("u1");

        assertThat(result.path()).isEqualTo(file.toAbsolutePath().normalize());
        assertThat(result.fileName()).isEqualTo("订单_a1b2c3d4.xlsx");
    }

    @Test
    @DisplayName("路径越界：即使数据库被改写也不允许打开 workspace 外的文件")
    void rejectPathOutsideWorkspace() {
        when(fileViewService.list(ArgumentMatchers.<Wrapper<FileView>>any())).thenReturn(List.of(FileView.builder()
                .uploadId("u1")
                .origName("outside.xlsx")
                .filePath("../../outside.xlsx")
                .build()));

        assertThatThrownBy(() -> service.resolve("u1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法打开");
    }

    @Test
    @DisplayName("评测伪路径 eval://：抛业务异常，不漏到兜底异常处理器")
    void rejectEvalPseudoPath() {
        when(fileViewService.list(ArgumentMatchers.<Wrapper<FileView>>any())).thenReturn(List.of(FileView.builder()
                .uploadId("eval_seed")
                .origName("orders")
                .filePath("eval://orders")
                .build()));

        assertThatThrownBy(() -> service.resolve("eval_seed"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评测数据")
                .hasFieldOrPropertyWithValue("code", com.gdou.agent.api.constant.CodeConstant.CALL_ERROR);
    }

    @Test
    @DisplayName("文件被移走：业务异常提示已不在原位置")
    void rejectMissingFile() {
        when(fileViewService.list(ArgumentMatchers.<Wrapper<FileView>>any())).thenReturn(List.of(FileView.builder()
                .uploadId("u1")
                .origName("gone.xlsx")
                .filePath("电商运营/gone.xlsx")
                .build()));

        assertThatThrownBy(() -> service.resolve("u1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已不在原位置");
    }

    @Test
    @DisplayName("uploadId 为空或查无记录：统一业务异常")
    void rejectUnknownUploadId() {
        assertThatThrownBy(() -> service.resolve(" "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少文件标识");

        when(fileViewService.list(ArgumentMatchers.<Wrapper<FileView>>any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.resolve("unknown"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }
}
