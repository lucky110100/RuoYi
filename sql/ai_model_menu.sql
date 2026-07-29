-- ----------------------------------------------------------
-- LLM 管理功能菜单
-- 1) 新增一级菜单"AI辅助"
-- 2) 原 117 由"系统工具->AI辅助"改为"AI辅助->AI问答"
-- 3) 新增"AI辅助->Models"及其按钮权限
-- 兼容"已导入旧 ai_menu.sql"与"全新库"两种环境（用 ON DUPLICATE KEY UPDATE）
-- ----------------------------------------------------------

-- 一级目录：AI辅助
insert into sys_menu values (
  '1180', 'AI辅助', '0', '5', '#', '', 'M', '0', '1', '',
  'fa fa-rocket', 'admin', sysdate(), '', null, 'AI辅助目录'
)
on duplicate key update
  menu_name='AI辅助', parent_id='0', order_num=5,
  url='#', target='', menu_type='M', visible='0', is_refresh='1',
  perms='', icon='fa fa-rocket', update_by='admin', update_time=sysdate(),
  remark='AI辅助目录';

-- 重命名并改父：原 117 "AI辅助" -> "AI问答" (parent=1180)
insert into sys_menu values (
  '117', 'AI问答', '1180', '1', '/ai/chat', '', 'C', '0', '1',
  'ai:chat:view', 'fa fa-comments', 'admin', sysdate(), '', null, 'AI问答菜单'
)
on duplicate key update
  menu_name='AI问答', parent_id='1180', order_num=1,
  url='/ai/chat', target='', menu_type='C', visible='0', is_refresh='1',
  perms='ai:chat:view', icon='fa fa-comments',
  update_by='admin', update_time=sysdate(), remark='AI问答菜单';

-- 新增二级菜单：Models
insert into sys_menu values (
  '1182', 'Models', '1180', '2', '/ai/models', '', 'C', '0', '1',
  'ai:model:view', 'fa fa-cubes', 'admin', sysdate(), '', null, 'LLM 模型管理菜单'
)
on duplicate key update
  menu_name='Models', parent_id='1180', order_num=2,
  url='/ai/models', target='', menu_type='C', visible='0', is_refresh='1',
  perms='ai:model:view', icon='fa fa-cubes',
  update_by='admin', update_time=sysdate(), remark='LLM 模型管理菜单';

-- Models 按钮权限
insert into sys_menu values ('1183', '列表查询', '1182', '1', '#', '', 'F', '0', '1', 'ai:model:list',   '#', 'admin', sysdate(), '', null, '')
on duplicate key update menu_name='列表查询', parent_id='1182', perms='ai:model:list';
insert into sys_menu values ('1184', '新增',     '1182', '2', '#', '', 'F', '0', '1', 'ai:model:add',    '#', 'admin', sysdate(), '', null, '')
on duplicate key update menu_name='新增', parent_id='1182', perms='ai:model:add';
insert into sys_menu values ('1185', '修改',     '1182', '3', '#', '', 'F', '0', '1', 'ai:model:edit',   '#', 'admin', sysdate(), '', null, '')
on duplicate key update menu_name='修改', parent_id='1182', perms='ai:model:edit';
insert into sys_menu values ('1186', '删除',     '1182', '4', '#', '', 'F', '0', '1', 'ai:model:remove', '#', 'admin', sysdate(), '', null, '')
on duplicate key update menu_name='删除', parent_id='1182', perms='ai:model:remove';
insert into sys_menu values ('1187', '测试连通', '1182', '5', '#', '', 'F', '0', '1', 'ai:model:test',   '#', 'admin', sysdate(), '', null, '')
on duplicate key update menu_name='测试连通', parent_id='1182', perms='ai:model:test';
