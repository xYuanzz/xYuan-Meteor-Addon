package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import cn.anyho.xyuan.util.FeishuWebhookSender;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Meteor 附属模块：集中管理飞书自定义机器人 Webhook 配置，避免在多个提醒模块中重复定义。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>持有 Webhook 地址、自定义消息前缀、签名校验开关与签名密钥</li>
 *   <li>提供统一的 {@link #sendMarkdown(String)} 异步推送接口，供其他模块调用</li>
 *   <li>签名密钥输入框仅在「启用签名校验」开启时显示（通过 {@code visible} 条件）</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <p>其他模块（如队列提醒、图腾提醒）通过以下方式调用：</p>
 * <pre>
 * FeishuWebhookModule webhook = Modules.get().get(FeishuWebhookModule.class);
 * if (webhook != null && webhook.isActive()) {
 *     webhook.sendMarkdown(markdownContent);
 * }
 * </pre>
 *
 * <p>若本模块未启用，或 Webhook 地址为空，{@link #sendMarkdown(String)} 静默跳过，
 * 不会抛出异常。</p>
 */
public class FeishuWebhookModule extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSign = settings.createGroup("签名校验");

    // ---------- 基础设置 ----------

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

    // ---------- 签名校验 ----------

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
            .visible(() -> enableSign.get())
            .build()
    );

    public FeishuWebhookModule() {
        super(QueueNoticeAddon.CATEGORY, "飞书Webhook", "集中管理飞书自定义机器人 Webhook 配置，供其他提醒模块共用。");
    }

    @Override
    public void onActivate() {
        // 无需初始化状态
    }

    @Override
    public void onDeactivate() {
        // 无需清理状态
    }

    /**
     * 异步推送 Markdown 内容到飞书 Webhook（不阻塞主线程）。
     *
     * <p>若 Webhook 地址为空则静默跳过。自动拼接「自定义消息前缀」，
     * 并在启用签名校验时附带 {@code timestamp} 与 {@code sign} 字段。</p>
     *
     * @param markdownContent 飞书交互式卡片的 Markdown 内容
     */
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
