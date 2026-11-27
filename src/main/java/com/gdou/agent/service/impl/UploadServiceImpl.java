package com.gdou.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.infra.constant.CodeConstant;
import com.gdou.agent.api.exception.BusinessException;
import com.gdou.agent.infra.tools.ExcelDataListener;
import com.gdou.agent.service.CategoryService;
import com.gdou.agent.service.FileViewService;
import com.gdou.agent.service.UploadService;
import com.gdou.agent.infra.tools.FileTool;
import com.gdou.agent.infra.tools.FileTransfer;
import com.gdou.agent.infra.tools.FolderTool;
import com.gdou.agent.infra.llm.LlmClient;
import com.gdou.agent.infra.tools.FileAction;
import com.gdou.agent.meta.entity.Category;
import com.gdou.agent.meta.entity.FileView;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.sheet.ExcelReader;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.read.metadata.ReadSheet;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;


@Service
@Slf4j
public class UploadServiceImpl implements UploadService {
    @Autowired
    private FolderTool folderTool;

    @Autowired
    private FileTool fileTool;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryService  categoryService;

    @Autowired
    private FileViewService fileViewService;

    @Value("${agent.workspace}")
    private String workSpace;

    private Path temp;

    private Path root;

    @PostConstruct
    private void init() {
        // 必须转绝对路径：MultipartFile.transferTo(File) 收到相对路径时由 Tomcat
        // 按它自己的工作目录（.../work/Tomcat/localhost/ROOT）解析，而不是 JVM 的
        // user.dir。开发模式 agent.workspace=./data/excel 时会写到不存在的目录里。
        root = Path.of(workSpace).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Files.walkFileTree(root,new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    // 必须先用 toString()：getFileName() 返回的是 Path，
                    // Path.startsWith(String) 按「路径组件」比较而不是字符串前缀，
                    // "temp123" 这个单组件路径 startsWith("temp") 恒为 false，
                    // 会导致每个临时目录都被 SKIP_SUBTREE 跳过，清理等于没写。
                    if (dir.getFileName().toString().startsWith("temp") || dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    // 根目录不能删：它下面还挂着各分类目录，删它必然 DirectoryNotEmptyException，
                    // 异常冒到外层 catch 就会让 @PostConstruct 失败，整个应用起不来。
                    if (!dir.equals(root)) {
                        Files.delete(dir);
                        log.info("已清理历史临时目录 {}", dir.getFileName());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            temp = Files.createTempDirectory(root, "temp");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 用户上传的文件名添加uuid后缀
     * @param excels
     * @return
     */
    @Override
    public String upload(List<MultipartFile> excels) {
        try {
            log.info("正在为您拼命解析用户上传的文件");
            List<String> paths = new LinkedList<>();
            try {
                excels.forEach(s -> {
                    try {
                        String fileName = "%s_%s.xlsx".formatted(Objects.requireNonNull(s.getOriginalFilename(), "不知名文件.xlsx").substring(0, s.getOriginalFilename().lastIndexOf(".")), UUID.randomUUID().toString().substring(0, 8));
                        s.transferTo(temp.resolve(fileName).toFile());
                        paths.add(temp.resolve(fileName).toFile().getAbsolutePath().replace("\\", "/"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                Map<String, Object> allFile = analysisExcels(paths);
                List<Message> messages = List.of(
                        new SystemMessage(prompt("message/UploadMessage.txt")),
                        new UserMessage(
                                "用户上传的文件的信息是" + allFile
                                        + "|" + "临时目录相对于工作目录的地址是" + root.relativize(temp)
                                        + "|"
                                        + folderTool.listFolder()
                                        + "|"
                                        + existingFilesSegment()
                        )
                );
                String answer = llmClient.call(messages);
                afterLLmCall(answer);
                log.info("执行完成");
            } catch (Exception e) {
                log.error("文件上传失败", e);
                throw new BusinessException(CodeConstant.CALL_ERROR, "文件上传失败，请稍后重试");
            }
            return "文件上传完毕，已为您解析好数据";
        } finally {
            try {
                Files.walkFileTree(temp, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        // 能走到这里说明模型没把这个文件归进任何分类，删之前留条线索
                        log.warn("临时目录里残留未归类文件，即将删除：{}", file.getFileName());
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (exc != null) {
                            throw exc;
                        }
                        // 只清内容、不删 temp 目录本身：temp 是启动时定下的字段，
                        // 删掉后同一进程里的第二次上传会因父目录不存在而失败
                        return FileVisitResult.CONTINUE;
                    }
                });
                log.info("已清空临时目录 {}", temp.getFileName());
            } catch (IOException e) {
                // finally 里绝不能抛异常：会覆盖 try 里真正的业务异常，用户只会看到"上传失败"
                log.warn("临时目录清理失败，已跳过：{}", e.getMessage());
            }
        }
    }

    /**
     * 拼出归类提示词的第 4 段：现有文件清单（按分类路径分组，每个文件附首行样本）。
     * 数据取自 file_view 注册表：filePath 的父目录当分类，origName 当文件名，
     * sampleData 是监听器写入时存的首行样本字符串（形如 {sheet={列=值}}），直接拼接即可。
     * 剔除本批刚落 temp、尚未归档的新文件，避免模型把它们当作"已有文件"重复移动。
     */
    private String existingFilesSegment() {
        Path workspace = root;
        String tempRelative = workspace.relativize(temp).toString().replace('\\', '/');
        List<FileView> views = fileViewService.list();
        // 分类路径 -> 文件名 -> 首行样本字符串；同一文件多 sheet 共享 origName，putIfAbsent 去重保序
        Map<String, Map<String, String>> byCategory = new TreeMap<>();
        if (views != null) {
            for (FileView view : views) {
                String fileName = view.getOrigName();
                String storedPath = view.getFilePath();
                if (fileName == null || fileName.isBlank() || storedPath == null || storedPath.isBlank()) {
                    continue;
                }
                if (FileView.PENDING_CATEGORY_ID.equals(view.getCategoryId())) {
                    // -1 = 待归类，不属于任何已有分类，不给模型当参考
                    continue;
                }
                if (storedPath.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) {
                    // eval:// 这类伪协议不是真实文件路径
                    continue;
                }
                Path absolute;
                try {
                    absolute = Path.of(storedPath);
                } catch (InvalidPathException e) {
                    // eval:// 这类伪路径不是真实文件，跳过
                    continue;
                }
                if (!absolute.isAbsolute()) {
                    absolute = workspace.resolve(absolute);
                }
                absolute = absolute.normalize();
                if (!absolute.startsWith(workspace)) {
                    continue;
                }
                String relative = workspace.relativize(absolute).toString().replace('\\', '/');
                if (relative.equals(tempRelative) || relative.startsWith(tempRelative + "/")) {
                    // 本批新文件此刻还躺在 temp 里，不算"已有文件"
                    continue;
                }
                int lastSlash = relative.lastIndexOf('/');
                if (lastSlash <= 0) {
                    continue;
                }
                String category = relative.substring(0, lastSlash);
                // sampleData 已是 {sheet={列=值}} 字符串，老数据可能为 null，退化为 {}=无样本
                String sample = view.getSampleData() == null ? "{}" : view.getSampleData();
                byCategory.computeIfAbsent(category, key -> new LinkedHashMap<>())
                        .putIfAbsent(fileName, sample);
            }
        }
        return "用户当前环境各分类下已有的文件如下：" + byCategory;
    }

    private Map<String,Object> analysisExcels(List<String> paths) {
        Map<String,Object> allFile = new LinkedHashMap<>();
        for (String path : paths) {
            String uuid = path.substring(path.lastIndexOf("_") + 1, path.lastIndexOf("."));
            String fileName = path.substring(path.lastIndexOf("/"));
            ExcelDataListener listener = new ExcelDataListener(fileViewService,uuid);
            try(ExcelReader reader = FesodSheet.read(path).registerReadListener(listener).build()){
                List<ReadSheet> sheets = reader.excelExecutor().sheetList();
                reader.read(sheets);
                Map<String, Map<String, String>> data = listener.getData();
                allFile.put(fileName, data);
            }
        }
        return allFile;
    }

    /**
     * 用于与大模型对话后得到的分类与文件调动
     * @param answer
     */
    private void afterLLmCall(String answer) {
        try {
            FileAction action = objectMapper.readValue(answer, FileAction.class);
            List<FileTransfer> transfers = action.transfers();
            if (action.create()!=null) {
                for(String folder : action.create()){
                    folderTool.createFolder(folder);
                }
            } else if (action.delete()!=null) {
                for(String folder : action.delete()){
                    folderTool.deleteFolder(folder);
                }
            }
            for (FileTransfer transfer : transfers) {
                String moveResult = null;
                if(transfer.from()!=null && transfer.to()!=null && transfer.fileName()!=null) {
                    moveResult = fileTool.transfer(transfer.from()+"/"+transfer.fileName(), transfer.to());
                }
                // 只有移动真的成功才改元数据：文件被 Excel/WPS 占用时 transfer 返回失败
                // 而不是抛异常（避免整条上传报失败），此时文件仍在原目录，若照样把
                // file_view 指到新路径，报表里点进去就是空的
                if(transfer.to()!=null && FileTool.isSuccess(moveResult)) {
                    String largeCategory = transfer.to().split("/")[0];
                    LambdaQueryWrapper<Category> cateWrapper = new LambdaQueryWrapper<>();
                    cateWrapper.eq(Category::getName,largeCategory);
                    Category category = categoryService.getOne(cateWrapper);
                    if(category==null ) {
                        category = Category.builder()
                                .name(largeCategory)
                                .createdAt(LocalDateTime.now())
                                .build();
                        categoryService.save(category);
                    }
                    Integer categoryId = category.getId();
                    LambdaUpdateWrapper<FileView> viewWrapper = new LambdaUpdateWrapper<>();
                    viewWrapper.eq(FileView::getOrigName,transfer.fileName()).set(FileView::getCategoryId,categoryId).set(FileView::getFilePath,transfer.to()+"/"+transfer.fileName());
                    fileViewService.update(viewWrapper);
                }
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String prompt(String path) {
        try(InputStream in = new ClassPathResource(path).getInputStream()) {
           return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
