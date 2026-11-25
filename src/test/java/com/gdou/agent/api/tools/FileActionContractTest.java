package com.gdou.agent.api.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传归类提示词（UploadMessage.txt）与后端 FileAction 契约的回归测试。
 *
 * 新契约要点：
 * - transfer 只有 fileName/from/to 三个字段，uuid 已废弃（编号融进文件名后缀）；
 * - 分类路径用正斜杠 / 分层，后端靠 split("/") 取一级分类；
 * - fileName 是纯文件名（含下划线编号后缀），不含路径分隔符。
 *
 * 这些测试直接喂模型按提示词会输出的 JSON 形态，确认 Jackson 能解析、
 * 且后端依赖的字段切分能拿到预期值——契约任一侧改动都会在这里红灯。
 */
class FileActionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("新契约三字段 JSON（无 uuid）可解析，uuid 为 null 且不影响读取")
    void parseWithoutUuid() throws Exception {
        String answer = """
                {"transfers":[{"fileName":"订单_a1b2c3d4.xlsx","from":"temp123","to":"电商运营"}],"create":null,"delete":null}
                """;

        FileAction action = objectMapper.readValue(answer, FileAction.class);

        assertThat(action.transfers()).hasSize(1);
        FileTransfer t = action.transfers().get(0);
        assertThat(t.fileName()).isEqualTo("订单_a1b2c3d4.xlsx");
        assertThat(t.from()).isEqualTo("temp123");
        assertThat(t.to()).isEqualTo("电商运营");
        assertThat(action.create()).isNull();
        assertThat(action.delete()).isNull();
    }

    @Test
    @DisplayName("风险揭示：若模型仍输出 uuid 字段，默认 ObjectMapper 会拒绝解析")
    void extraUuidFieldIsRejectedByDefaultMapper() {
        String answer = """
                {"transfers":[{"fileName":"订单.xlsx","from":"temp123","to":"电商运营","uuid":"ab12cd34"}],"create":null,"delete":null}
                """;

        // 当前 FileTransfer 已移除 uuid 字段，而项目未配置 FAIL_ON_UNKNOWN_PROPERTIES=false。
        // 这测出真实的脆弱点：提示词必须明确禁止模型输出 uuid，否则整批上传会解析失败。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> objectMapper.readValue(answer, FileAction.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("uuid");
    }

    @Test
    @DisplayName("多级分类路径用 / 分隔，split 能取到一级分类名")
    void slashPathYieldsTopCategory() throws Exception {
        String answer = """
                {"transfers":[{"fileName":"订单_a1b2c3d4.xlsx","from":"temp123","to":"电商运营/历史订单/2026"}],"create":["电商运营/历史订单/2026"],"delete":null}
                """;

        FileAction action = objectMapper.readValue(answer, FileAction.class);
        String to = action.transfers().get(0).to();

        // 复刻 afterLLmCall 里取一级分类的写法
        assertThat(to.split("/")[0]).isEqualTo("电商运营");
        assertThat(action.create()).containsExactly("电商运营/历史订单/2026");
    }

    @Test
    @DisplayName("模型误用反斜杠时 split(/) 取不到一级分类——证明提示词锁死 / 的必要性")
    void backslashPathBreaksTopCategoryExtraction() throws Exception {
        String answer = """
                {"transfers":[{"fileName":"订单_a1b2c3d4.xlsx","from":"temp123","to":"电商运营\\\\历史订单"}],"create":null,"delete":null}
                """;

        FileAction action = objectMapper.readValue(answer, FileAction.class);
        String to = action.transfers().get(0).to();

        // 反斜杠路径下 split("/") 返回的整串就是一级分类，无法再分层——提示词必须禁止反斜杠
        assertThat(to.split("/")[0]).isEqualTo("电商运营\\历史订单");
        assertThat(to.split("/")).hasSize(1);
    }

    @Test
    @DisplayName("多文件各自带不同编号后缀，逐项解析且不串改")
    void parseMultipleFiles() throws Exception {
        String answer = """
                {"transfers":[
                  {"fileName":"语文_期中_3c1f0a21.xlsx","from":"temp555","to":"教学/语文"},
                  {"fileName":"数学_期中_3c1f0a22.xlsx","from":"temp555","to":"教学"}
                ],"create":null,"delete":null}
                """;

        FileAction action = objectMapper.readValue(answer, FileAction.class);

        assertThat(action.transfers()).extracting(FileTransfer::fileName)
                .containsExactly("语文_期中_3c1f0a21.xlsx", "数学_期中_3c1f0a22.xlsx");
        assertThat(action.transfers()).extracting(FileTransfer::to)
                .containsExactly("教学/语文", "教学");
    }

    @Test
    @DisplayName("transfers 为空数组时解析为空列表，后端可直接遍历")
    void parseEmptyTransfers() throws Exception {
        String answer = "{\"transfers\":[],\"create\":null,\"delete\":null}";

        FileAction action = objectMapper.readValue(answer, FileAction.class);

        assertThat(action.transfers()).isEqualTo(List.of());
    }
}
