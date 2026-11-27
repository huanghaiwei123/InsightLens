package com.gdou.agent.infra.tools;

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
import java.nio.file.InvalidPathException;
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



    /** 移动成功的文案前缀：调用方据此判断要不要同步 file_view 的路径 */
    public static final String TRANSFER_OK = "文件移动成功";

    public String transfer(String source,  String target){
        Path sour = Path.of(workSpace, source);
        Path tar = Path.of(workSpace, target);
        // source 不带 "/" 时 lastIndexOf 返回 -1，原来的 substring(0,-1) 会直接抛越界
        int slash = source.lastIndexOf('/');
        String category = slash < 0 ? "" : source.substring(0, slash);
        String file = slash < 0 ? source : source.substring(slash + 1);
        log.info("正在移动分类{}下的文件{}到分类{}",category,file,target);

        // Excel / WPS 打开工作簿时会在同目录留一个「~$ + 文件名」的锁文件，此时文件被独占打开。
        // Windows 上 Files.move 连改名都要独占句柄，必然失败。先看锁文件，能给出比
        // 「另一个程序正在使用此文件」更准确的提示，也让用户知道该关什么。
        if (isOpenedByOffice(sour)) {
            log.warn("文件{}正被 Excel/WPS 打开（同目录存在 ~$ 锁文件），本轮跳过移动", sour.toAbsolutePath());
            return "文件移动失败，路径 " + source + " 的表正被 Excel 或 WPS 打开，"
                    + "请关闭该文件后重新归档；其余文件不受影响。";
        }
        try {
            Files.createDirectories(tar);
            Files.move(sour,tar.resolve(file));
            log.info("文件移动成功,分类{}下的文件{}已经进入分类{}下", category, file, target);
            return TRANSFER_OK + "，分类路径"+source+"下的文件已经全部进入分类路径"+target+"下";
        } catch (IOException e) {
            // 不抛异常：一个文件没归位不该让整条上传链路失败 —— 抛出去会一路冒到
            // upload 的 catch，用户看到「文件上传失败」，其实文件已经传上去了。
            // 改成可读文案返回，模型据此告诉用户原因，其余文件继续处理。
            log.error("文件移动失败，路径：{} -> {}，原因：{}",
                    sour.toAbsolutePath(), tar.resolve(file).toAbsolutePath(), e.getMessage());
            return "文件移动失败，路径 " + source + " 的文件可能正被其他程序占用，"
                    + "请关闭后重试；其余文件不受影响。原因：" + e.getMessage();
        }
    }

    /** transfer 的返回是否表示移动成功；调用方据此决定要不要同步 file_view 元数据 */
    public static boolean isSuccess(String transferResult) {
        return transferResult != null && transferResult.startsWith(TRANSFER_OK);
    }

    /**
     * 是否正被 Excel / WPS 打开：Office 系打开工作簿时会在同目录建「~$ + 文件名」锁文件。
     * 路径异常（拿不到父目录等）时按「未打开」处理，交给 Files.move 兜底。
     */
    private boolean isOpenedByOffice(Path file) {
        Path dir = file.getParent();
        if (dir == null) {
            return false;
        }
        try {
            return Files.exists(dir.resolve("~$" + file.getFileName()));
        } catch (InvalidPathException e) {
            return false;
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
