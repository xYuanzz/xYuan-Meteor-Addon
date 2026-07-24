# xYuan's Mod — Meteor Client Addon

> 作者：**xYuan**

基于 [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) 的附属插件，通过**飞书自定义机器人 Webhook** 推送多场景提醒。

## 功能

- **队列提醒**：监控排队进度，入队/完成/退出/异常断开等场景推送提醒。
- **图腾提醒**：图腾耗尽与玩家死亡时推送提醒，附带伤害来源与剩余数量。
- **玩家预警**：扫描附近陌生玩家，进入/离开视野即时推送 + 定时名单快照，附威胁等级评估。
- **飞书 Webhook**：集中管理 Webhook 配置，供其他模块共用。
- **数据包调试**：抓取接收/发送数据包写入日志，支持按方向/状态/类名过滤。

## 项目结构

```
src/main/java/cn/anyho/xyuan/
├── QueueNoticeAddon.java          # 入口，注册分类与模块
├── modules/
│   ├── FeishuWebhookModule.java   # 飞书Webhook（共用配置）
│   ├── QueueNoticeModule.java     # 队列提醒
│   ├── TotemNoticeModule.java     # 图腾提醒
│   ├── PlayerRadarModule.java     # 玩家预警
│   └── PacketDebugModule.java     # 数据包调试
└── util/
    ├── QueueParser.java           # 队列文本正则匹配
    ├── FeishuWebhookSender.java   # 飞书 Webhook 请求 / 签名 / 异步发送
    ├── ThreatLevelCalculator.java # 威胁等级计算
    └── PlayerRadarHistory.java    # 玩家预警历史记录
```

## 构建

环境：JDK 21 + Gradle。

```bash
./gradlew build
```

产物位于 `build/libs`，放入 Minecraft `mods` 目录即可。

## 飞书配置

1. 飞书群聊添加「自定义机器人」，获取 Webhook 地址与（可选）签名密钥。
2. 游戏内打开 Meteor GUI →「xYuan's Mod」分类，填写「飞书Webhook」模块配置。
3. 启用所需提醒模块即可。

## 兼容性

| 组件 | 版本 |
|------|------|
| Minecraft | 1.21.11 |
| Meteor Client | 1.21.11-SNAPSHOT |
| Fabric Loader | 0.18.2 |
| JDK | 21 |

## 许可证

[GPL-3.0](./LICENSE)

---

Powered by GLM 5.2.
