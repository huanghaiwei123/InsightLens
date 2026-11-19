package com.gdou.agent.react.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
public class FolderTool {
    @Value("${agent.workspace}")
    private String workSpace;

    /**
     * 返回结果格式：{1=[batch-001, batch-002, archive], 2=[batch-001/sub, batch-002/empty]}
     * @return
     */
    @Tool(description =
            """
              列出当前工作目录下的文件夹，每一级文件夹都相当于一级文件分类
            """)
    public String ListFolder(){
        log.info("正在检查当前用户环境目录下的分类文件夹");
        Path root = Path.of(workSpace);
        if(!Files.isDirectory(root)){
            log.error("当前环境工作目录没有创建或已被删除，请先创建");
            return "当前环境工作目录没有创建或已被删除，请先创建";
        }
        int rootDepth = root.getNameCount();
        try(Stream<Path>  path = Files.walk(root)){
            log.info("正在解析各级文件夹");
            String rs = path.filter(Files::isDirectory).filter(p -> !p.equals(root))
                    .collect(Collectors.groupingBy(p -> p.getNameCount() - rootDepth, TreeMap::new, Collectors.mapping(root::relativize, Collectors.toList()))).toString();
            return "各级文件分类如下："+rs;
        }catch(IOException e){
            log.error("读取用户环境工作目录下的分类文件夹失败，请检查原因：{}",e.getMessage());
            return "读取文件分类失败";
        }
    }

    @Tool(description = "创建分类文件夹用来存放特定分类下的文件,若是工作目录也不存在，调用这个tool也会自动先行创建工作目录,无需额外调用，使用场景是随着文件的增多，你觉得当前分类下的文件太杂了，需要细分")
    public String createFolder(@ToolParam(required = false,description = "这个参数是用来在工作目录下创建出分类文件夹的，参数是一个数组，从左到右文件夹层次逐渐深入，请注意参数顺序") String... paths){
        log.info("正在创建当前用户环境目录下的分类文件夹");
        Path root = Path.of(workSpace,paths);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.error("分类文件夹创建失败，请检查原因：{}",e.getMessage());
            return "分类创建失败，请检查原因："+e.getMessage();
        }
        return "分类创建完成";
    }

    @Tool(description = "删除分类文件夹，这个tool的使用场景是你或者用户觉得当前分类不再合适的时候使用，不过当前文件夹里面还有文件的时候会报异常所以需要先移动文件到新的文夹")
    public String deleteFolder(@ToolParam(description = "这个参数是用来删除指定路径的分类文件夹的，参数是一个字符串数组，每个参数都是一个要删除的文件路径，你可以截取你想要删除的那个路径作为最后一级目录进行删除") String... path){
        log.info("正在删除当前用户环境目录下的分类文件夹");
        for(String p : path){
            Path dirt = Path.of(p);
            try {
                Files.delete(dirt);
            } catch (IOException e) {
                String category = p.substring(p.lastIndexOf("/"));
                log.error("分类文件夹{}删除失败{}",p,e.getMessage());
                return "分类"+category+"删除失败,请检查原因："+e.getMessage();
            }
        }
        return  "指定分类删除完成";
    }
}
