-- 生成结果会把多个源码文件拼成一条 Markdown 历史消息，TEXT 的 65535 字节上限不够。
alter table chat_history
    modify column message longtext not null comment '消息';
