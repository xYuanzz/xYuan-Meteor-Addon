package cn.anyho.xyuan;

import cn.anyho.xyuan.modules.FeishuWebhookModule;
import cn.anyho.xyuan.modules.PacketDebugModule;
import cn.anyho.xyuan.modules.PlayerRadarModule;
import cn.anyho.xyuan.modules.QueueNoticeModule;
import cn.anyho.xyuan.modules.TotemNoticeModule;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Meteor addon 入口：xYuan's Mod。作者：xYuan。 */
public class QueueNoticeAddon extends MeteorAddon {

    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("xYuan's Mod");

    @Override
    public void onInitialize() {
        LOG.info("Initializing xYuan's Mod");
        Modules.get().add(new QueueNoticeModule());
        Modules.get().add(new TotemNoticeModule());
        Modules.get().add(new PlayerRadarModule());
        Modules.get().add(new FeishuWebhookModule());
        Modules.get().add(new PacketDebugModule());

        // Meteor 在所有 addon 的 onInitialize() 后会调用 Modules.get().sortModules()
        // 按 title 字母序强制重排，覆盖 add() 顺序。中文名按 Unicode 码点排序，
        // 无法得到期望的显示顺序。通过 preLoadTask（在 sortModules 之后、GUI 打开前执行）
        // 按指定顺序重排本分类下的模块。
        Systems.addPreLoadTask(() -> {
            List<Module> group = Modules.get().getGroup(CATEGORY);
            if (group == null || group.isEmpty()) return;

            // 期望的显示顺序（从上到下）
            List<String> desiredOrder = List.of(
                    "队列提醒",
                    "图腾提醒",
                    "玩家预警",
                    "飞书Webhook",
                    "数据包调试"
            );

            // 按期望顺序构建新列表
            Map<String, Module> byName = new HashMap<>();
            for (Module m : group) byName.put(m.name, m);

            List<Module> sorted = new ArrayList<>(group.size());
            for (String name : desiredOrder) {
                Module m = byName.remove(name);
                if (m != null) sorted.add(m);
            }
            // 未在期望列表中的模块追加到末尾
            sorted.addAll(byName.values());

            group.clear();
            group.addAll(sorted);
        });
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
