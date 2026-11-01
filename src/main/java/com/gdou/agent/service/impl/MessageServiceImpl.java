package com.gdou.agent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gdou.meta.entity.Message;
import com.gdou.agent.service.MessageService;
import com.gdou.agent.mapper.MessageMapper;
import org.springframework.stereotype.Service;

/**
* @author huanghaiwei
* @description 针对表【message(消息表：会话中的每一轮提问 / 回答 / 工具结果)】的数据库操作Service实现
* @createDate 2026-10-30 17:32:59
*/
@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message>
    implements MessageService{

}




