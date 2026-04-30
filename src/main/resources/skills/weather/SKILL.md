---
name: weather
description: 查询天气信息。当用户询问任何城市、地区的当前天气、温度、湿度、风力、天气预报时使用此技能。支持的查询包括：天气怎么样、今天天气如何、明天会下雨吗、某城市温度、天气预报等。无需 API key。
homepage: https://wttr.in/:help
metadata: {"nanobot":{"emoji":"🌤️","requires":{"bins":["curl"]}}}
---

# Weather

查询任意城市或地区的天气信息。

## 重要提示

当用户询问某个城市的天气时：
1. 首先识别用户要查询的城市名称（从用户的消息中提取）
2. 将城市名称替换到以下 curl 命令中的 `{城市名}` 位置
3. 使用 exec 工具执行 curl 命令获取天气信息
4. 将结果整理后返回给用户

## wttr.in (主要服务)

### 快速查询（推荐）
将 `{城市名}` 替换为实际城市名称：

```bash
curl -s "wttr.in/{城市名}?format=%l:+%c+%t+%h+%w"
```

示例：
- 查询上海天气：`curl -s "wttr.in/上海?format=%l:+%c+%t+%h+%w"`
- 查询北京天气：`curl -s "wttr.in/北京?format=%l:+%c+%t+%h+%w"`
- 查询 London 天气：`curl -s "wttr.in/London?format=%l:+%c+%t+%h+%w"`

输出格式说明：
- `%l` - 位置名称
- `%c` - 天气状况（emoji）
- `%t` - 温度
- `%h` - 湿度
- `%w` - 风力

### 完整天气预报
```bash
curl -s "wttr.in/{城市名}?T"
```

## 使用技巧

- 中文城市名直接使用：`wttr.in/上海`、`wttr.in/北京`
- 英文城市名：`wttr.in/London`、`wttr.in/New+York`（空格用 `+` 代替）
- 机场代码：`wttr.in/JFK`、`wttr.in/PVG`（上海浦东机场）
- 单位：默认公制，如需英制添加 `?u` 参数

## Open-Meteo (备选服务，JSON 格式)

如果 wttr.in 不可用，使用 Open-Meteo：

```bash
curl -s "https://api.open-meteo.com/v1/forecast?latitude={纬度}&longitude={经度}&current_weather=true"
```

需要先查询城市的经纬度，然后替换 `{纬度}` 和 `{经度}`。

## 执行示例

当用户问"上海天气怎么样？"时：

1. 提取城市名：上海
2. 执行命令：`exec(command='curl -s "wttr.in/上海?format=%l:+%c+%t+%h+%w"')`
3. 获取结果后整理返回给用户

当用户问"明天会下雨吗？"时：

1. 从上下文或用户消息中确定城市
2. 执行完整天气预报：`exec(command='curl -s "wttr.in/{城市名}?T"')`
3. 分析结果，判断明天是否会下雨
