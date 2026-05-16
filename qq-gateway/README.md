# QQ Gateway for jclaw

QQ 机器人网关服务，将 QQ 消息转发到 jclaw。

## ✨ 功能

- ✅ 自动从 `~/.jclaw/config.json` 读取配置（无需单独配置文件）
- ✅ 通过 WebSocket 连接 QQ 开放平台
- ✅ 接收私聊和群聊 @ 消息
- ✅ 消息格式转换并转发到 jclaw Webhook
- ✅ 自动重连机制
- ✅ 心跳保活
- ✅ 令牌自动刷新

## 📋 前置要求

1. **Java 17+**
2. **jclaw 已配置** - `~/.jclaw/config.json` 中已配置 QQ 通道
3. **Maven 3.x**（用于构建）

## 🚀 快速开始

### 1. 配置 jclaw

确保 `~/.jclaw/config.json` 中有 QQ 通道配置：

```json
{
  "channels": {
    "qq": {
      "enabled": true,
      "appId": "your_qq_app_id",
      "appSecret": "your_qq_app_secret",
      "allowFrom": []
    }
  }
}
```

> 💡 **提示**：如果还没有启动过 jclaw，可以先运行一次 `java -jar jclaw-0.1.0.jar` 生成配置文件。

### 2. 构建项目

```bash
cd qq-gateway
mvn clean package
```

构建成功后会生成：
- `target/qq-gateway-1.0.0.jar` - **包含所有依赖的可执行 JAR**
- `target/original-qq-gateway-1.0.0.jar` - 原始 JAR（不含依赖）

### 3. 运行网关

#### 方式 1: 直接运行 JAR（推荐）

```bash
java -jar target/qq-gateway-1.0.0.jar
```

#### 方式 2: 使用启动脚本

```bash
chmod +x run.sh
./run.sh
```

#### 方式 3: 使用 Maven

```bash
mvn compile exec:java -Dexec.mainClass="cn.seifly.jclaw.qqgateway.QQGateway"
```

## 📊 运行效果

启动成功后会看到：

```
========================================
     QQ Gateway for jclaw v1.0.0
========================================

正在加载 jclaw 配置: /home/user/.jclaw/config.json
✓ QQ 配置加载成功
  App ID: 1903...3877
  Webhook URL: http://localhost:18790/api/channels/qq/webhook
正在获取 QQ 访问令牌...
✓ 访问令牌获取成功
  Token: abcd...wxyz
  有效期: 7200 秒
正在连接 QQ WebSocket...
✓ WebSocket 连接成功

网关正在运行... 按 Ctrl+C 退出
```

## 🔧 配置说明

### 配置来源

QQ Gateway **不需要单独的配置文件**，所有配置都从 jclaw 的配置文件读取：

| 参数 | 来源 | 默认值 |
|------|------|--------|
| `appId` | `~/.jclaw/config.json` → `channels.qq.appId` | - |
| `appSecret` | `~/.jclaw/config.json` → `channels.qq.appSecret` | - |
| `webhookUrl` | 硬编码 | `http://localhost:18790/api/channels/qq/webhook` |
| `reconnectDelayMs` | 硬编码 | `5000` |
| `heartbeatIntervalMs` | 硬编码 | `30000` |

### 修改配置

如需修改 webhook URL 或其他参数，需要：

1. **修改代码**：编辑 `GatewayConfig.java` 中的默认值
2. **重新编译**：`mvn clean package`

## 🧪 测试

### 测试 jclaw Webhook

在启动网关前，先测试 jclaw 的 Webhook 是否正常工作：

```bash
curl -X POST http://localhost:18790/api/channels/qq/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "id": "test_001",
    "content": "测试消息",
    "author": {"id": "123456"}
  }'
```

预期响应：
```json
{
  "success": true,
  "message": "Message processed"
}
```

### 测试完整流程

1. 启动 jclaw：`java -jar jclaw-0.1.0.jar`
2. 启动 QQ Gateway：`./run.sh`
3. 用 QQ 向机器人发送消息
4. 观察日志输出

## 🏗️ 架构

```
QQ用户 → QQ平台 → QQ Gateway (本项目) → jclaw Webhook → AI处理 → 回复
```

### 组件说明

- **QQ 平台**：腾讯 QQ 开放平台，提供机器人 API
- **QQ Gateway**：WebSocket 客户端，接收 QQ 消息并转发
- **jclaw Webhook**：`POST /api/channels/qq/webhook` 接收消息
- **AI 处理**：jclaw Agent 处理消息并生成回复
- **回复**：通过 QQ API 发送回复给用户

## ❓ 常见问题

### Q1: 提示"配置文件不存在"

**原因**：`~/.jclaw/config.json` 文件不存在

**解决**：先启动一次 jclaw 以生成配置文件

```bash
java -jar jclaw-0.1.0.jar
# 按 Ctrl+C 停止
```

### Q2: 提示"配置中缺少 appId"

**原因**：`config.json` 中未配置 QQ 通道

**解决**：编辑 `~/.jclaw/config.json`，添加 QQ 配置（见上方配置示例）

### Q3: WebSocket 连接失败

**可能原因**：
- App ID 或 App Secret 错误
- 网络连接问题
- QQ 机器人应用未审核通过

**解决**：
1. 检查配置是否正确
2. 确认可以访问 `wss://api.sgroup.qq.com`
3. 登录 QQ 开放平台确认应用状态

### Q4: 消息转发失败

**原因**：jclaw 未启动或 Webhook 不可达

**解决**：
1. 确认 jclaw 正在运行
2. 检查 Webhook URL 是否正确
3. 测试 Webhook 端点（见测试部分）

## 📝 开发

### 项目结构

```
qq-gateway/
├── src/main/java/cn/seifly/jclaw/qqgateway/
│   ├── QQGateway.java          # 主程序
│   ├── config/
│   │   └── GatewayConfig.java  # 配置管理
│   └── websocket/
│       └── QQWebSocketHandler.java  # WebSocket 处理
├── pom.xml                      # Maven 配置
├── run.sh                       # 启动脚本
└── README.md                    # 本文档
```

### 修改配置默认值

编辑 `src/main/java/cn/seifly/jclaw/qqgateway/config/GatewayConfig.java`：

```java
private String webhookUrl = "http://localhost:18790/api/channels/qq/webhook";
private long reconnectDelayMs = 5000;
private long heartbeatIntervalMs = 30000;
```

然后重新编译：`mvn clean package`

## 📄 License

Apache License 2.0
