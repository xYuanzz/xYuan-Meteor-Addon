package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动开关模块：受击、生命值过低或死亡重生时，自动切换指定模块的启用状态。
 *
 * <p>三种触发条件（均可独立启停，全局共享作用于所有目标模块）：
 * <ul>
 *   <li>受击触发：收到本地玩家的伤害数据包即触发（任何伤害来源，
 *       含攻击、摔落、溺水等，与图腾提醒模块的伤害拦截一致）</li>
 *   <li>生命值触发：生命值低于阈值（严格小于）时触发一次，恢复到阈值及以上重新武装；
 *       阈值 0 禁用，默认 20 表示受伤即触发</li>
 *   <li>重生触发：死亡（DeathMessageS2CPacket）后重生时触发一次</li>
 * </ul></p>
 *
 * <p>目标模块按动作分三个列表（参考 glazed PlayerDetection 的模块控制方式）：
 * 「总是关闭」＞「总是启用」＞「切换状态」。同一模块出现在多个列表时按此优先级
 * 只执行一种动作；本模块自身在任何列表中都会被跳过，避免自我关闭。</p>
 *
 * <p>防抖：全局最短触发间隔（默认 3 秒）。间隔内的触发直接丢弃，防止受击与生命值
 * 等条件同时命中时对同一模块来回切换造成状态振荡；无实际状态变化的触发不占用防抖。</p>
 */
public class AutoModuleToggleModule extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTriggers = settings.createGroup("触发条件");
    private final SettingGroup sgModules = settings.createGroup("目标模块");

    // ---------- 基础 ----------

    private final Setting<Integer> debounce = sgGeneral.add(new IntSetting.Builder()
            .name("防抖间隔")
            .description("两次触发之间的最小间隔（秒）。间隔内的触发被丢弃，避免短时间内来回切换模块状态。")
            .defaultValue(3)
            .range(0, 60)
            .sliderRange(0, 30)
            .build()
    );

    // ---------- 触发条件 ----------

    private final Setting<Boolean> onDamage = sgTriggers.add(new BoolSetting.Builder()
            .name("受击触发")
            .description("受到任何伤害（含攻击、摔落、溺水等）时触发。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> healthThreshold = sgTriggers.add(new IntSetting.Builder()
            .name("生命值阈值")
            .description("当生命值低于此值时触发（严格小于）。设置为 0 禁用生命值触发，默认 20 表示受伤即触发。")
            .defaultValue(20)
            .range(0, 20)
            .sliderRange(0, 20)
            .build()
    );

    private final Setting<Boolean> onRespawn = sgTriggers.add(new BoolSetting.Builder()
            .name("重生触发")
            .description("死亡后重生时触发。")
            .defaultValue(true)
            .build()
    );

    // ---------- 目标模块 ----------

    private final Setting<List<Module>> disableModules = sgModules.add(new ModuleListSetting.Builder()
            .name("总是关闭的模块")
            .description("触发时若处于启用状态则关闭（已关闭则跳过）。优先级最高，对本模块自身不生效。")
            .defaultValue(new ArrayList<>())
            .build()
    );

    private final Setting<List<Module>> enableModules = sgModules.add(new ModuleListSetting.Builder()
            .name("总是启用的模块")
            .description("触发时若处于关闭状态则启用（已启用则跳过）。优先级次之，对本模块自身不生效。")
            .defaultValue(new ArrayList<>())
            .build()
    );

    private final Setting<List<Module>> toggleModules = sgModules.add(new ModuleListSetting.Builder()
            .name("切换状态的模块")
            .description("触发时反转启用状态（启用则关闭，关闭则启用）。优先级最低，对本模块自身不生效。")
            .defaultValue(new ArrayList<>())
            .build()
    );

    // ---------- 运行时状态 ----------

    /** 生命值边缘触发武装标记：恢复到阈值及以上才重新置 true。 */
    private boolean healthArmed = true;

    /** 死亡标记：收到本地玩家死亡数据包后置 true，等待重生。 */
    private boolean waitingRespawn;

    /** 最近一次实际触发时间戳（毫秒），用于全局防抖。 */
    private long lastTriggerMs;

    public AutoModuleToggleModule() {
        super(QueueNoticeAddon.CATEGORY, "自动开关", "受击/生命值过低/死亡重生时自动切换指定模块的启用状态。");
    }

    @Override
    public void onActivate() {
        healthArmed = true;
        waitingRespawn = false;
        lastTriggerMs = 0;
    }

    // ---------- 事件监听 ----------

    /**
     * 拦截数据包：伤害（EntityDamageS2CPacket → 受击触发）、
     * 死亡（DeathMessageS2CPacket → 标记等待重生）。
     */
    @SuppressWarnings("unused")
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (isNotOnTargetServer()) {
            return;
        }

        // 受击触发：本地玩家受到任何伤害
        if (event.packet instanceof EntityDamageS2CPacket dmgPacket) {
            if (onDamage.get()
                    && mc.player != null && !mc.player.isDead()
                    && dmgPacket.entityId() == mc.player.getId()) {
                executeTrigger("受到伤害");
            }
            return;
        }

        // 死亡标记：mc.player 为 null 时仍接受（死亡屏幕包专门发给当前客户端，
        // 与图腾提醒模块一致）；mc.player 存在时校验 playerId 防止其他玩家死亡误标
        if (event.packet instanceof DeathMessageS2CPacket deathPacket) {
            if (mc.player != null && deathPacket.playerId() != mc.player.getId()) {
                return;
            }
            waitingRespawn = true;
        }
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || isNotOnTargetServer()) {
            return;
        }

        // 重生触发：死亡标记存在时等待玩家复活（mc.player 重建且生命值恢复）
        if (waitingRespawn) {
            if (mc.player.isDead() || mc.player.getHealth() <= 0) {
                return; // 仍处于死亡状态，跳过其他检查
            }
            waitingRespawn = false;
            if (onRespawn.get()) {
                executeTrigger("死亡重生");
            }
        }

        // 死亡期间跳过生命值检查
        if (mc.player.isDead() || mc.player.getHealth() <= 0) {
            return;
        }

        // 生命值触发（边缘触发 + 防抖）：严格小于阈值触发一次，恢复后重新武装。
        // 与受击同时命中时受击先占用防抖，生命值边缘被丢弃，避免对同一模块来回切换
        int threshold = healthThreshold.get();
        if (threshold > 0) {
            if (mc.player.getHealth() < threshold) {
                if (healthArmed) {
                    healthArmed = false;
                    executeTrigger("生命值 " + String.format("%.1f", mc.player.getHealth())
                            + " 低于阈值 " + threshold);
                }
            } else {
                healthArmed = true;
            }
        }
    }

    // ---------- 触发执行 ----------

    /**
     * 执行一次触发：按「总是关闭 ＞ 总是启用 ＞ 切换状态」优先级处理三个模块列表。
     *
     * <p>全局防抖：距上次实际触发不足「防抖间隔」时直接丢弃。
     * 无任何实际状态变化（如启用列表中的模块已全部启用）时不占用防抖、不输出提示。
     * 本模块自身始终被跳过，避免自我关闭导致行为不可预期。</p>
     *
     * @param reason 触发原因描述（用于本地聊天反馈）
     */
    private void executeTrigger(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastTriggerMs < debounceMs()) {
            return;
        }

        Set<Module> processed = new HashSet<>();
        List<String> actions = new ArrayList<>();

        for (Module m : disableModules.get()) {
            if (m == null || m == this || !processed.add(m)) {
                continue;
            }
            if (m.isActive()) {
                m.toggle();
                actions.add("关闭 " + m.title);
            }
        }

        for (Module m : enableModules.get()) {
            if (m == null || m == this || !processed.add(m)) {
                continue;
            }
            if (!m.isActive()) {
                m.toggle();
                actions.add("启用 " + m.title);
            }
        }

        for (Module m : toggleModules.get()) {
            if (m == null || m == this || !processed.add(m)) {
                continue;
            }
            m.toggle();
            actions.add((m.isActive() ? "启用 " : "关闭 ") + m.title);
        }

        // 无实际状态变化：不占用防抖、不提示
        if (actions.isEmpty()) {
            return;
        }

        lastTriggerMs = now;
        info("[" + reason + "] " + String.join("，", actions));
    }

    // ---------- 工具方法 ----------

    private long debounceMs() {
        return debounce.get() * 1000L;
    }

    /** 服务器白名单校验已迁移至「全局设置」模块，此处委托全局配置。模块缺失时保守判定为不在目标服务器。 */
    private boolean isNotOnTargetServer() {
        GlobalSettingsModule global = GlobalSettingsModule.get();
        return global == null || global.isNotOnTargetServer();
    }

    /** 在模块列表标题旁显示已配置的目标模块总数，未配置时不显示。 */
    @Override
    public String getInfoString() {
        int total = disableModules.get().size() + enableModules.get().size() + toggleModules.get().size();
        return total == 0 ? null : String.valueOf(total);
    }
}
