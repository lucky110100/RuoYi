-- ----------------------------------------------------------
-- RuoYi AI 辅助模块菜单
-- 模块: 系统工具 -> AI辅助
-- 入口 URL: /ai/chat
-- 权限标识: ai:chat:view / ai:chat:send
-- ----------------------------------------------------------

-- 父菜单: 系统工具 (menu_id=3) 下新增 AI辅助 菜单 (menu_id=117)
insert into sys_menu values('117', 'AI辅助', '3', '4', '/ai/chat', '', 'C', '0', '1', 'ai:chat:view', 'fa fa-rocket', 'admin', sysdate(), '', null, 'AI辅助菜单');

-- 按钮权限
insert into sys_menu values('1171', '对话发送', '117', '1',  '#', '', 'F', '0', '1', 'ai:chat:send', '#', 'admin', sysdate(), '', null, '');
