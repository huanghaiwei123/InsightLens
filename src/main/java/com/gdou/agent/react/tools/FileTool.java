package com.gdou.agent.react.tools;

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
import java.util.stream.Stream;

@Slf4j
@Component
public class FileTool {
    @Autowired
    private JdbcTemplate jdbc;

//    @PostConstruct
//    private void init(){
//        jdbc.execute("LOAD excel");
//    }

    @Value("${agent.workspace}")
    private String workSpace;

//    @Tool(description = "搜索指定目录下所有用户已经上传的文件内容")
//    public String listFiles(@ToolParam(description = "这个参数是指定文件夹的相对路径，用来获取改路径的文件夹的文件") String folder){
//        Path dirt = Path.of(workSpace,folder);
//        log.info("正在搜索路径{}下的所有用户文件",dirt.toAbsolutePath());
//        try( Stream<Path> path = Files.walk(dirt)){
//            List<Path> files = path
//                    .filter(Files::isRegularFile)
//                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xlsx"))
//                    .filter(p -> !p.getFileName().toString().startsWith("~"))   // 排除 Excel 锁文件
//                    .sorted()
//                    .toList();
//            Map<String, List<Map<String, Object>>> rs = new LinkedHashMap<>();
//            for(Path file : files){
//                String abs = file.toAbsolutePath().toString();
//                List<Map<String, Object>> rows = jdbc.queryForList("select * from read_xlsx(?)",abs);
//                rs.put(abs, rows);
//            }
//            return "文件内容搜索完成，结果如下："+rs.toString();
//        } catch (IOException e) {
//            log.error("目录{}下文件搜索失败，请检查原因：{}",folder,e.getMessage());
//            return "目录"+folder+"下文件搜索失败，请检查原因："+e.getMessage();
//        }
//    }


    @Tool(description = "这个tool用来移动用户文件，使用场景是当你想要删除原有的文件分类，或者你觉得分类不合适时，你需要先调用这个方法先将当前分类下的文件先移动到新的分类里面，新的分类无需提前创建，只需要给出准确的分类相对路径即可，不然会删除分类失败")
    public String transfer(@ToolParam(description = "这个参数的意思是你想要删除的原分类的相对路径") String source, @ToolParam(description = "这个参数的意思是你想要将文件移动到的新分类的相对路径") String target){
        Path sour = Path.of(workSpace, source);
        Path tar = Path.of(workSpace, target);
        log.info("正在移动分类{}下的文件到分类{}",source,target);
        try {
            Files.createDirectories(tar);
            try(Stream<Path> path = Files.list(tar)){
                for(Path p:path.filter(Files::isRegularFile).toList()){
                    Files.move(p,tar.resolve(p.getFileName()));
                }
            }
            log.info("文件移动成功,分类路径{}下的文件已经全部进入分类路径{}下",source,target);
            return "文件移动成功，分类路径"+source+"下的文件已经全部进入分类路径"+target+"下";
        } catch (IOException e) {
            log.error("文件移动失败，请检查原因：{}",e.getMessage());
            return "文件移动失败，请检查原因"+e.getMessage();
        }
    }

    @Tool(description = "这个tool用来删除指定文件，只在用户自己告诉你需要删除的时候才会调用")
    public String deleteFile(@ToolParam(description = "这个是要删除文件的相对路径") String target){
        Path dirt = Path.of(workSpace, target);
        log.info("正在删除指定路径{}下的文件",target);
        boolean delete = dirt.toFile().delete();
        if(delete){
            log.info("文件删除成功");
            return target+"下的文件删除成功";
        }else{
            log.warn("文件删除失败");
            return target+"下的文件删除失败";
        }
    }
}
