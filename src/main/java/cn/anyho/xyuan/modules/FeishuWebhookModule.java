package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import cn.anyho.xyuan.util.FeishuWebhookSender;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

/** 飞书自定义机器人 Webhook 配置模块，集中管理供其他提醒模块共用。 */
public class FeishuWebhookModule extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSign = settings.createGroup("签名校验");

    private final Setting<String> webhookUrl = sgGeneral.add(new StringSetting.Builder()
            .name("飞书Webhook地址")
            .description("飞书自定义机器人的完整 Webhook 地址。")
            .defaultValue("")
            .wide()
            .build()
    );

    private final Setting<String> messagePrefix = sgGeneral.add(new StringSetting.Builder()
            .name("自定义消息前缀")
            .description("用于适配飞书机器人关键词安全校验，自动添加到消息开头。")
            .defaultValue("")
            .build()
    );

    private final Setting<Boolean> enableSign = sgSign.add(new BoolSetting.Builder()
            .name("启用签名校验")
            .description("启用飞书签名校验（HmacSHA256 + Base64）。")
            .defaultValue(false)
            .build()
    );

    /** 签名密钥：仅在「启用签名校验」开启时显示。 */
    private final Setting<String> signSecret = sgSign.add(new StringSetting.Builder()
            .name("签名密钥")
            .description("飞书机器人签名密钥，启用签名校验时必填。")
            .defaultValue("")
            .wide()
            .visible(enableSign::get)
            .build()
    );

    public FeishuWebhookModule() {
        super(QueueNoticeAddon.CATEGORY, "飞书Webhook", "集中管理飞书自定义机器人 Webhook 配置，供其他提醒模块共用。");
    }

    /** 异步推送 Markdown 内容到飞书 Webhook。地址为空时静默跳过。 */
    public void sendMarkdown(String markdownContent) {
        String url = webhookUrl.get();
        if (url == null || url.isBlank()) {
            return;
        }
        FeishuWebhookSender.sendAsync(
                url,
                messagePrefix.get(),
                markdownContent,
                enableSign.get(),
                signSecret.get(),
                QueueNoticeAddon.LOG
        );
    }
}
