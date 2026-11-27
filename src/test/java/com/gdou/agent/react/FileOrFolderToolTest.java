package com.gdou.agent.react;

import com.gdou.agent.infra.tools.FileTool;
import com.gdou.agent.infra.tools.FolderTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FileOrFolderTool 是 ReAct 暴露给模型的文件门面，只做参数透传与委托。
 * 这里 mock 掉底层 FolderTool / FileTool，不碰真实文件系统、不碰工作目录，
 * 验证模型给的相对路径能一字不差地传到执行层——路径被改写是文件操作最危险的失效模式。
 */
class FileOrFolderToolTest {

    @Test
    @DisplayName("create：相对路径原样委托给 FolderTool.createFolder，返回值回传")
    void createDelegatesWithExactPath() {
        FolderTool folderTool = mock(FolderTool.class);
        FileTool fileTool = mock(FileTool.class);
        when(folderTool.createFolder("电商运营/数码/配件")).thenReturn("分类创建完成");

        String result = new FileOrFolderTool(folderTool, fileTool)
                .create("电商运营/数码/配件");

        assertThat(result).isEqualTo("分类创建完成");
        verify(folderTool).createFolder("电商运营/数码/配件");
        verifyNoInteractions(fileTool);
    }

    @Test
    @DisplayName("transfer：源与目标路径原样委托给 FileTool.transfer，顺序不颠倒")
    void transferDelegatesWithExactPaths() {
        FolderTool folderTool = mock(FolderTool.class);
        FileTool fileTool = mock(FileTool.class);
        when(fileTool.transfer("temp/订单.xlsx", "电商运营/数码"))
                .thenReturn("文件移动成功");

        String result = new FileOrFolderTool(folderTool, fileTool)
                .transfer("temp/订单.xlsx", "电商运营/数码");

        assertThat(result).isEqualTo("文件移动成功");
        verify(fileTool).transfer("temp/订单.xlsx", "电商运营/数码");
        verifyNoInteractions(folderTool);
    }
}
