#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""天气查询 CLI — 本地模拟天气查询流程，返回结构化 JSON 供 DeepAgent 决策"""
import argparse, json, re, sys, time

CONVERSATION_STORE = {}

WEATHER_DATA = {
    "北京": {"weather": "晴", "temperature": "32°C", "humidity": "35%", "wind": "北风3级", "air_quality": "良"},
    "深圳": {"weather": "雨", "temperature": "28°C", "humidity": "85%", "wind": "东南风4级", "air_quality": "优"},
}

CITY_PATTERNS = ["天气", "查天气", "天气查询", "weather", "查询天气", "查一下天气"]


def _is_weather_query(text: str) -> bool:
    lower = text.lower()
    return any(p in lower for p in CITY_PATTERNS)


def _extract_city(text: str) -> str:
    for city in WEATHER_DATA:
        if city in text:
            return city
    m = re.search(r'([\u4e00-\u9fa5]{2,4}(?:市|区|县)?)', text)
    if m:
        return m.group(1)
    return ""


def _format_weather_result(city: str, data: dict) -> str:
    return (
        f"查询完成！{city}天气详情：天气-{data['weather']}，"
        f"温度-{data['temperature']}，湿度-{data['humidity']}，"
        f"风力-{data['wind']}，空气质量-{data['air_quality']}。"
    )


def _local_weather_query(query_text: str, conversation_id: str = None) -> dict:
    conv_id = conversation_id or f"local_{int(time.time() * 1000)}"
    supported_cities = "、".join(WEATHER_DATA.keys())

    if conversation_id and conversation_id in CONVERSATION_STORE:
        city = _extract_city(query_text)
        if city and city in WEATHER_DATA:
            data = WEATHER_DATA[city]
            CONVERSATION_STORE[conversation_id] = {"city": city}
            return {
                "status": "completed",
                "conversation_id": conv_id,
                "node_id": "",
                "result": _format_weather_result(city, data),
            }
        elif city:
            return {
                "status": "completed",
                "conversation_id": conv_id,
                "node_id": "",
                "result": f"暂不支持查询{city}的天气，目前支持的城市：{supported_cities}。",
            }
        else:
            return {
                "status": "input-required",
                "conversation_id": conv_id,
                "node_id": "questioner",
                "result": f"请补充需要查询天气的城市名称。您已提供：{query_text}，但仍需具体的城市名称。",
            }

    city = _extract_city(query_text)
    if city and city in WEATHER_DATA:
        data = WEATHER_DATA[city]
        CONVERSATION_STORE[conv_id] = {"city": city}
        return {
            "status": "completed",
            "conversation_id": conv_id,
            "node_id": "",
            "result": _format_weather_result(city, data),
        }
    elif city:
        CONVERSATION_STORE[conv_id] = {"query": query_text}
        return {
            "status": "completed",
            "conversation_id": conv_id,
            "node_id": "",
            "result": f"暂不支持查询{city}的天气，目前支持的城市：{supported_cities}。",
        }

    if _is_weather_query(query_text):
        CONVERSATION_STORE[conv_id] = {"query": query_text}
        return {
            "status": "input-required",
            "conversation_id": conv_id,
            "node_id": "questioner",
            "result": "请补充需要查询天气的城市名称。",
        }

    CONVERSATION_STORE[conv_id] = {"query": query_text}
    return {
        "status": "input-required",
        "conversation_id": conv_id,
        "node_id": "questioner",
        "result": "请提供城市名称以进行天气查询。",
    }


def main():
    parser = argparse.ArgumentParser(description="天气查询 — 本地模拟查询并返回结构化 JSON")
    parser.add_argument("--query", required=True, help="查询内容")
    parser.add_argument("--conversation_id", default=None, help="多轮会话 ID（续对话时传入）")
    parser.add_argument("--node_id", default=None, help="节点 ID（续对话时传入）")
    args = parser.parse_args()

    output = _local_weather_query(args.query, args.conversation_id)
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()
