package com.gdou.agent.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.api.excelTool.ExcelDataListener;
import com.gdou.agent.api.service.CategoryService;
import com.gdou.agent.api.service.FileViewService;
import com.gdou.agent.api.service.MessageService;
import com.gdou.agent.api.service.UploadService;
import com.gdou.agent.api.tools.FileTool;
import com.gdou.agent.api.tools.FileTransfer;
import com.gdou.agent.api.tools.FolderTool;
import com.gdou.agent.infra.LlmClient;
import com.gdou.agent.api.tools.FileAction;
import com.gdou.agent.meta.entity.Category;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @PostConstruct
    private void init() {
        Path root = Path.of(workSpace);
        try {
            Files.createDirectories(root);
            temp = Files.createTempDirectory(root,"temp");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void upload(List<MultipartFile> excels) {
        log.info("正在为您拼命解析用户上传的文件");
        Map<String,Object> allFile = analysisExcels(excels);
        excels.forEach(s-> {
            try {
                Files.copy(s.getInputStream(),temp, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        List<Message> messages = List.of(
                new SystemMessage(prompt("message/UploadMessage")),
                new UserMessage(
                        "用户上传的文件的信息是" + allFile
                                +"|"+"临时目录相对于工作目录的地址是"+Path.of(workSpace).relativize(temp)
                                + "|"
                                + folderTool.listFolder()
                )
        );
        String answer = llmClient.call(messages);
        afterLLmCall(answer);
        log.info("执行完成");
    }

    /**
     * 解析获得excel相关数据
     * @param excels
     * @return
     */
    private Map<String,Object> analysisExcels(List<MultipartFile> excels) {
        Map<String,Object> allFile = new LinkedHashMap<>();

        int count = 0;
        for (MultipartFile file : excels) {
            count++;
            var singleFile = new LinkedHashMap<String,List<Map<Integer, String>>>();
            String fileName = file.getOriginalFilename();
            ExcelDataListener listener = new ExcelDataListener();
            try(InputStream in = file.getInputStream();
                ExcelReader reader = FesodSheet.read(in).build()){
                List<ReadSheet> sheets = reader.excelExecutor().sheetList();
                log.info("文件{}中一共包含了{}个sheet，正在进行进一步解析", fileName, sheets.size());
                List<String> sheetName = sheets.stream().map(ReadSheet::getSheetName).toList();
                for (String name : sheetName) {
                    FesodSheet.read(in,listener).sheet(name).doRead();
                    List<Map<Integer, String>> data = listener.getData();
                    listener.removeData();
                    singleFile.put(name,data);
                }
            } catch (IOException e) {
                log.error("文件{}解析失败，请检查原因:{}",fileName,e.getMessage());
                throw new RuntimeException(e);
            }
            allFile.put(fileName,singleFile);
            log.info("文件{}解析成功,还剩{}个文件", fileName,excels.size()-count);
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
                    String category = transfer.to().split("/")[0];
                    LambdaQueryWrapper<Category> cateWrapper = new LambdaQueryWrapper<>();
                    cateWrapper.eq(Category::getName,category);
                    long count = categoryService.count(cateWrapper);
                    if(count==0) {
                        Category cat = Category.builder()
                                .name(category)
                                .createdAt(LocalDateTime.now())
                                .build();
                        categoryService.save(cat);
                    }
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
