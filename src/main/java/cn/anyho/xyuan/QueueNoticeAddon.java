package cn.anyho.xyuan;

import cn.anyho.xyuan.modules.FeishuWebhookModule;
import cn.anyho.xyuan.modules.QueueNoticeModule;
import cn.anyho.xyuan.modules.TotemNoticeModule;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

/**
 * Meteor addon 入口：xYuan's Mod。
 *
 * <p>通过 {@code fabric.mod.json} 中的 {@code "meteor"} 入口注册。
 * 初始化时注册一个自定义分类与三个模块：
 * <ul>
 *   <li>{@link FeishuWebhookModule}（飞书Webhook）：集中管理 Webhook 配置，供其他模块共用</li>
 *   <li>{@link QueueNoticeModule}（队列提醒）：监控 3c3u.org 队列位置</li>
 *   <li>{@link TotemNoticeModule}（图腾提醒）：监控不死图腾触发并统计剩余数量</li>
 * </ul></p>
 *
 * <p>作者：xYuan</p>
 */
public class QueueNoticeAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    /** 自定义 Meteor 模块分类（GUI 中显示）。 */
    public static final Category CATEGORY = new Category("xYuan's Mod");

    @Override
    public void onInitialize() {
        LOG.info("Initializing xYuan's Mod");

        // Modules
        Modules.get().add(new FeishuWebhookModule());
        Modules.get().add(new QueueNoticeModule());
        Modules.get().add(new TotemNoticeModule());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "cn.anyho.xyuan";
    }

    @Override
    public GithubRepo getRepo() {
        // Replace with your own repository if you publish this addon.
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
