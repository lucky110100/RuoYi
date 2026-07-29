-- ----------------------------------------------------------
-- LLM 模型配置表 sys_ai_model
-- 替代 application.yml 中 ai.llm.* 的静态配置
-- 由 Models 菜单管理（增删改查 + 连通性测试）
-- ----------------------------------------------------------

drop table if exists sys_ai_model;
create table sys_ai_model (
  model_id         bigint(20)      not null auto_increment    comment '模型主键',
  model_name       varchar(100)    not null                   comment '模型名称（传给 LLM 的 model 标识，如 GLM-5.1）',
  model_type       varchar(50)     default 'chat'             comment '模型类型（chat 对话 / reasoning 推理 / embedding 嵌入）',
  model_version    varchar(50)     default ''                 comment '模型版本（展示用，如 5.1）',
  model_provider   varchar(50)     not null                   comment '后端提供方（AgentCore client_provider，如 OpenAI / DashScope）',
  api_base         varchar(255)    not null                   comment 'API 基地址（如 https://api.openai.com/v1）',
  api_key          varchar(255)    not null                   comment 'API Key',
  ssl_verify       char(1)         default '1'                comment '是否校验 SSL（0否 1是）',
  status           char(1)         default '0'                comment '状态（0启用 1停用）',
  is_default       char(1)         default 'N'                comment '是否默认模型（Y是 N否，仅可有一条为 Y）',
  sort_order       int(4)          default 0                  comment '显示顺序',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default ''                 comment '备注',
  primary key (model_id)
) engine=innodb auto_increment=100 comment = 'LLM 模型配置表';

-- ----------------------------------------------------------
-- 初始化数据：示例占位符
-- 部署时请替换为真实的 api_base 和 api_key
-- ----------------------------------------------------------
insert into sys_ai_model (model_name, model_type, model_version, model_provider,
                          api_base, api_key, ssl_verify, status, is_default, sort_order,
                          create_by, create_time, remark)
values ('GLM-5.1', 'chat', '5.1', 'OpenAI',
        'http://example.com/v1', 'sk-your-api-key', '0',
        '0', 'Y', 0,
        'admin', sysdate(), '默认模型（部署时请替换 api_base 和 api_key）');
