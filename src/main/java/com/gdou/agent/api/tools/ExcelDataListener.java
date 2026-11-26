package com.gdou.agent.api.tools;

import com.gdou.agent.api.service.FileViewService;
import com.gdou.agent.meta.entity.FileView;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 不能被spring管理
 */
@Slf4j
public class ExcelDataListener extends AnalysisEventListener<Map<Integer,String>> {

    /**
     * sheetName为key，单行数据为value，一个data存放单个文件所有sheet数据
     */
    @Getter
    private final Map<String,Map<String,String>> data = new LinkedHashMap<>();

    private final FileViewService fileViewService;

    private Map<Integer,String> headMap;

    private final String uuid;

    public ExcelDataListener(FileViewService fileViewService, String uuid) {
        this.fileViewService = fileViewService;
        this.uuid = uuid;
    }

    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
        this.headMap = headMap;
    }

    @Override
    public void invoke(Map<Integer, String> integerStringMap, AnalysisContext ctx) {
        String sheetName = ctx.readSheetHolder().getReadSheet().getSheetName();
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        integerStringMap.forEach(
                (index,value)->{
                    String head = headMap.getOrDefault(index,"未知列");
                    map.putIfAbsent(head, value);
                }
        );
        data.putIfAbsent(sheetName, map);
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext ctx) {
        String fileName = ctx.readWorkbookHolder().getFile().getName();
        String filePath = ctx.readWorkbookHolder().getFile().getPath();
        String sheetName = ctx.readSheetHolder().getReadSheet().getSheetName();
        FileView view = FileView.builder()
                .uploadId(uuid)
                .origName(fileName)
                .sheetName(sheetName)
                .filePath(filePath)
                .rowCount((long) data.size())
                .sampleData(data.toString())
                .status("success")
                .createdAt(LocalDateTime.now())
                .build();
        fileViewService.save(view);
        log.info("当前文件{}的sheet{}数据读取完成",fileName,sheetName);
    }

    public void removeData(){
        data.clear();
    }
}
