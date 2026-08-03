package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;

/**
 * 自动指令模块：监控生命值、不死图腾数量与 Y 轴坐标，低于阈值时自动在聊天栏发送指定指令。
 *
 * <p>监控种类、判断逻辑与阈值设置参考 meteor-miku AutoLog：
 * <ul>
 *   <li>生命值：低于或等于阈值触发（阈值 0 禁用）；可选「智能预测」，
 *       把即将受到的伤害计入（生命值 + 伤害吸收 - 潜在伤害 ≤ 阈值即触发）</li>
 *   <li>图腾：背包（含快捷栏、护甲栏与副手）图腾总数低于或等于阈值触发</li>
 * </ul>
 * Y 轴坐标：低于或等于阈值触发。</p>
 *
 * <p>防刷屏：边缘触发 + 冷却双保险。数值跌破阈值时触发一次并撤防，
 * 恢复到阈值以上才重新武装，避免持续低于阈值时重复发送；
 * 同时每次触发受「触发冷却」最小间隔限制，防止数值在阈值附近抖动导致连发。
 * 死亡期间（生命值为 0）暂停全部检查， respawn 后随生命值恢复自动重新武装。</p>
 *
 * <p>指令发送机制参考 LeavesHack AutoLogin：直接发送 {@link CommandExecutionC2SPacket}
 * （内容不含 "/" 前缀；配置时写不写 "/" 均可，发送前自动剔除）。</p>
 */
public class AutoCommandModule extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHealth = settings.createGroup("生命值监控");
    private final SettingGroup sgTotem = settings.createGroup("图腾监控");
    private final SettingGroup sgYAxis = settings.createGroup("Y轴监控");

    // ---------- 基础 ----------

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
            .name("触发冷却")
            .description("同一监控项两次触发之间的最小间隔（秒）。防止数值在阈值附近抖动导致指令连发。")
            .defaultValue(5)
            .range(0, 300)
            .sliderRange(0, 60)
            .build()
    );

    // ---------- 生命值监控 ----------

    private final Setting<Integer> healthThreshold = sgHealth.add(new IntSetting.Builder()
            .name("生命值阈值")
            .description("当生命值低于或等于此值时发送指令。设置为 0 禁用生命值监控。")
            .defaultValue(0)
            .range(0, 19)
            .sliderMax(19)
            .build()
    );

    private final Setting<Boolean> smart = sgHealth.add(new BoolSetting.Builder()
            .name("智能预测")
            .description("检测到即将受到足够伤害使生命值低于设定阈值时提前发送指令。")
            .defaultValue(true)
            .visible(() -> healthThreshold.get() > 0)
            .build()
    );

    private final Setting<String> healthCommand = sgHealth.add(new StringSetting.Builder()
            .name("生命值指令")
            .description("生命值低于阈值时发送的指令（写不写 \"/\" 均可，留空则不发送）。")
            .defaultValue("")
            .visible(() -> healthThreshold.get() > 0)
            .build()
    );

    // ---------- 图腾监控 ----------

    private final Setting<Boolean> totemCheck = sgTotem.add(new BoolSetting.Builder()
            .name("图腾检查")
            .description("启用图腾数量检查，当图腾数量低于或等于设定值时发送指令。")
            .defaultValue(false)
            .build()
    );

    private final Setting<Integer> totemCount = sgTotem.add(new IntSetting.Builder()
            .name("最少图腾数量")
            .description("当背包（含副手）中的图腾总数低于或等于此值时发送指令。")
            .defaultValue(3)
            .range(0, 37)
            .sliderRange(0, 37)
            .visible(totemCheck::get)
            .build()
    );

    private final Setting<String> totemCommand = sgTotem.add(new StringSetting.Builder()
            .name("图腾指令")
            .description("图腾数量低于阈值时发送的指令（写不写 \"/\" 均可，留空则不发送）。")
            .defaultValue("")
            .visible(totemCheck::get)
            .build()
    );

    // ---------- Y轴监控 ----------

    private final Setting<Boolean> yCheck = sgYAxis.add(new BoolSetting.Builder()
            .name("Y轴检查")
            .description("启用 Y 轴坐标检查，当 Y 坐标低于或等于设定值时发送指令。")
            .defaultValue(false)
            .build()
    );

    private final Setting<Integer> yThreshold = sgYAxis.add(new IntSetting.Builder()
            .name("Y轴阈值")
            .description("当 Y 坐标低于或等于此值时发送指令。")
            .defaultValue(0)
            .range(-64, 320)
            .sliderRange(-64, 320)
            .visible(yCheck::get)
            .build()
    );

    private final Setting<String> yCommand = sgYAxis.add(new StringSetting.Builder()
            .name("Y轴指令")
            .description("Y 坐标低于阈值时发送的指令（写不写 \"/\" 均可，留空则不发送）。")
            .defaultValue("")
            .visible(yCheck::get)
            .build()
    );

    // ---------- 运行时状态 ----------

    /** 边缘触发武装标记：数值恢复到阈值以上才重新置 true。 */
    private boolean healthArmed = true;
    private boolean totemArmed = true;
    private boolean yArmed = true;

    /** 各监控项最近一次触发时间戳（毫秒），用于冷却限制。 */
    private long lastHealthTriggerMs;
    private long lastTotemTriggerMs;
    private long lastYTriggerMs;

    public AutoCommandModule() {
        super(QueueNoticeAddon.CATEGORY, "自动指令", "生命值/图腾/Y轴低于阈值时自动在聊天栏发送指定指令。");
    }

    @Override
    public void onActivate() {
        healthArmed = true;
        totemArmed = true;
        yArmed = true;
        lastHealthTriggerMs = 0;
        lastTotemTriggerMs = 0;
        lastYTriggerMs = 0;
    }

    // ---------- 事件监听 ----------

    @SuppressWarnings("unused")
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || isNotOnTargetServer()) {
            return;
        }
        // 死亡期间暂停检查：生命值归零不属于有效触发场景，指令此时发送也无意义
        if (mc.player.isDead() || mc.player.getHealth() <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        checkHealth(now);
        checkTotem(now);
        checkYAxis(now);
    }

    // ---------- 监控逻辑 ----------

    /**
     * 生命值检查：有效生命值 ≤ 阈值触发。
     *
     * <p>开启「智能预测」时，有效生命值取 当前生命值 与
     * （生命值 + 伤害吸收 - 潜在伤害）的较小值，把水晶/床等即将爆发的伤害提前计入。</p>
     */
    private void checkHealth(long now) {
        int threshold = healthThreshold.get();
        if (threshold <= 0) {
            return;
        }

        float effective = mc.player.getHealth();
        if (smart.get()) {
            try {
                float predicted = mc.player.getHealth() + mc.player.getAbsorptionAmount()
                        - PlayerUtils.possibleHealthReductions();
                effective = Math.min(effective, predicted);
            } catch (Throwable ignored) {
                // 潜在伤害计算不可用时退化为仅当前生命值
            }
        }

        if (effective <= threshold) {
            if (healthArmed && now - lastHealthTriggerMs >= cooldownMs()) {
                healthArmed = false;
                lastHealthTriggerMs = now;
                sendCommand(healthCommand.get(),
                        String.format("生命值 %.1f ≤ 阈值 %d", effective, threshold));
            }
        } else {
            healthArmed = true;
        }
    }

    /** 图腾检查：背包（含快捷栏、护甲栏与副手）图腾总数 ≤ 阈值触发。 */
    private void checkTotem(long now) {
        if (!totemCheck.get()) {
            return;
        }

        int totems = countTotems();
        int threshold = totemCount.get();
        if (totems <= threshold) {
            if (totemArmed && now - lastTotemTriggerMs >= cooldownMs()) {
                totemArmed = false;
                lastTotemTriggerMs = now;
                sendCommand(totemCommand.get(),
                        "图腾数量 " + totems + " ≤ 阈值 " + threshold);
            }
        } else {
            totemArmed = true;
        }
    }

    /** Y轴检查：Y 坐标 ≤ 阈值触发。 */
    private void checkYAxis(long now) {
        if (!yCheck.get()) {
            return;
        }

        double y = mc.player.getY();
        int threshold = yThreshold.get();
        if (y <= threshold) {
            if (yArmed && now - lastYTriggerMs >= cooldownMs()) {
                yArmed = false;
                lastYTriggerMs = now;
                sendCommand(yCommand.get(),
                        String.format("Y坐标 %.1f ≤ 阈值 %d", y, threshold));
            }
        } else {
            yArmed = true;
        }
    }

    // ---------- 工具方法 ----------

    /**
     * 发送指令到聊天栏（参考 LeavesHack AutoLogin 的命令发送机制）。
     *
     * <p>自动剔除前导 "/" 与空白后发送 {@link CommandExecutionC2SPacket}；
     * 指令为空时仅警告不发送（不误触发裸包）。</p>
     *
     * @param rawCommand 用户配置的原始指令
     * @param reason     触发原因描述（用于本地聊天反馈）
     */
    private void sendCommand(String rawCommand, String reason) {
        String command = normalizeCommand(rawCommand);
        if (command.isEmpty()) {
            warning("触发自动指令（" + reason + "），但未配置对应指令，已跳过发送。");
            return;
        }
        if (mc.getNetworkHandler() == null) {
            return;
        }
        mc.getNetworkHandler().sendPacket(new CommandExecutionC2SPacket(command));
        info("已发送指令 /" + command + "（" + reason + "）");
    }

    /** 规范化指令：去除首尾空白与前导 "/"（可多个）。 */
    private String normalizeCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String command = raw.trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        return command;
    }

    /** 统计背包中不死图腾总数。PlayerInventory.size() 已包含主背包 + 快捷栏 + 护甲 + 副手。 */
    private int countTotems() {
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private long cooldownMs() {
        return cooldown.get() * 1000L;
    }

    /** 服务器白名单校验已迁移至「全局设置」模块，此处委托全局配置。模块缺失时保守判定为不在目标服务器。 */
    private boolean isNotOnTargetServer() {
        GlobalSettingsModule global = GlobalSettingsModule.get();
        return global == null || global.isNotOnTargetServer();
    }
}
