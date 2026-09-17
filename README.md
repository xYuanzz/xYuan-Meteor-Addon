# xYuan's Mod — Meteor Client Addon

> 作者：**xYuan**

基于 [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) 的附属插件，通过**飞书自定义机器人 Webhook** 推送多场景提醒。

支持 Minecraft 1.21.1 / 1.21.4 / 1.21.11。

## 功能

- **队列提醒**：监控排队进度，入队/完成/退出/异常断开等场景推送提醒。
- **图腾提醒**：图腾耗尽与玩家死亡时推送提醒，附带伤害来源与剩余数量。
- **玩家预警**：扫描附近陌生玩家，进入/离开视野即时推送 + 定时名单快照，附威胁等级评估。
- **自动指令**：生命值/图腾/Y轴低于阈值时自动发送指定指令。
- **自动开关**：受击/生命值过低/死亡重生时自动切换指定模块。
- **宝库增强**：记录已开启宝库坐标并持久化，让透视区分未开/已开。
- **状态过滤**：为原版 BlockESP 增加按方块状态筛选。
- **飞书 Webhook**：集中管理 Webhook 配置，供其他模块共用。
- **数据包调试**：抓取接收/发送数据包写入日志，支持按方向/状态/类名过滤。

## 项目结构

```
versions/                           # 各版本依赖坐标（单一事实来源）
├── 1.21.1.properties
├── 1.21.4.properties
└── 1.21.11.properties

src/main/java/cn/anyho/xyuan/
├── QueueNoticeAddon.java           # 入口，注册分类与模块
├── modules/                        # 业务模块（与版本无关）
├── blockesp/                       # BlockESP 状态过滤
├── vault/                          # 宝库解锁记录与持久化
├── util/                           # 队列解析 / 飞书发送 / 威胁等级等
├── mixin/                          # 共用 mixin
├── v1211/compat/                   # 1.21.1 兼容层实现（不参与共享编译）
├── v1214/compat/                   # 1.21.4 兼容层实现
└── v12111/compat/                  # 1.21.11 兼容层实现
```

## 构建

环境：JDK 21。仓库已提交 Gradle wrapper。

```bash
./gradlew buildAll                            # 全部版本
./gradlew build -Pminecraft_version=1.21.4    # 单版本，产物在 build/libs
./gradlew printVersions -Pminecraft_version=1.21.4
```

`buildAll` 产物：

```
build/versions/<mc>/xyuan-mod-<mc>-<mod_version>.jar
```

把对应 MC 版本的 jar 放入 `mods` 目录即可（需同时安装对应版本的 Meteor Client）。


## 飞书配置

1. 飞书群聊添加「自定义机器人」，获取 Webhook 地址与（可选）签名密钥。
2. 游戏内打开 Meteor GUI →「xYuan's Mod」分类，填写「全局设置」模块里的 Webhook 配置。
3. 启用所需提醒模块即可。

## 兼容性

| Minecraft | Meteor Client | Fabric Loader | JDK |
|---|---|---|---|
| 1.21.11 | `1.21.11-SNAPSHOT` | 0.18.2 | 21 |
| 1.21.4 | `1.21.4-SNAPSHOT` | 0.16.9 | 21 |
| 1.21.1 | `0.5.8-SNAPSHOT` | 0.15.11 | 21 |


## 许可证

[GPL-3.0](./LICENSE)

---

Powered by GLM 5.2 · DeepSeek-V4.1-Flash
