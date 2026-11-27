package com.gdou.agent.react;

import com.gdou.agent.infra.tools.FileTool;
import com.gdou.agent.infra.tools.FolderTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;

public class FileOrFolderTool  {
    @Value("${agent.workspace}")
    private String workSpace;

    private final FolderTool folderTool;

    private final FileTool fileTool;

    public FileOrFolderTool(FolderTool folderTool, FileTool fileTool) {
        this.folderTool = folderTool;
        this.fileTool = fileTool;
    }

    @Tool(description = "这是一个在工作目录下面创建分类文件夹的工具")
    public String create(@ToolParam(description = "相比于工作目录下要创建的完整分类相对路径，可以是要创建的多层路径") String paths){
        return  folderTool.createFolder(paths);
    }

    @Tool(description = "这是一个用于移动指定分类路径下的单个文件到指定分类路径下的工具")
    public String transfer(@ToolParam(description = "指定的单个文件相对于工作目录的相对路径") String source, @ToolParam(description = "想要将文件移动到的相对于工作目录下的指定分类路径") String target){
        return fileTool.transfer(source, target);
    }
}
