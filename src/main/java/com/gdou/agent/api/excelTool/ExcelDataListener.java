package com.gdou.agent.api.excelTool;

import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 不能被spring管理
 */
@Slf4j
public class ExcelDataListener extends AnalysisEventListener<Map<Integer,String>> {
    private final List<Map<Integer,String>> data = new LinkedList<Map<Integer,String>>();
    @Override
    public void invoke(Map<Integer, String> integerStringMap, AnalysisContext ctx) {
        data.add(integerStringMap);
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext ctx) {
        String fileName = ctx.readWorkbookHolder().getFile().getName();
        String sheetName = ctx.readSheetHolder().getReadSheet().getSheetName();
        log.info("当前文件{}的sheet{}数据读取完成",fileName,sheetName);
    }

    public List<Map<Integer,String>> getData() {
        return data;
    }

    public void removeData(){
        data.clear();
    }
}
