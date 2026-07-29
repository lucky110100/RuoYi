package com.ruoyi.ai.mapper;

import java.util.List;
import com.ruoyi.ai.domain.SysAiModel;

/**
 * LLM 模型配置 数据层
 *
 * @author ruoyi
 */
public interface SysAiModelMapper
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
     * 查询启用的模型列表（status=0），按 sort_order 排序
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
     * 校验模型名称是否唯一
     *
     * @param modelName 模型名称
     * @return 模型配置
     */
    public SysAiModel checkModelNameUnique(String modelName);

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
     * 把所有模型的 is_default 置为 N（用于设置新默认前的互斥清理）
     *
     * @return 结果
     */
    public int clearDefaultFlag();

    /**
     * 通过 ID 删除模型
     *
     * @param modelId 模型主键
     * @return 结果
     */
    public int deleteModelById(Long modelId);

    /**
     * 批量删除模型
     *
     * @param modelIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteModelByIds(String[] modelIds);
}
