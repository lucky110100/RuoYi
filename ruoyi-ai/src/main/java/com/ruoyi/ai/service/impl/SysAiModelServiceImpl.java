package com.ruoyi.ai.service.impl;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.ai.domain.SysAiModel;
import com.ruoyi.ai.event.AiModelChangedEvent;
import com.ruoyi.ai.mapper.SysAiModelMapper;
import com.ruoyi.ai.service.ISysAiModelService;

/**
 * LLM 模型配置 服务层实现
 *
 * @author ruoyi
 */
@Service
public class SysAiModelServiceImpl implements ISysAiModelService
{
    @Autowired
    private SysAiModelMapper modelMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 通过 ID 查询模型
     */
    @Override
    public SysAiModel selectModelById(Long modelId)
    {
        return modelMapper.selectModelById(modelId);
    }

    /**
     * 查询默认模型
     */
    @Override
    public SysAiModel selectDefaultModel()
    {
        return modelMapper.selectDefaultModel();
    }

    /**
     * 查询启用的模型列表
     */
    @Override
    public List<SysAiModel> selectEnabledModels()
    {
        return modelMapper.selectEnabledModels();
    }

    /**
     * 查询模型列表
     */
    @Override
    @DataScope(deptAlias = "d", userAlias = "u")
    public List<SysAiModel> selectModelList(SysAiModel model)
    {
        return modelMapper.selectModelList(model);
    }

    /**
     * 新增模型
     * 设置默认模型互斥：若新模型 is_default=Y，先把其他模型的 is_default 置 N
     */
    @Override
    public int insertModel(SysAiModel model)
    {
        if ("Y".equals(model.getIsDefault()))
        {
            modelMapper.clearDefaultFlag();
        }
        int rows = modelMapper.insertModel(model);
        publishChangeEvent(model.getModelId());
        return rows;
    }

    /**
     * 修改模型
     * 处理默认模型互斥：若改为默认，先把其他模型的 is_default 置 N
     */
    @Override
    public int updateModel(SysAiModel model)
    {
        SysAiModel existing = modelMapper.selectModelById(model.getModelId());
        if (existing == null)
        {
            throw new ServiceException("模型不存在");
        }
        if ("Y".equals(model.getIsDefault()))
        {
            modelMapper.clearDefaultFlag();
        }
        int rows = modelMapper.updateModel(model);
        publishChangeEvent(model.getModelId());
        return rows;
    }

    /**
     * 批量删除模型
     */
    @Override
    public void deleteModelByIds(String ids)
    {
        String[] idArr = Convert.toStrArray(ids);
        Arrays.stream(idArr).forEach(idStr -> {
            Long id = Long.valueOf(idStr);
            modelMapper.deleteModelById(id);
            publishChangeEvent(id);
        });
    }

    /**
     * 校验模型名称是否唯一
     *
     * @return true 唯一 / false 不唯一
     */
    @Override
    public boolean checkModelNameUnique(SysAiModel model)
    {
        Long modelId = model.getModelId() == null ? -1L : model.getModelId();
        SysAiModel info = modelMapper.checkModelNameUnique(model.getModelName());
        if (StringUtils.isNotNull(info) && !info.getModelId().equals(modelId))
        {
            return false;
        }
        return true;
    }

    /**
     * 发布模型变更事件，触发 DeepAgent 缓存失效（由 Registry 监听）。
     */
    private void publishChangeEvent(Long modelId)
    {
        if (modelId == null)
        {
            return;
        }
        eventPublisher.publishEvent(new AiModelChangedEvent(this, modelId));
    }
}
