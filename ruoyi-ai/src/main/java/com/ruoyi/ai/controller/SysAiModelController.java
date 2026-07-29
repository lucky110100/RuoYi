package com.ruoyi.ai.controller;

import java.util.List;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.ai.domain.SysAiModel;
import com.ruoyi.ai.service.AiModelConnectionTester;
import com.ruoyi.ai.service.ISysAiModelService;

/**
 * LLM 模型管理 信息操作处理
 *
 * @author ruoyi
 */
@Controller
@RequestMapping("/ai/models")
public class SysAiModelController extends BaseController
{
    private String prefix = "ai/model";

    @Autowired
    private ISysAiModelService modelService;

    @Autowired
    private AiModelConnectionTester connectionTester;

    /**
     * 模型管理页面
     */
    @RequiresPermissions("ai:model:view")
    @GetMapping
    public String model()
    {
        return prefix + "/model";
    }

    /**
     * 查询模型列表
     */
    @RequiresPermissions("ai:model:list")
    @PostMapping("/list")
    @ResponseBody
    public TableDataInfo list(SysAiModel model)
    {
        startPage();
        List<SysAiModel> list = modelService.selectModelList(model);
        return getDataTable(list);
    }

    /**
     * 新增模型页面
     */
    @RequiresPermissions("ai:model:add")
    @GetMapping("/add")
    public String add()
    {
        return prefix + "/add";
    }

    /**
     * 新增保存模型
     */
    @RequiresPermissions("ai:model:add")
    @Log(title = "LLM模型管理", businessType = BusinessType.INSERT)
    @PostMapping("/add")
    @ResponseBody
    public AjaxResult addSave(@Validated SysAiModel model)
    {
        if (!modelService.checkModelNameUnique(model))
        {
            return error("新增模型'" + model.getModelName() + "'失败，模型名称已存在");
        }
        model.setCreateBy(getLoginName());
        return toAjax(modelService.insertModel(model));
    }

    /**
     * 修改模型页面
     */
    @RequiresPermissions("ai:model:edit")
    @GetMapping("/edit/{modelId}")
    public String edit(@PathVariable("modelId") Long modelId, ModelMap mmap)
    {
        mmap.put("model", modelService.selectModelById(modelId));
        return prefix + "/edit";
    }

    /**
     * 修改保存模型
     */
    @RequiresPermissions("ai:model:edit")
    @Log(title = "LLM模型管理", businessType = BusinessType.UPDATE)
    @PostMapping("/edit")
    @ResponseBody
    public AjaxResult editSave(@Validated SysAiModel model)
    {
        if (!modelService.checkModelNameUnique(model))
        {
            return error("修改模型'" + model.getModelName() + "'失败，模型名称已存在");
        }
        model.setUpdateBy(getLoginName());
        return toAjax(modelService.updateModel(model));
    }

    /**
     * 删除模型
     */
    @RequiresPermissions("ai:model:remove")
    @Log(title = "LLM模型管理", businessType = BusinessType.DELETE)
    @PostMapping("/remove")
    @ResponseBody
    public AjaxResult remove(String ids)
    {
        modelService.deleteModelByIds(ids);
        return success();
    }

    /**
     * 测试已保存模型的连通性（列表页行内按钮）
     */
    @RequiresPermissions("ai:model:test")
    @Log(title = "LLM模型管理", businessType = BusinessType.OTHER)
    @PostMapping("/test/{modelId}")
    @ResponseBody
    public AjaxResult testById(@PathVariable("modelId") Long modelId)
    {
        return connectionTester.testById(modelId, modelService);
    }

    /**
     * 测试表单中尚未保存的配置（新增/编辑表单"测试连通"按钮）
     */
    @RequiresPermissions("ai:model:test")
    @PostMapping("/testConnect")
    @ResponseBody
    public AjaxResult testConnect(SysAiModel model)
    {
        return connectionTester.testByModel(model);
    }

    /**
     * 校验模型名称是否唯一
     */
    @PostMapping("/checkModelNameUnique")
    @ResponseBody
    public boolean checkModelNameUnique(SysAiModel model)
    {
        return modelService.checkModelNameUnique(model);
    }
}
