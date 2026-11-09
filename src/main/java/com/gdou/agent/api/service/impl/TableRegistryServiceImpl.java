package com.gdou.agent.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.agent.api.mapper.TableRegistryMapper;
import com.gdou.agent.api.service.TableRegistryService;
import com.gdou.agent.meta.entity.TableRegistry;
import org.springframework.stereotype.Service;

/**
 * @author huanghaiwei
 * @description 针对表【table_registry(物理表注册表：每张分析数据表一行，存结构指纹与语义标签)】的数据库操作Service实现
 * @createDate 2026-11-03 17:33:00
 */
@Service
public class TableRegistryServiceImpl extends ServiceImpl<TableRegistryMapper, TableRegistry>
        implements TableRegistryService {

}
