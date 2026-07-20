package cn.anyho.xyuan;

import cn.anyho.xyuan.modules.QueueNoticeModule;
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
 * 初始化时注册一个自定义分类与单个 {@link QueueNoticeModule}（队列提醒），
 * 后者承载所有队列监控逻辑。</p>
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
        Modules.get().add(new QueueNoticeModule());
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
