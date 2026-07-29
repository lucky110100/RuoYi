package com.ruoyi.ai.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.annotation.Excel.ColumnType;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * LLM 模型配置表 sys_ai_model
 *
 * @author ruoyi
 */
public class SysAiModel extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 模型主键 */
    @Excel(name = "模型主键", cellType = ColumnType.NUMERIC)
    private Long modelId;

    /** 模型名称（传给 LLM 的 model 标识，如 GLM-5.1） */
    @Excel(name = "模型名称")
    private String modelName;

    /** 模型类型（chat 对话 / reasoning 推理 / embedding 嵌入） */
    @Excel(name = "模型类型")
    private String modelType;

    /** 模型版本（展示用，如 5.1） */
    @Excel(name = "模型版本")
    private String modelVersion;

    /** 后端提供方（AgentCore client_provider，如 OpenAI / DashScope） */
    @Excel(name = "提供方")
    private String modelProvider;

    /** API 基地址 */
    @Excel(name = "API基地址")
    private String apiBase;

    /** API Key */
    @Excel(name = "API Key")
    private String apiKey;

    /** 是否校验 SSL（0否 1是） */
    @Excel(name = "SSL校验", readConverterExp = "0=否,1=是")
    private String sslVerify;

    /** 状态（0启用 1停用） */
    @Excel(name = "状态", readConverterExp = "0=启用,1=停用")
    private String status;

    /** 是否默认模型（Y是 N否） */
    @Excel(name = "默认", readConverterExp = "Y=是,N=否")
    private String isDefault;

    /** 显示顺序 */
    @Excel(name = "排序")
    private Integer sortOrder;

    public Long getModelId()
    {
        return modelId;
    }

    public void setModelId(Long modelId)
    {
        this.modelId = modelId;
    }

    @NotBlank(message = "模型名称不能为空")
    @Size(min = 0, max = 100, message = "模型名称长度不能超过100个字符")
    public String getModelName()
    {
        return modelName;
    }

    public void setModelName(String modelName)
    {
        this.modelName = modelName;
    }

    @Size(min = 0, max = 50, message = "模型类型长度不能超过50个字符")
    public String getModelType()
    {
        return modelType;
    }

    public void setModelType(String modelType)
    {
        this.modelType = modelType;
    }

    @Size(min = 0, max = 50, message = "模型版本长度不能超过50个字符")
    public String getModelVersion()
    {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion)
    {
        this.modelVersion = modelVersion;
    }

    @NotBlank(message = "提供方不能为空")
    @Size(min = 0, max = 50, message = "提供方长度不能超过50个字符")
    public String getModelProvider()
    {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider)
    {
        this.modelProvider = modelProvider;
    }

    @NotBlank(message = "API 基地址不能为空")
    @Size(min = 0, max = 255, message = "API 基地址长度不能超过255个字符")
    public String getApiBase()
    {
        return apiBase;
    }

    public void setApiBase(String apiBase)
    {
        this.apiBase = apiBase;
    }

    @NotBlank(message = "API Key 不能为空")
    @Size(min = 0, max = 255, message = "API Key 长度不能超过255个字符")
    public String getApiKey()
    {
        return apiKey;
    }

    public void setApiKey(String apiKey)
    {
        this.apiKey = apiKey;
    }

    public String getSslVerify()
    {
        return sslVerify;
    }

    public void setSslVerify(String sslVerify)
    {
        this.sslVerify = sslVerify;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getIsDefault()
    {
        return isDefault;
    }

    public void setIsDefault(String isDefault)
    {
        this.isDefault = isDefault;
    }

    public Integer getSortOrder()
    {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder)
    {
        this.sortOrder = sortOrder;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("modelId", getModelId())
                .append("modelName", getModelName())
                .append("modelType", getModelType())
                .append("modelVersion", getModelVersion())
                .append("modelProvider", getModelProvider())
                .append("apiBase", getApiBase())
                .append("apiKey", getApiKey())
                .append("sslVerify", getSslVerify())
                .append("status", getStatus())
                .append("isDefault", getIsDefault())
                .append("sortOrder", getSortOrder())
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .append("updateBy", getUpdateBy())
                .append("updateTime", getUpdateTime())
                .append("remark", getRemark())
                .toString();
    }
}
