package com.gdou.agent.api.tools;

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
    public String listFolder(){
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
            return "用户当前环境的各级文件分类如下："+rs;
        }catch(IOException e){
            log.error("读取用户环境工作目录下的分类文件夹失败，请检查原因：{}",e.getMessage());
            throw new RuntimeException(e);
        }
    }


    public String createFolder( String... paths){
        log.info("正在创建当前用户环境目录下的分类文件夹");
        Path root = Path.of(workSpace,paths);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.error("分类文件夹创建失败，请检查原因：{}",e.getMessage());
             throw new RuntimeException(e);
        }
        return "分类创建完成";
    }


    public String deleteFolder( String... path){
        log.info("正在删除当前用户环境目录下的分类文件夹");
        for(String p : path){
            Path dirt = Path.of(p);
            try {
                Files.delete(dirt);
            } catch (IOException e) {
                String category = p.substring(p.lastIndexOf("/"));
                log.error("分类文件夹{}删除失败{}",p,e.getMessage());
                throw new RuntimeException(e);
            }
        }
        return  "指定分类删除完成";
    }
}
