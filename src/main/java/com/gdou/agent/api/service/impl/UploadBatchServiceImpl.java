package com.gdou.agent.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.agent.api.mapper.UploadBatchMapper;
import com.gdou.agent.api.service.UploadBatchService;
import com.gdou.agent.meta.entity.UploadBatch;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * @author huanghaiwei
 * @description 针对表【upload_batch(导入批次表：一行 = 一个 sheet 的导入)】的数据库操作Service实现
 * @createDate 2026-11-03 17:33:00
 */
@Service
public class UploadBatchServiceImpl extends ServiceImpl<UploadBatchMapper, UploadBatch>
        implements UploadBatchService {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UploadBatchMapper uploadBatchMapper;

    @PostConstruct
    public void init() {
        jdbc.execute("SET file_search_path = '/data/uploads'");
        jdbc.execute("LOAD excel");
    }

    /**
     * 这里想要使用duckdb读取excel数据，需要知道excel文件路径，考虑到用户是直接拖拽上传并且方便用户获取自己曾经上传过的文件，这里将上传的excel文件作为正式文件存储
     *
     * @param excels
     * @return
     */
    @Override
    @Transactional
    public Boolean create(List<MultipartFile> excels) {
        Map<String, String> sheets = analysis(excels);
        for(String path : sheets.keySet()){
            String sheet = sheets.get(path);
            List<Map<String, Object>> rs = jdbc.queryForList("SELECT * FROM read_xlsx(?, sheet = ?),path,sheet");
        }
        return null;
    }

    private Map<String, String> analysis(List<MultipartFile> excels) {
        String uploadId = UUID.randomUUID().toString().substring(0, 8);
        LinkedHashMap<String, String> sheets = new LinkedHashMap<>();
        for (MultipartFile excel : excels) {
            try {
                String name = excel.getOriginalFilename() + "_" + uploadId;
                Path formal = Paths.get("/data/upload/", name + ".xlsx");
//        检查/data/upload/是否存在，不存在则递归创建，存在也不会报错
                Files.createDirectories(formal.getParent());
                Files.copy(excel.getInputStream(), formal, StandardCopyOption.REPLACE_EXISTING);
                String path = formal.toAbsolutePath().toString();
                try(ZipFile zip = new ZipFile(path)){
                    ZipEntry entry = zip.getEntry("xl/workbook.xml");
                    String xml;
                    InputStream in = zip.getInputStream(entry);
                    xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("<sheet[^>]*name=\"([^\"]+)\"").matcher(xml);
                    while(m.find()) {
                        sheets.put(path,m.group(1));
                    }
                }
            }catch (Exception e){
                throw new RuntimeException(e);
            }
        }
        return sheets;
    }
}
