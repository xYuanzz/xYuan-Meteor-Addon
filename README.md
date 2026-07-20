# xYuan's Mod — Meteor Client Addon

> 作者：**xYuan**

基于 [Meteor Client Addon API](https://github.com/MeteorDevelopment/meteor-client) 开发的队列位置监控附属插件。
实时识别服务器排队位置，并通过**飞书自定义机器人 Webhook** 推送排队进度提醒，支持自定义前进名次触发阈值。

- 队列检测逻辑参考：[SnowZhouer/queue-notice-mod](https://github.com/SnowZhouer/queue-notice-mod)
- 项目结构参考：[MeteorDevelopment/meteor-addon-template](https://github.com/MeteorDevelopment/meteor-addon-template)
- Webhook 规范参考：[飞书自定义机器人官方文档](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot)

---

## 功能特性

### 1. 生效范围限制（3c3u.org 白名单）


### 2. 队列位置监控


### 3. 八种触发场景


### 4. 飞书 Webhook 推送


---


## 项目结构

```
xYuan's Mod/
├── build.gradle.kts                 # 构建脚本（Kotlin DSL，与官方模板一致）
├── settings.gradle.kts              # Gradle 设置
├── gradle.properties                # maven_group / archives_base_name
├── gradle/
│   ├── libs.versions.toml           # 版本目录（统一管理依赖版本）
│   └── wrapper/
│       └── gradle-wrapper.properties
├── LICENSE
├── README.md
└── src/main/
    ├── resources/
    │   └── fabric.mod.json          # Fabric / Meteor 入口声明
    └── java/cn/anyho/xyuan/
        ├── QueueNoticeAddon.java    # 主类，继承 MeteorAddon，注册模块与分类
        ├── modules/
        │   └── QueueNoticeModule.java  # 模块类，设置项 + 事件监听 + 触发逻辑
        └── util/
            ├── QueueParser.java        # 队列文本正则匹配 / 位置提取
            └── FeishuWebhookSender.java # 飞书 Webhook 请求构造 / 签名 / 异步发送
```

---

## 构建说明

### 1. 环境准备
- 安装 **JDK 21** 并配置 `JAVA_HOME`
- 安装 **Gradle 9.2.0**（或使用 IDE 自带的 Gradle）

### 2. 生成 Gradle Wrapper（首次构建）
本项目只包含 `gradle-wrapper.properties`，未内置 `gradlew` 脚本。首次拉取后，在项目根目录执行一次：

```bash
gradle wrapper --gradle-version 9.2.0
```

执行后会生成 `gradlew`、`gradlew.bat` 与 `gradle/wrapper/gradle-wrapper.jar`。之后即可使用 `./gradlew` 替代全局 `gradle` 命令。


### 3. 编译打包

```bash
# Linux / macOS
./gradlew build

# Windows
gradlew.bat build
```

构建产物位于 `build/libs/xYuan's Mod-1.0.0.jar`。

---

## 部署说明

1. 确保已安装对应版本的 **Meteor Client**（适配 MC 1.21.11）与 **Fabric Loader**（0.18.2）
2. 将构建产物 `xYuan's Mod.jar` 放入 Minecraft 的 `mods` 目录
3. 启动游戏，Meteor 会自动加载本附属并在模块列表中新增「xYuan's Mod」分类，下设「队列提醒」模块

---

## 使用说明

### 第一步：配置飞书自定义机器人

1. 在飞书群聊中「设置 → 群机器人 → 添加机器人 → 自定义机器人」，获取 **Webhook 地址** 与（可选）**签名校验密钥**
2. 如启用了「自定义关键词」安全校验，请把关键词填入模块的「自定义消息前缀」设置项（消息会自动以该前缀开头，从而通过校验）
3. 如启用了「签名校验」，记下签名密钥，稍后在模块中填写

### 第二步：在游戏中开启模块

1. 打开 Meteor GUI（默认右 Shift），进入「xYuan's Mod」分类
2. 启用「队列提醒」模块
3. 展开模块设置，填写各项参数（见下表）

### 第三步：排队进服

连接到 `3c3u.org` 服务器后，模块自动识别白名单并开始监控；进入队列时识别聊天 / 标题中的队列位置文本，按规则推送提醒到飞书群。若连接的不是 `3c3u.org`，模块不执行任何监听与推送。

---

### Webhook 设置

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| 飞书Webhook地址 | 字符串 | （空） | 飞书自定义机器人的完整 Webhook URL |
| 自定义消息前缀 | 字符串 | （空） | 用于适配飞书关键词安全校验，自动添加到消息开头 |

### 安全设置（签名校验）

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| 启用签名校验 | 布尔 | false | 启用飞书 HmacSHA256 + Base64 签名校验 |
| 签名密钥 | 字符串 | （空） | 飞书机器人签名密钥，启用校验时必填 |

### 高级设置

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| 不校验服务器地址 | 布尔 | false | 跳过 3c3u.org 白名单校验，允许在任意服务器触发排队提醒 |

---

## 许可证

本项目采用 [GPL 3.0](./LICENSE) 许可证。

---

( Built with GLM-5.2 based on TRAE. )
