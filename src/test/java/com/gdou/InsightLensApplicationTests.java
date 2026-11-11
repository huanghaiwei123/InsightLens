package com.gdou;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 集成测试：会加载完整 Spring 上下文，依赖本地 application-local.yaml 与 DuckDB。
 * 该配置不入库，新克隆仓库跑 mvn test 必然失败，故默认不纳入单元测试；
 * 需要验证上下文能起来时手动去掉 @Disabled。
 */
@Disabled("集成测试：需本地私有配置与完整 Spring 上下文，手动启用")
@SpringBootTest
class InsightLensApplicationTests {

    @Test
    void contextLoads() {
    }

}
