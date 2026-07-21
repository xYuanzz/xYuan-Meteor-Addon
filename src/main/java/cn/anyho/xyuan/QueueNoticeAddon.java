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

/** Meteor addon 入口：xYuan's Mod。作者：xYuan。 */
public class QueueNoticeAddon extends MeteorAddon {

    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("xYuan's Mod");

    @Override
    public void onInitialize() {
        LOG.info("Initializing xYuan's Mod");
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
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
