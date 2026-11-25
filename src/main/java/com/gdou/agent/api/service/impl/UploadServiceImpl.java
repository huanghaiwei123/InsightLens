package com.gdou.agent.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.api.constant.CodeConstant;
import com.gdou.agent.api.exception.BusinessException;
import com.gdou.agent.api.tools.ExcelDataListener;
import com.gdou.agent.api.service.CategoryService;
import com.gdou.agent.api.service.FileViewService;
import com.gdou.agent.api.service.UploadService;
import com.gdou.agent.api.tools.FileTool;
import com.gdou.agent.api.tools.FileTransfer;
import com.gdou.agent.api.tools.FolderTool;
import com.gdou.agent.infra.LlmClient;
import com.gdou.agent.api.tools.FileAction;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

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
        root = Path.of(workSpace);
        try {
            Files.createDirectories(root);
            temp = Files.createTempDirectory(root,"temp");
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
        log.info("正在为您拼命解析用户上传的文件");
        List<String> paths = new LinkedList<>();
        try {
            excels.forEach(s -> {
                try {
                    String fileName = "%s_%s.xlsx".formatted(Objects.requireNonNull(s.getOriginalFilename(),"不知名文件.xlsx").substring(0, s.getOriginalFilename().lastIndexOf(".")), UUID.randomUUID().toString().substring(0, 8));
                    s.transferTo(temp.resolve(fileName).toFile());
                    paths.add(temp.resolve(fileName).toFile().getAbsolutePath());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            Map<String, Object> allFile = analysisExcels(paths);
            List<Message> messages = List.of(
                    new SystemMessage(prompt("message/UploadMessage.txt")),
                    new UserMessage(
                            "用户上传的文件的信息是" + allFile
                                    + "|" + "临时目录相对于工作目录的地址是" + Path.of(workSpace).relativize(temp)
                                    + "|"
                                    + folderTool.listFolder()
                    )
            );
            String answer = llmClient.call(messages);
            afterLLmCall(answer);
            log.info("执行完成");
        }catch (Exception e){
            log.error("文件上传失败", e);
            throw new BusinessException(CodeConstant.CALL_ERROR, "文件上传失败，请稍后重试");
        }
        return "文件上传完毕，已为您解析好数据";
    }

    private Map<String,Object> analysisExcels(List<String> paths) {
        Map<String,Object> allFile = new LinkedHashMap<>();
        for (String path : paths) {
            String uuid = path.substring(path.lastIndexOf("_") + 1, path.lastIndexOf("."));
            String fileName = path.substring(path.lastIndexOf(File.separator));
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
                if(transfer.from()!=null && transfer.to()!=null && transfer.fileName()!=null) {
                    fileTool.transfer(transfer.from()+"/"+transfer.fileName(), transfer.to());
                }
                if(transfer.to()!=null) {
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
