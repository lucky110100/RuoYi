package com.ruoyi.ai.service;

import java.util.List;
import com.ruoyi.ai.domain.SysAiModel;

/**
 * LLM 模型配置 服务层
 *
 * @author ruoyi
 */
public interface ISysAiModelService
{
    /**
     * 通过 ID 查询模型
     *
     * @param modelId 模型主键
     * @return 模型配置
     */
    public SysAiModel selectModelById(Long modelId);

    /**
     * 查询默认模型（is_default=Y 且 status=0）
     *
     * @return 默认模型配置
     */
    public SysAiModel selectDefaultModel();

    /**
     * 查询启用的模型列表（status=0）
     *
     * @return 启用模型集合
     */
    public List<SysAiModel> selectEnabledModels();

    /**
     * 查询模型列表
     *
     * @param model 查询条件
     * @return 模型集合
     */
    public List<SysAiModel> selectModelList(SysAiModel model);

    /**
     * 新增模型
     *
     * @param model 模型配置
     * @return 结果
     */
    public int insertModel(SysAiModel model);

    /**
     * 修改模型
     *
     * @param model 模型配置
     * @return 结果
     */
    public int updateModel(SysAiModel model);

    /**
     * 批量删除模型
     *
     * @param ids 需要删除的数据ID
     */
    public void deleteModelByIds(String ids);

    /**
     * 校验模型名称是否唯一
     *
     * @param model 模型信息
     * @return 结果（true 唯一，false 不唯一）
     */
    public boolean checkModelNameUnique(SysAiModel model);
}
