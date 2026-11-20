package com.gdou.agent.api.tools;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
public class FileTool {
    @Autowired
    private JdbcTemplate jdbc;

    @PostConstruct
    private void init(){
        jdbc.execute("LOAD excel");
    }

    @Value("${agent.workspace}")
    private String workSpace;

    public String listFiles(String folder){
        Path dirt = Path.of(workSpace,folder);
        log.info("正在搜索路径{}下的所有用户文件",dirt.toAbsolutePath());
        try( Stream<Path> path = Files.walk(dirt)){
            List<Path> files = path
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                    .filter(p -> !p.getFileName().toString().startsWith("~"))   // 排除 Excel 锁文件
                    .sorted()
                    .toList();
            Map<String, List<Map<String, Object>>> rs = new LinkedHashMap<>();
            for(Path file : files){
                String abs = file.toAbsolutePath().toString();
                List<Map<String, Object>> rows = jdbc.queryForList("select * from read_xlsx(?)",abs);
                rs.put(abs, rows);
            }
            return "文件内容搜索完成，结果如下："+rs.toString();
        } catch (IOException e) {
            log.error("目录{}下文件搜索失败，请检查原因：{}",folder,e.getMessage());
            throw new RuntimeException(e);
        }
    }



    public String transfer(String source,  String target){
        Path sour = Path.of(workSpace, source);
        Path tar = Path.of(workSpace, target);
        log.info("正在移动分类{}下的文件到分类{}",source.substring(0,source.lastIndexOf("/")),target);
        try {
            Files.createDirectories(tar);
            Files.move(sour,tar);
            log.info("文件移动成功,分类{}下的文件已经进入分类{}下",source.substring(0,source.lastIndexOf("/")),target);
            return "文件移动成功，分类路径"+source+"下的文件已经全部进入分类路径"+target+"下";
        } catch (IOException e) {
            log.error("文件移动失败，请检查原因：{}",e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public String deleteFile(String target){
        Path dirt = Path.of(workSpace, target);
        log.info("正在删除指定路径{}下的文件",target);
        try {
            boolean delete = dirt.toFile().delete();
            if (delete) {
                log.info("文件删除成功");
                return target + "下的文件删除成功";
            } else {
                log.warn("文件删除失败");
                return "文件删除失败";
            }
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }
}
