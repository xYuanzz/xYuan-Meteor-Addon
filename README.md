# xYuan's Mod — Meteor Client Addon

> 作者：**xYuan**

基于 [Meteor Client Addon API](https://github.com/MeteorDevelopment/meteor-client) 的多场景监控附属插件。实时识别 3c3u.org 服务器队列位置、不死图腾触发与玩家死亡，并通过**飞书自定义机器人 Webhook** 推送提醒。

---

## 功能

- **队列提醒**：监控排队位置，支持入队 / 进度 / 即将进入 / 完成 / 无排队进入 / 模块关闭 / 退出 / 异常断开 8 种场景。进度通知支持「默认」（整十位置 + 前 5 每名）与「自定义」步长两种模式二选一。
- **图腾提醒**：检测不死图腾触发，附带伤害来源（类型 + 实体名）与背包剩余图腾数量；剩余为 0 时紧急样式。同时支持玩家死亡提醒。
- **飞书 Webhook**：独立模块集中管理 Webhook 配置，供其他模块共用；未启用时设置仍生效。
- **白名单**：默认仅在 `3c3u.org` 生效，可在「高级」分类跳过校验。

---

## 项目结构

```
src/main/java/cn/anyho/xyuan/
├── QueueNoticeAddon.java          # 入口，注册分类与模块
├── modules/
│   ├── FeishuWebhookModule.java   # 飞书Webhook（共用配置）
│   ├── QueueNoticeModule.java     # 队列提醒
│   └── TotemNoticeModule.java     # 图腾提醒
└── util/
    ├── QueueParser.java           # 队列文本正则匹配
    └── FeishuWebhookSender.java   # 飞书 Webhook 请求 / 签名 / 异步发送
```

---

## 构建说明

环境：JDK 21 + Gradle 9.2.0。

```bash
# 首次拉取需生成 Gradle Wrapper
gradle wrapper --gradle-version 9.2.0

# 编译打包
./gradlew build
```

产物位于 `build/libs/xYuan's Mod-1.1.0.jar`，放入 Minecraft `mods` 目录即可。

---

## 飞书配置说明

1. 在飞书群聊「设置 → 群机器人 → 添加机器人 → 自定义机器人」，获取 **Webhook 地址** 与（可选）**签名密钥**。
2. 如启用「自定义关键词」安全校验，把关键词填入模块的「自定义消息前缀」。
3. 游戏内打开 Meteor GUI（默认右 Shift）→「xYuan's Mod」分类：
    - 填写「飞书Webhook」模块的地址 / 前缀 / 签名配置（无需启用即可生效）。
    - 启用「队列提醒」与/或「图腾提醒」模块，按需调整触发选项。

连接到 `3c3u.org` 后自动开始监控并推送提醒。

---

## 兼容性

| 组件 | 版本 |
|------|------|
| Minecraft | 1.21.11 |
| Yarn 映射 | 1.21.11+build.3 |
| Meteor Client | 1.21.11-SNAPSHOT |
| Fabric Loader | 0.18.2 |
| JDK | 21 |

---

## 许可证

[GPL 3.0](./LICENSE)

---

( Built with GLM-5.2 based on TRAE. )
