package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 图腾提醒模块：监控本地玩家不死图腾触发与玩家死亡，通过飞书 Webhook 推送提醒。
 *
 * <p>图腾触发附带伤害来源（类型 ID + 来源实体名）与背包剩余图腾数量；
 * 死亡携带游戏返回的死亡信息。不做防重复去抖，避免漏掉关键信息。</p>
 */
public class TotemNoticeModule extends Module {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 实体状态码 35 = 不死图腾触发（vanilla 协议约定）。 */
    private static final byte TOTEM_STATUS = 35;

    /** 伤害缓存有效期（毫秒）：图腾触发时仅读取此窗口内的伤害，避免误用旧伤害。 */
    private static final long DAMAGE_WINDOW_MS = 500;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("高级");

    private final Setting<Boolean> urgentWhenEmpty = sgGeneral.add(new BoolSetting.Builder()
            .name("耗尽时紧急提醒")
            .description("剩余图腾为 0 时整行加粗红色并追加「图腾已耗尽-」前缀，便于快速补图腾。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> enableDeathNotify = sgGeneral.add(new BoolSetting.Builder()
            .name("启用死亡提醒")
            .description("玩家死亡时推送提醒，携带游戏返回的死亡信息（如「被xxx杀死了」）。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> skipServerCheck = sgAdvanced.add(new BoolSetting.Builder()
            .name("不校验服务器地址")
            .description("跳过 3c3u.org 白名单校验，允许在任意服务器触发图腾提醒。")
            .defaultValue(false)
            .build()
    );

    // ---------- 运行时状态 ----------

    private String playerName;

    /** 缓存最近一次本地玩家受到的伤害（供图腾触发时读取伤害来源）。 */
    private RegistryEntry<DamageType> lastDamageType;
    private int lastDamageCauseId = -1;
    private long lastDamageTimeMs;

    public TotemNoticeModule() {
        super(QueueNoticeAddon.CATEGORY, "图腾提醒", "监控不死图腾触发与玩家死亡并通过飞书 Webhook 推送提醒，附带剩余图腾数量与死亡信息。");
    }

    @Override
    public void onActivate() {
        playerName = null;
        lastDamageType = null;
        lastDamageCauseId = -1;
        lastDamageTimeMs = 0;
        capturePlayerName();
    }

    /**
     * 拦截数据包：死亡（DeathMessageS2CPacket）、伤害（EntityDamageS2CPacket）、
     * 图腾触发（EntityStatusS2CPacket 状态码 35）。
     *
     * <p>vanilla 受伤流程中伤害包先于图腾包到达，本模块缓存最近一次伤害供图腾触发时读取。</p>
     */
    @SuppressWarnings("unused")
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        // 玩家死亡：优先处理（独立于图腾触发）
        if (event.packet instanceof DeathMessageS2CPacket deathPacket) {
            handleDeathMessage(deathPacket);
            return;
        }

        // 伤害包：缓存最近一次本地玩家受到的伤害
        if (event.packet instanceof EntityDamageS2CPacket dmgPacket) {
            cacheDamageIfLocalPlayer(dmgPacket);
            return;
        }

        if (!(event.packet instanceof EntityStatusS2CPacket packet)) {
            return;
        }
        if (packet.getStatus() != TOTEM_STATUS) {
            return;
        }
        if (mc.world == null || mc.player == null) {
            return;
        }

        // 校验目标实体是否为本地玩家（其他玩家爆图腾不通知）
        Entity entity = packet.getEntity(mc.world);
        if (entity != mc.player) {
            return;
        }
        if (isNotOnTargetServer()) {
            return;
        }

        capturePlayerName();
        int remaining = countTotemsAfterConsumption();
        String damageDesc = buildDamageDescription();
        sendTotemReminder(remaining, damageDesc);
    }

    /** 缓存针对本地玩家的伤害：记录伤害类型、来源实体 ID 与时间戳。 */
    private void cacheDamageIfLocalPlayer(EntityDamageS2CPacket packet) {
        if (mc.player == null || packet.entityId() != mc.player.getId()) {
            return;
        }
        lastDamageType = packet.sourceType();
        lastDamageCauseId = packet.sourceCauseId();
        lastDamageTimeMs = System.currentTimeMillis();
    }

    /**
     * 处理玩家死亡数据包：仅处理本地玩家的死亡，推送携带死亡信息的提醒。
     *
     * <p>关键：{@code mc.player} 为 null 时仍发送死亡提醒。图腾触发后极短时间内被杀死时，
     * 客户端可能处于过渡状态导致 {@code mc.player} 被清空，而 {@link DeathMessageS2CPacket}
     * 是服务端专门发给当前客户端的死亡通知，{@code playerId()} 就是本地玩家，不应丢弃。
     * 仅在 {@code mc.player} 存在时校验 {@code playerId} 防止其他玩家死亡误报。</p>
     */
    private void handleDeathMessage(DeathMessageS2CPacket packet) {
        if (!enableDeathNotify.get()) {
            return;
        }
        if (mc.player != null && packet.playerId() != mc.player.getId()) {
            return;
        }
        if (isNotOnTargetServer()) {
            return;
        }
        capturePlayerName();
        sendDeathReminder(packet.message().getString());
    }

    /**
     * 统计触发图腾后背包剩余的不死图腾数量。
     *
     * <p>PlayerInventory.size() 已包含主背包 + 快捷栏 + 护甲 + 副手。
     * 由于服务端消耗图腾与客户端背包同步之间存在网络延迟，按 vanilla 检查顺序
     * （副手优先 → 主手）扣除被消耗的那一个图腾，确保计数正确。</p>
     */
    private int countTotemsAfterConsumption() {
        if (mc.player == null) {
            return 0;
        }

        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) {
                count += stack.getCount();
            }
        }

        // 扣除本次触发消耗的那一个图腾（vanilla 检查顺序：副手优先 → 主手）
        ItemStack offHand = mc.player.getOffHandStack();
        if (offHand.getItem() == Items.TOTEM_OF_UNDYING) {
            count -= 1;
        } else {
            ItemStack mainHand = mc.player.getMainHandStack();
            if (mainHand.getItem() == Items.TOTEM_OF_UNDYING) {
                count -= 1;
            }
        }

        return Math.max(0, count);
    }

    /** 构造并发送图腾触发提醒。 */
    private void sendTotemReminder(int remaining, String damageDescription) {
        FeishuWebhookModule webhook = Modules.get().get(FeishuWebhookModule.class);
        if (webhook != null) {
            webhook.sendMarkdown(buildMarkdown(remaining, damageDescription));
        }
    }

    /** 构造图腾提醒 Markdown 内容。紧急提醒（剩余 0）时整行加粗红色并追加「图腾已耗尽-」前缀。 */
    private String buildMarkdown(int remaining, String damageDescription) {
        capturePlayerName();
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String player = (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
        boolean urgent = (remaining == 0) && urgentWhenEmpty.get();

        StringBuilder sb = new StringBuilder();
        if (urgent) {
            sb.append("<font color=\"red\">**图腾已耗尽-[警告]图腾已被触发**</font>\n");
        } else {
            sb.append("<font color=\"red\">**[警告]图腾已被触发**</font>\n");
        }
        sb.append("玩家:").append(player).append("\n");
        sb.append("触发伤害:").append(damageDescription).append("\n");
        if (remaining == 0) {
            sb.append("剩余图腾:").append("<font color=\"red\">**").append(remaining).append("**</font>\n");
        } else {
            sb.append("剩余图腾:").append(remaining).append("\n");
        }
        sb.append("触发时间:").append(time);
        return sb.toString();
    }

    /**
     * 构造图腾触发的伤害来源描述。
     *
     * <p>格式：有来源实体时为「伤害类型ID(来源实体名)」；环境伤害仅显示类型 ID；
     * 无缓存或已过期显示「未知伤害」。yarn 映射中 DamageType 的字段为 msgId。</p>
     */
    private String buildDamageDescription() {
        if (lastDamageType == null || lastDamageTimeMs == 0
                || System.currentTimeMillis() - lastDamageTimeMs > DAMAGE_WINDOW_MS) {
            return "未知伤害";
        }

        String typeId;
        try {
            typeId = lastDamageType.value().msgId();
        } catch (Throwable ignored) {
            typeId = "unknown";
        }
        if (typeId == null || typeId.isEmpty()) {
            typeId = "unknown";
        }

        if (lastDamageCauseId == -1 || mc.world == null) {
            return typeId;
        }
        try {
            Entity cause = mc.world.getEntityById(lastDamageCauseId);
            if (cause != null) {
                String name = cause.getName().getString();
                if (name != null && !name.isEmpty()) {
                    return typeId + "(" + name + ")";
                }
            }
        } catch (Throwable ignored) {
        }
        return typeId;
    }

    /** 构造并发送玩家死亡提醒。第一行为游戏返回的死亡信息（红色加粗）。 */
    private void sendDeathReminder(String deathMessage) {
        FeishuWebhookModule webhook = Modules.get().get(FeishuWebhookModule.class);
        if (webhook != null) {
            webhook.sendMarkdown(buildDeathMarkdown(deathMessage));
        }
    }

    private String buildDeathMarkdown(String deathMessage) {
        capturePlayerName();
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String player = (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
        String msg = (deathMessage == null || deathMessage.isEmpty()) ? "已死亡" : deathMessage;

        return "<font color=\"red\">**" + msg + "**</font>\n"
                + "玩家:" + player + "\n"
                + "触发时间:" + time;
    }

    private void capturePlayerName() {
        if ((playerName == null || playerName.isEmpty()) && mc.player != null) {
            try {
                playerName = mc.player.getName().getString();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 启用「不校验服务器地址」时返回 false；否则按地址不包含 3c3u.org 判定。 */
    private boolean isNotOnTargetServer() {
        if (skipServerCheck.get()) {
            return false;
        }
        if (mc.getCurrentServerEntry() == null) {
            return true;
        }
        String address = mc.getCurrentServerEntry().address;
        return address == null || !address.toLowerCase().contains("3c3u.org");
    }
}
