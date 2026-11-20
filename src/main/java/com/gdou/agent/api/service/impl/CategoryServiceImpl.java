package com.gdou.agent.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.agent.api.mapper.CategoryMapper;
import com.gdou.agent.api.service.CategoryService;
import com.gdou.agent.meta.entity.Category;
import org.springframework.stereotype.Service;

/**
 * @author huanghaiwei
 * @description 针对表【category(大分类：隔离单元)】的数据库操作Service实现
 */
@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category>
        implements CategoryService {

}
