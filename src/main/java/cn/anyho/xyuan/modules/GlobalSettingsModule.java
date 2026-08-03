package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import cn.anyho.xyuan.util.FeishuWebhookSender;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * 全局设置模块：集中管理全模组共用的配置，供其他模块统一读取。
 *
 * <p>包含两部分：
 * <ul>
 *   <li><b>服务器白名单校验</b>：默认仅 3c3u.org 触发，开启「不校验服务器地址」后放开到任意服务器。
 *       各功能模块通过 {@link #get()} 获取实例后调用 {@link #isOnTargetServer()} /
 *       {@link #isNotOnTargetServer()} 统一判定，不再各自维护校验设置</li>
 *   <li><b>飞书自定义机器人 Webhook</b>：地址、消息前缀与签名校验，
 *       各提醒模块通过 {@link #sendMarkdown(String)} 共用推送</li>
 * </ul>
 * 本模块无需处于启用状态，设置在任意状态下均生效。</p>
 */
public class GlobalSettingsModule extends Module {

    /** 目标服务器地址关键字（小写包含匹配）。 */
    private static final String TARGET_SERVER = "3c3u.org";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWebhook = settings.createGroup("飞书Webhook");
    private final SettingGroup sgSign = settings.createGroup("签名校验");

    // ---------- 服务器白名单 ----------

    private final Setting<Boolean> skipServerCheck = sgGeneral.add(new BoolSetting.Builder()
            .name("不校验服务器地址")
            .description("跳过 3c3u.org 白名单校验，允许所有模块在任意服务器触发。")
            .defaultValue(false)
            .build()
    );

    // ---------- 飞书 Webhook ----------

    private final Setting<String> webhookUrl = sgWebhook.add(new StringSetting.Builder()
            .name("飞书Webhook地址")
            .description("飞书自定义机器人的完整 Webhook 地址。")
            .defaultValue("")
            .build()
    );

    private final Setting<String> messagePrefix = sgWebhook.add(new StringSetting.Builder()
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
            .visible(enableSign::get)
            .build()
    );

    public GlobalSettingsModule() {
        super(QueueNoticeAddon.CATEGORY, "全局设置", "集中管理服务器白名单校验与飞书Webhook配置，供其他模块共用。");
    }

    /** 获取全局设置模块实例（已在 addon 入口注册，正常运行时不会为 null）。 */
    public static GlobalSettingsModule get() {
        return Modules.get().get(GlobalSettingsModule.class);
    }

    /** 当前是否在目标服务器（3c3u.org）。开启「不校验服务器地址」时恒为 true。 */
    public boolean isOnTargetServer() {
        if (skipServerCheck.get()) {
            return true;
        }
        if (mc.getCurrentServerEntry() == null) {
            return false;
        }
        String address = mc.getCurrentServerEntry().address;
        return address != null && address.toLowerCase().contains(TARGET_SERVER);
    }

    /** 当前是否不在目标服务器（3c3u.org）。开启「不校验服务器地址」时恒为 false。 */
    public boolean isNotOnTargetServer() {
        return !isOnTargetServer();
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
