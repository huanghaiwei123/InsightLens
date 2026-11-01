package com.gdou.api.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.meta.entity.Session;
import com.gdou.api.service.SessionService;
import com.gdou.api.mapper.SessionMapper;
import org.springframework.stereotype.Service;

/**
* @author huanghaiwei
* @description 针对表【session(用户会话表：一个会话 = 一段多轮对话)】的数据库操作Service实现
* @createDate 2026-10-30 17:32:59
*/
@Service
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session>
    implements SessionService{

}




