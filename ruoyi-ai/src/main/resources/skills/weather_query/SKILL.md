---
name: weather_query
description: 查询城市天气
---

# 天气查询 (Weather Query)

查询城市天气。此技能支持交互式多轮对话。

目前支持的城市及天气数据：
- **北京**：晴，32°C，湿度35%，北风3级，空气质量-良
- **深圳**：雨，28°C，湿度85%，东南风4级，空气质量-优

## 调用命令

### 首次调用（不含城市名称）

```bash
python3 examples/deep_agent/skills/weather_query/cli.py --query "<查询内容>"
```

查询内容示例：`查天气`、`天气查询`

### 多轮续接调用（用户补充了城市名称）

当上一次调用返回了 `input-required` 状态后，用户补充了信息（如城市名称），需要使用相同的 `conversation_id` 再次调用 CLI：

```bash
python3 examples/deep_agent/skills/weather_query/cli.py --query "<用户补充的内容>" --conversation_id "<上一次返回的 conversation_id>"
```

示例：`python3 examples/deep_agent/skills/weather_query/cli.py --query "北京" --conversation_id "local_1783497547741"`

## 重要说明

1. **首次调用**：如果查询中包含支持的城市名称（如 `北京`），CLI 会直接返回天气结果（status=completed）。
2. **需要补充信息**：如果查询中不包含城市名称（如仅 `查天气`），CLI 返回 `input-required` 状态和 `conversation_id`。系统会中断当前任务，等待用户补充城市名称。
3. **续接调用**：当用户补充了城市名称，使用上一次的 `conversation_id` 再次调用 CLI，CLI 会返回最终天气结果（status=completed）。
4. **不支持的城市**：如果用户提供的城市不在支持列表中，CLI 会返回提示信息（status=completed）。
5. **不要猜测或虚构城市天气**，所有数据必须来自内置的城市天气映射。

## 输出格式

CLI 输出 JSON，格式如下：

### 首次查询（不含城市名称）

```json
{"status": "input-required", "conversation_id": "local_xxx", "node_id": "questioner", "result": "请补充需要查询天气的城市名称。"}
```

### 最终结果（含城市名称或续接调用）

```json
{"status": "completed", "conversation_id": "xxx", "node_id": "", "result": "查询完成！北京天气详情：天气-晴，温度-32°C，湿度-35%，风力-北风3级，空气质量-良。"}
```

### 不支持的城市

```json
{"status": "completed", "conversation_id": "xxx", "node_id": "", "result": "暂不支持查询上海的天气，目前支持的城市：北京、深圳。"}
```

### 字段说明

| 字段 | 说明 |
|------|------|
| `status` | `"completed"`=最终结果 \| `"input-required"`=需要补充信息 |
| `conversation_id` | 会话 ID（续接调用时必须传入相同的值） |
| `node_id` | `"questioner"`=需要用户输入 \| `""`=已完成 |
| `result` | 查询结果文本 |
