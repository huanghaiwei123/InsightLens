package com.gdou.agent.api.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdou.agent.api.service.CategoryService;
import com.gdou.agent.api.service.FileViewService;
import com.gdou.agent.api.tools.FileTool;
import com.gdou.agent.api.tools.FolderTool;
import com.gdou.agent.infra.LlmClient;
import com.gdou.agent.meta.entity.Category;
import com.gdou.agent.meta.entity.FileView;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UploadServiceImpl.afterLLmCall 的契约回归测试。
 *
 * 聚焦本轮改动引入的三条规则：
 * 1. 分类路径用 / 分层，一级分类取 split("/")[0]（不再依赖 uuid）；
 * 2. 数据库回写按 origName == 带编号后缀的文件名，filePath 用 / 拼到文件；
 * 3. create/delete 互斥、无操作时为 null，避免空数组误触发文件删除。
 *
 * afterLLmCall 是私有方法，用 ReflectionTestUtils.invokeMethod 触发，
 * 依赖全部 mock，只验证"传给依赖的参数"这一层，不碰真实文件系统和数据库。
 */
class UploadServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 脱离 Spring 容器时，MyBatis-Plus 的 LambdaQueryWrapper.eq(Entity::getXxx, ...)
     * 依赖实体 TableInfo 缓存来把方法引用解析成列名。纯单测需先手动注册，
     * 否则抛 "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Category.class);
        TableInfoHelper.initTableInfo(assistant, FileView.class);
    }

    private static UploadServiceImpl serviceWith(FolderTool folderTool, FileTool fileTool,
                                                 CategoryService categoryService,
                                                 FileViewService fileViewService) {
        UploadServiceImpl service = new UploadServiceImpl();
        ReflectionTestUtils.setField(service, "objectMapper", MAPPER);
        ReflectionTestUtils.setField(service, "folderTool", folderTool);
        ReflectionTestUtils.setField(service, "fileTool", fileTool);
        ReflectionTestUtils.setField(service, "categoryService", categoryService);
        ReflectionTestUtils.setField(service, "fileViewService", fileViewService);
        return service;
    }

    private static void call(UploadServiceImpl service, String answer) {
        ReflectionTestUtils.invokeMethod(service, "afterLLmCall", answer);
    }

    @Test
    @DisplayName("相对 agent.workspace 必须转成绝对路径，否则 transferTo 会写进 Tomcat 工作目录")
    void initResolvesWorkspaceToAbsolutePath() {
        UploadServiceImpl service = serviceWith(mock(FolderTool.class), mock(FileTool.class),
                mock(CategoryService.class), mock(FileViewService.class));
        Path relative = Path.of("target", "upload-init-" + UUID.randomUUID());
        ReflectionTestUtils.setField(service, "workSpace", relative.toString());

        ReflectionTestUtils.invokeMethod(service, "init");

        Path root = (Path) ReflectionTestUtils.getField(service, "root");
        Path temp = (Path) ReflectionTestUtils.getField(service, "temp");
        assertThat(root).isNotNull();
        assertThat(root.isAbsolute()).isTrue();
        assertThat(temp).isNotNull();
        assertThat(temp.isAbsolute()).isTrue();
        assertThat(temp.getParent()).isEqualTo(root);
        assertThat(Files.isDirectory(temp)).isTrue();
    }

    /** 造一份 {sheet名={列名=值}} 样本字符串，与监听器存进 sample_data 的格式一致 */
    private static String sample(String sheet, String... kv) {
        StringBuilder sb = new StringBuilder("{" + sheet + "={");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(kv[i]).append('=').append(kv[i + 1]);
        }
        return sb.append("}}").toString();
    }

    @Test
    @DisplayName("提示词第 4 段：按分类分组列已有文件并附首行样本，去重、排除 temp 与伪路径")
    void existingFilesSegment() {
        FileViewService fileViewService = mock(FileViewService.class);
        UploadServiceImpl service = serviceWith(mock(FolderTool.class), mock(FileTool.class),
                mock(CategoryService.class), fileViewService);
        Path workspace = Path.of("").toAbsolutePath().resolve("workspace");
        Path temp = workspace.resolve("temp123");
        ReflectionTestUtils.setField(service, "workSpace", workspace.toString());
        ReflectionTestUtils.setField(service, "root", workspace);
        ReflectionTestUtils.setField(service, "temp", temp);

        String orderSample = sample("订单明细", "order_id", "1001", "amount", "299.00");
        when(fileViewService.list()).thenReturn(List.of(
                FileView.builder().origName("订单_a1b2c3d4.xlsx")
                        .filePath(workspace.resolve("电商运营/订单_a1b2c3d4.xlsx").toString())
                        .sampleData(orderSample).build(),
                // 同一文件的多个 sheet 共享 origName/filePath，清单里应只出现一次
                FileView.builder().origName("订单_a1b2c3d4.xlsx")
                        .filePath(workspace.resolve("电商运营/订单_a1b2c3d4.xlsx").toString())
                        .sampleData(orderSample).build(),
                FileView.builder().origName("退款_9a4e2b11.xlsx")
                        .filePath(workspace.resolve("电商运营/历史订单/退款_9a4e2b11.xlsx").toString())
                        .sampleData(sample("退款明细", "order_id", "2002", "reason", "质量")).build(),
                // 老数据没有样本字段，退化为 {} 而不是崩
                FileView.builder().origName("无样本_5e6f7a8b.xlsx")
                        .filePath(workspace.resolve("电商运营/无样本_5e6f7a8b.xlsx").toString()).build(),
                // 本批刚落 temp、尚未归档的新文件：不算已有文件
                FileView.builder().origName("本批_1a2b3c4d.xlsx")
                        .filePath(temp.resolve("本批_1a2b3c4d.xlsx").toString())
                        .sampleData(orderSample).build(),
                // eval:// 伪路径不是真实文件
                FileView.builder().origName("伪造.xlsx").filePath("eval://orders")
                        .sampleData(orderSample).build()
        ));

        String segment = ReflectionTestUtils.invokeMethod(service, "existingFilesSegment");

        assertThat(segment).isEqualTo("用户当前环境各分类下已有的文件如下：{"
                + "电商运营={订单_a1b2c3d4.xlsx={订单明细={order_id=1001, amount=299.00}}, "
                + "无样本_5e6f7a8b.xlsx={}}, "
                + "电商运营/历史订单={退款_9a4e2b11.xlsx={退款明细={order_id=2002, reason=质量}}}}");
    }

    @Test
    @DisplayName("upload 拼装 UserMessage 时带上第 4 段，且模型输出的已有文件 transfer 能落地")
    void uploadIncludesExistingFilesSegment() {
        FolderTool folderTool = mock(FolderTool.class);
        FileTool fileTool = mock(FileTool.class);
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        LlmClient llmClient = mock(LlmClient.class);
        UploadServiceImpl service = serviceWith(folderTool, fileTool, categoryService, fileViewService);
        ReflectionTestUtils.setField(service, "llmClient", llmClient);
        Path workspace = Path.of("").toAbsolutePath().resolve("workspace");
        Path temp = workspace.resolve("temp456");
        ReflectionTestUtils.setField(service, "workSpace", workspace.toString());
        ReflectionTestUtils.setField(service, "root", workspace);
        ReflectionTestUtils.setField(service, "temp", temp);

        when(folderTool.listFolder()).thenReturn("用户当前环境的各级文件分类如下：{1=[电商运营], 2=[]}");
        when(fileViewService.list()).thenReturn(List.of(
                FileView.builder().origName("订单_9a4e2b11.xlsx")
                        .filePath(workspace.resolve("电商运营/订单_9a4e2b11.xlsx").toString())
                        .sampleData(sample("订单明细", "order_id", "9001", "category", "数码")).build()
        ));
        when(llmClient.call(any())).thenReturn("""
                {"transfers":[{"fileName":"订单_9a4e2b11.xlsx","from":"电商运营","to":"退款分析"}],"create":["退款分析"],"delete":null}
                """);
        when(categoryService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.upload(List.of());

        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(llmClient).call(messages.capture());
        assertThat(messages.getValue()).anySatisfy(m -> {
            assertThat(m).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage) m).getText())
                    .contains("用户当前环境各分类下已有的文件如下：{电商运营={订单_9a4e2b11.xlsx="
                            + "{订单明细={order_id=9001, category=数码}}}}");
        });
        // 已有文件从"电商运营"搬到新建统计分类，filePath 同步回写
        verify(fileTool).transfer("电商运营/订单_9a4e2b11.xlsx", "退款分析");
    }

    @Test
    @DisplayName("归到多级分类：按 / 取一级分类查库，文件移到完整 to，filePath 用 / 拼")
    void multiLevelCategory() {
        FolderTool folderTool = mock(FolderTool.class);
        FileTool fileTool = mock(FileTool.class);
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        when(categoryService.getOne(any(LambdaQueryWrapper.class)))
                .thenReturn(Category.builder().id(7).name("电商运营").build());

        String answer = """
                {"transfers":[{"fileName":"订单_a1b2c3d4.xlsx","from":"temp123","to":"电商运营/历史订单"}],"create":null,"delete":null}
                """;
        call(serviceWith(folderTool, fileTool, categoryService, fileViewService), answer);

        // 源文件 = temp目录 + "/" + 带后缀文件名；目标 = 完整 to
        verify(fileTool).transfer("temp123/订单_a1b2c3d4.xlsx", "电商运营/历史订单");

        // 按 to 的第一级"电商运营"查分类，而不是整条路径
        ArgumentCaptor<LambdaQueryWrapper<Category>> qw = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(categoryService).getOne(qw.capture());
        assertThat(qw.getValue().getSqlSegment()).contains("name");
        assertThat(qw.getValue().getParamNameValuePairs().values()).contains("电商运营");

        // 按 origName == 文件名回写分类 id 和 filePath，filePath 用 / 拼
        // 注意 getSqlSegment() 只含 where 条件；set 的字段要看 getSqlSet()
        ArgumentCaptor<LambdaUpdateWrapper<FileView>> uw = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fileViewService).update(uw.capture());
        assertThat(uw.getValue().getSqlSet()).contains("category_id", "file_path");
        assertThat(uw.getValue().getSqlSegment()).contains("orig_name");
        // where 值（文件名）与 filePath 值相同，MyBatis-Plus 会共用占位符，values 去重后是这两项
        assertThat(uw.getValue().getParamNameValuePairs().values())
                .contains("电商运营/历史订单/订单_a1b2c3d4.xlsx", 7);
    }

    @Test
    @DisplayName("新建分类：create 路径逐项建目录，transfer 的 to 与 create 项一致")
    void createCategoryWhenMissing() {
        FolderTool folderTool = mock(FolderTool.class);
        FileTool fileTool = mock(FileTool.class);
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        // 分类不存在 → 走 new + save 分支；save 不赋 id，id 为 null 不影响本测断言
        when(categoryService.getOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        String answer = """
                {"transfers":[{"fileName":"对账单_7b2d9e3a.xlsx","from":"temp987","to":"财务"}],"create":["财务","教学"],"delete":null}
                """;
        call(serviceWith(folderTool, fileTool, categoryService, fileViewService), answer);

        verify(folderTool).createFolder("财务");
        verify(folderTool).createFolder("教学");
        verify(fileTool).transfer("temp987/对账单_7b2d9e3a.xlsx", "财务");
        verify(categoryService).save(any(Category.class));
        verify(folderTool, never()).deleteFolder(any());
    }

    @Test
    @DisplayName("create 与 delete 同时给出时只执行 create（互斥，create 优先）")
    void createWinsWhenBothPresent() {
        FolderTool folderTool = mock(FolderTool.class);
        FileTool fileTool = mock(FileTool.class);
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        when(categoryService.getOne(any(LambdaQueryWrapper.class)))
                .thenReturn(Category.builder().id(1).name("电商运营").build());

        String answer = """
                {"transfers":[{"fileName":"商品_9a4e2b11.xlsx","from":"temp444","to":"电商运营"}],"create":["教学"],"delete":["临时测试"]}
                """;
        call(serviceWith(folderTool, fileTool, categoryService, fileViewService), answer);

        verify(folderTool).createFolder("教学");
        verify(folderTool, never()).deleteFolder(any());
    }

    @Test
    @DisplayName("仅删除：无新建时 delete 路径逐个删除")
    void deleteOnly() {
        FolderTool folderTool = mock(FolderTool.class);
        FileTool fileTool = mock(FileTool.class);
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        when(categoryService.getOne(any(LambdaQueryWrapper.class)))
                .thenReturn(Category.builder().id(1).name("电商运营").build());

        String answer = """
                {"transfers":[{"fileName":"商品_9a4e2b11.xlsx","from":"temp444","to":"电商运营"}],"create":null,"delete":["临时测试"]}
                """;
        call(serviceWith(folderTool, fileTool, categoryService, fileViewService), answer);

        verify(folderTool).deleteFolder("临时测试");
        verify(folderTool, never()).createFolder(any());
    }

    @Test
    @DisplayName("拆分重组：新文件与已有文件主题不同，去两个不同的新子分类，各自建目录")
    void splitIntoDifferentSubCategories() {
        FolderTool folderTool = mock(FolderTool.class);
        FileTool fileTool = mock(FileTool.class);
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        when(categoryService.getOne(any(LambdaQueryWrapper.class)))
                .thenReturn(Category.builder().id(5).name("电商订单").build());

        // 复刻示例7：新利润文件去 电商订单/利润，已有退款文件从 电商订单 搬到 电商订单/退款
        String answer = """
                {"transfers":[
                  {"fileName":"利润表_b2c3d4e5.xlsx","from":"temp222","to":"电商订单/利润"},
                  {"fileName":"退款明细_9a4e2b11.xlsx","from":"电商订单","to":"电商订单/退款"}
                ],"create":["电商订单/利润","电商订单/退款"],"delete":null}
                """;
        call(serviceWith(folderTool, fileTool, categoryService, fileViewService), answer);

        // 两条独立的移动：新文件从 temp、已有文件从原分类，去不同目标
        verify(fileTool).transfer("temp222/利润表_b2c3d4e5.xlsx", "电商订单/利润");
        verify(fileTool).transfer("电商订单/退款明细_9a4e2b11.xlsx", "电商订单/退款");
        // 两个新子分类都建目录
        verify(folderTool).createFolder("电商订单/利润");
        verify(folderTool).createFolder("电商订单/退款");
        // 两个文件都回写 filePath，且一级分类都取 电商订单
        ArgumentCaptor<LambdaUpdateWrapper<FileView>> uw = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fileViewService, org.mockito.Mockito.times(2)).update(uw.capture());
        assertThat(uw.getAllValues()).extracting(w -> java.util.List.copyOf(w.getParamNameValuePairs().values()))
                .containsExactlyInAnyOrder(
                        java.util.List.of("电商订单/利润/利润表_b2c3d4e5.xlsx", 5),
                        java.util.List.of("电商订单/退款/退款明细_9a4e2b11.xlsx", 5));
    }

    @Test
    @DisplayName("多文件逐项处理：不同后缀文件名各自回写，不串改")
    void multipleFilesIndependent() {
        FolderTool folderTool = mock(FolderTool.class);
        FileTool fileTool = mock(FileTool.class);
        CategoryService categoryService = mock(CategoryService.class);
        FileViewService fileViewService = mock(FileViewService.class);
        when(categoryService.getOne(any(LambdaQueryWrapper.class)))
                .thenReturn(Category.builder().id(2).name("教学").build());

        String answer = """
                {"transfers":[
                  {"fileName":"语文_3c1f0a21.xlsx","from":"temp555","to":"教学/语文"},
                  {"fileName":"数学_3c1f0a22.xlsx","from":"temp555","to":"教学"}
                ],"create":null,"delete":null}
                """;
        call(serviceWith(folderTool, fileTool, categoryService, fileViewService), answer);

        verify(fileTool).transfer("temp555/语文_3c1f0a21.xlsx", "教学/语文");
        verify(fileTool).transfer("temp555/数学_3c1f0a22.xlsx", "教学");

        ArgumentCaptor<LambdaUpdateWrapper<FileView>> uw = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(fileViewService, org.mockito.Mockito.times(2)).update(uw.capture());
        // 每项的 filePath = to + "/" + 带后缀文件名，据此验证两文件各回写各的、没串改
        assertThat(uw.getAllValues()).extracting(w -> java.util.List.copyOf(w.getParamNameValuePairs().values()))
                .containsExactlyInAnyOrder(
                        java.util.List.of("教学/语文/语文_3c1f0a21.xlsx", 2),
                        java.util.List.of("教学/数学_3c1f0a22.xlsx", 2));
    }
}
