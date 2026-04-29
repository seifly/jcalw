# wechat-ilink-sdk API 正确使用指南

本文面向业务系统接入方，说明如何用 `wechat-ilink-sdk` 正确构建微信机器人服务。重点是理解 SDK 的运行模型：登录是异步的，消息监听依赖主动拉取，发送消息依赖最近一次入站消息带来的 `contextToken`。

## 核心模型

SDK 不是 WebSocket 推送模型，也不是注册 `onMessage` 后自动后台监听消息。

正确理解是：

1. 业务应用创建 `ILinkClient` 并注册监听器。
2. 业务应用调用 `executeLogin()` 获取二维码内容。
3. 用户扫码后，`getLoginFuture()` 完成，SDK 进入已登录状态。
4. 业务应用周期性调用 `client.getUpdates()` 主动拉取消息。
5. `getUpdates()` 拉到非空消息后，SDK 才会触发 `OnMessageListener`。
6. SDK 在拉取消息时自动维护 `cursor`，并缓存每个用户最新的 `contextToken`。
7. 后续 `sendText`、`sendImage`、`startTyping` 等发送类 API 会使用缓存的最新 `contextToken`。

## 最小接入流程

```java
ILinkConfig config = ILinkConfig.builder()
    .connectTimeoutMs(35000)
    .readTimeoutMs(35000)
    .writeTimeoutMs(35000)
    .httpMaxRetries(3)
    .heartbeatEnabled(false)
    .build();

ILinkClient client = ILinkClient.builder()
    .config(config)
    .onLogin(new OnLoginListener() {
        @Override
        public void onLoginSuccess(LoginContext context) {
            System.out.println("login success, botId = " + context.getBotId());
        }

        @Override
        public void onLoginFailure(Throwable throwable) {
            throwable.printStackTrace();
        }
    })
    .onMessage(new OnMessageListener() {
        @Override
        public void onMessages(List<WeixinMessage> messages) {
            for (WeixinMessage message : messages) {
                System.out.println("message from " + message.getFrom_user_id());
            }
        }
    })
    .build();

String qrCodeContent = client.executeLogin();
System.out.println(qrCodeContent);

LoginContext loginContext = client.getLoginFuture().get();
System.out.println("logged in: " + loginContext.getBotId());

List<WeixinMessage> messages = client.getUpdates();
```

## 登录 API

### `executeLogin()`

启动二维码登录流程，并返回二维码原始内容。调用后 SDK 内部会异步轮询登录状态。

```java
String qrCodeContent = client.executeLogin();
```

注意事项：

- 返回值需要由业务方展示给用户扫码，可以渲染成二维码。
- 登录成功或失败通过 `getLoginFuture()` 和 `OnLoginListener` 获取。
- 登录超时、二维码过期、网络异常都可能导致登录失败。

### `getLoginFuture()`

获取异步登录结果。

```java
LoginContext context = client.getLoginFuture().get(180, TimeUnit.SECONDS);
```

企业应用中建议设置超时时间，避免线程永久等待。

### `cancelLogin()`

取消当前登录流程。

```java
client.cancelLogin();
```

适合用户关闭二维码页面、后台服务停止、重新发起登录前使用。

## 消息拉取与监听器

### `getUpdates()`

主动拉取增量消息。

```java
List<WeixinMessage> messages = client.getUpdates();
```

这是消息消费链路的核心 API。SDK 内部会做三件事：

- 使用内部保存的 `cursor` 拉取增量消息。
- 用响应中的新 cursor 更新内部游标。
- 从入站消息中提取 `contextToken`，按 `(botId, fromUserId)` 缓存会话上下文。

如果拉到的消息列表非空，SDK 会调用所有已注册的 `OnMessageListener`。

### `onMessage(...)`

注册消息回调。

```java
ILinkClient client = ILinkClient.builder()
    .onMessage(new OnMessageListener() {
        @Override
        public void onMessages(List<WeixinMessage> messages) {
            // 这里只处理业务逻辑
        }
    })
    .build();
```

重要：`onMessage` 不会自动拉取消息。它只会在业务调用 `client.getUpdates()` 且拉到非空消息后被触发。

### 推荐的企业消息泵

常驻机器人服务建议在应用层启动一个单独的消息泵，周期性调用 `getUpdates()`，把业务处理放到 `OnMessageListener` 中。

```java
ScheduledExecutorService messagePump = Executors.newSingleThreadScheduledExecutor();

messagePump.scheduleWithFixedDelay(new Runnable() {
    @Override
    public void run() {
        if (!client.isLoggedIn()) {
            return;
        }
        try {
            client.getUpdates();
        } catch (Exception e) {
            // 记录日志，等待下一轮重试
            e.printStackTrace();
        }
    }
}, 0, 1000, TimeUnit.MILLISECONDS);
```

更完整的示例见：

```text
wechat-ilink-sdk-test/src/test/java/com/github/wechat/ilink/sdk/demo/EnterpriseEchoBotFlowTest.java
```

## 消息去重与消费状态

SDK 会通过 `cursor` 避免正常连续拉取时重复拿到同一批消息，但它不提供业务级的“处理成功确认”机制。

这意味着：

- `getUpdates()` 拉到消息后，cursor 已经可能前进。
- 如果 listener 业务逻辑失败，SDK 不知道这条消息是否处理成功。
- 如果服务拉到消息后宕机，SDK 不会自动重放未处理业务。

企业应用建议：

- 按 `message_id` 建立幂等表。
- listener 中先落库或投递内部队列，再异步执行业务。
- 发送回复前检查业务处理状态。
- 对外部接口调用、发送消息、媒体处理做好重试和幂等。

示例：

```java
public void onMessages(List<WeixinMessage> messages) {
    for (WeixinMessage message : messages) {
        Long messageId = message.getMessage_id();
        if (messageId == null || messageRepository.exists(messageId)) {
            continue;
        }

        messageRepository.savePending(message);
        workerPool.submit(() -> handleMessage(message));
    }
}
```

## 发送消息

### 前置条件

发送消息前，目标用户必须先给 Bot 发过消息，并且该消息已经被 `getUpdates()` 拉到。原因是发送接口依赖该用户最新的 `contextToken`。

如果没有上下文，SDK 会抛出类似异常：

```text
missing latest context token for userId=...
```

正确顺序：

1. 用户给 Bot 发消息。
2. 应用调用 `client.getUpdates()` 拉到该消息。
3. SDK 缓存该用户最新 `contextToken`。
4. 应用调用 `sendText` 或其他发送 API。

### 发送文本

```java
client.sendText("user@im.wechat", "Hello");
```

### 带输入态发送文本

```java
client.sendTextWithTyping("user@im.wechat", "正在处理，请稍等", 1000L);
```

### 发送媒体

```java
client.sendImage("user@im.wechat", imageBytes, "image.png", "图片说明");
client.sendFile("user@im.wechat", fileBytes, "report.pdf", "文件说明");
client.sendVoice("user@im.wechat", voiceBytes, "voice.silk", 3000, 16000);
client.sendVideo("user@im.wechat", videoBytes, "video.mp4", 5000, "视频说明");
```

媒体发送会涉及上传、加密、发送消息等多个步骤。企业应用中建议记录发送任务状态，避免网络异常后重复发送不可控。

## 读取消息内容

一条 `WeixinMessage` 可能包含多个 `MessageItem`。

```java
for (WeixinMessage message : messages) {
    String fromUserId = message.getFrom_user_id();

    if (message.getItem_list() == null) {
        continue;
    }

    for (MessageItem item : message.getItem_list()) {
        if (item.getText_item() != null) {
            String text = item.getText_item().getText();
            System.out.println(fromUserId + ": " + text);
        }
    }
}
```

常见字段：

| 字段 | 说明 |
|---|---|
| `message_id` | 消息 ID，建议用于业务幂等 |
| `from_user_id` | 发送用户 ID，回复时通常作为 `toUserId` |
| `to_user_id` | 接收方 ID |
| `context_token` | 本次会话上下文，SDK 会自动缓存 |
| `item_list` | 消息内容列表 |

## 下载媒体

收到媒体消息后，可以从消息项中下载。

```java
for (MessageItem item : message.getItem_list()) {
    if (item.getImage_item() != null) {
        byte[] bytes = client.downloadImageFromMessageItem(item);
        Files.write(Paths.get("image.bin"), bytes);
    }
}
```

通用下载：

```java
byte[] bytes = client.downloadMediaFromMessageItem(item);
```

专用下载：

```java
client.downloadImageFromMessageItem(item);
client.downloadFileFromMessageItem(item);
client.downloadVoiceFromMessageItem(item);
client.downloadVideoFromMessageItem(item);
```

## 上下文管理

### `clearContext(userId)`

清理单个用户的会话上下文。

```java
client.clearContext("user@im.wechat");
```

清理后，如果没有重新通过 `getUpdates()` 拉到该用户的新消息，再向该用户发送会失败。

### `clearAllContexts()`

清理全部会话上下文。

```java
client.clearAllContexts();
```

一般只在用户登出、服务切换账号、测试清理时使用。

## 服务重启恢复

SDK 支持导出恢复上下文：

```java
ResumeContext resumeContext = client.exportResumeContext();
```

恢复创建客户端：

```java
ILinkClient client = ILinkClient.builder()
    .resumeContext(resumeContext)
    .onMessage(listener)
    .build();
```

`ResumeContext` 包含：

- `LoginContext`
- `getUpdates` cursor
- 已缓存的用户 `ConversationContext`

企业应用如果需要跨进程重启恢复，需要自行序列化保存这些信息，并在下次启动时还原为 `ResumeContext`。

## 生命周期管理

`ILinkClient` 实现了 `AutoCloseable`，服务停止时必须关闭。

```java
try (ILinkClient client = ILinkClient.builder().build()) {
    // run bot
}
```

或：

```java
client.close();
```

关闭会释放登录轮询、线程池、心跳任务、cursor 和上下文缓存。

## 心跳注意事项

当前 SDK 的心跳健康检查会调用底层消息拉取逻辑。它可以推进 `getUpdates` cursor，但不会触发 `OnMessageListener`。

如果业务应用已经维护自己的消息泵，建议：

- 将 `heartbeatEnabled(false)`。
- 或确保不会有多个后台任务同时消费 `getUpdates`。
- 后续可考虑改造 SDK，把心跳与消息消费彻底分离。

## 推荐生产架构

推荐分层：

```text
ILinkClient
  |
  |-- LoginManager: 扫码登录、登录状态、恢复上下文
  |-- MessagePump: 单线程周期性 getUpdates
  |-- OnMessageListener: 消息分发入口
  |-- MessageStore: message_id 幂等、处理状态
  |-- WorkerPool: 业务处理、调用模型、调用外部系统
  |-- ReplyService: 统一发送文本、媒体、输入态
```

推荐处理顺序：

1. `MessagePump` 调用 `client.getUpdates()`。
2. SDK 触发 `OnMessageListener`。
3. listener 检查 `message_id` 幂等。
4. listener 将消息落库或入队。
5. worker 异步处理业务。
6. worker 调用 `client.sendText(...)` 或媒体发送 API 回复。
7. 定期保存 `client.exportResumeContext()`。

## 常见错误

### 只注册 `onMessage`，但没有调用 `getUpdates()`

现象：永远收不到消息。

原因：`onMessage` 不是自动监听线程。

解决：启动消息泵，周期性调用 `client.getUpdates()`。

### 直接调用 `sendText()` 给陌生用户

现象：报 `missing latest context token`。

原因：该用户没有被 `getUpdates()` 拉到过，SDK 没有该用户上下文。

解决：让用户先给 Bot 发消息，拉取后再回复。

### 同时开启心跳和自定义消息泵

现象：偶发消息没有进入 `onMessage`。

原因：心跳可能消费了 `getUpdates` cursor。

解决：企业消息泵场景下关闭 SDK 心跳。

### listener 里直接做耗时业务

现象：拉取线程阻塞，消息积压，故障难恢复。

解决：listener 中只做幂等、落库、投递队列，耗时逻辑交给 worker。

## 参考示例

- 简单 Echo Bot：`wechat-ilink-sdk-test/src/test/java/com/github/wechat/ilink/sdk/demo/EchoBotDemoTest.java`
- 企业服务化 Echo Bot：`wechat-ilink-sdk-test/src/test/java/com/github/wechat/ilink/sdk/demo/EnterpriseEchoBotFlowTest.java`
