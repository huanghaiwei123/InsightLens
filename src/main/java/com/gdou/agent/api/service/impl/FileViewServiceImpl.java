package com.gdou.agent.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.agent.api.mapper.FileViewMapper;
import com.gdou.agent.api.service.FileViewService;
import com.gdou.agent.meta.entity.FileView;
import org.springframework.stereotype.Service;

/**
 * @author huanghaiwei
 * @description 针对表【file_view(文件注册表：一行=一个sheet)】的数据库操作Service实现
 */
@Service
public class FileViewServiceImpl extends ServiceImpl<FileViewMapper, FileView>
        implements FileViewService {

}
