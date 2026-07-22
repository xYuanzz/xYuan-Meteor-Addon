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
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 图腾提醒模块：监控本地玩家不死图腾触发与玩家死亡，通过飞书 Webhook 推送提醒。
 *
 * <p>图腾触发附带伤害来源（伤害类型键名 + 来源实体名）与背包剩余图腾数量；
 * 死亡携带游戏返回的死亡信息与触发伤害。不做防重复去抖，避免漏掉关键信息。</p>
 *
 * <p><b>3c3u 死因回退</b>：3c3u 服务端把 {@link DeathMessageS2CPacket#message()} 清空，
 * 模块按优先级回退获取死因文本与触发伤害：
 * <ol>
 *   <li>正常服务器：直接用 {@code DeathMessageS2CPacket.message().getString()}</li>
 *   <li>方案 B：取最近 1 秒内本地玩家死亡广播 {@link GameMessageS2CPacket} 的 content
 *       （聊天栏 death.* 翻译消息，含完整死因文本）</li>
 *   <li>方案 A：取最近 {@link #DAMAGE_WINDOW_MS} 内 {@link EntityDamageS2CPacket}
 *       缓存的伤害类型键名（如 fall，去掉 minecraft: 前缀）构造简化描述</li>
 *   <li>兜底「已死亡」</li>
 * </ol>
 * 触发伤害字段统一从 {@link EntityDamageS2CPacket} 缓存读取伤害类型键名（去掉 minecraft: 前缀）。</p>
 */
public class TotemNoticeModule extends Module {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 实体状态码 35 = 不死图腾触发（vanilla 协议约定）。 */
    private static final byte TOTEM_STATUS = 35;

    /** 伤害缓存有效期（毫秒）：图腾触发与死亡时仅读取此窗口内的伤害，避免误用旧伤害。 */
    private static final long DAMAGE_WINDOW_MS = 500;

    /** 死亡广播缓存有效期（毫秒）：DeathMessageS2CPacket 到达时关联最近的 GameMessageS2CPacket 死亡广播。 */
    private static final long DEATH_BROADCAST_WINDOW_MS = 1000;

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

    /** 缓存最近一次本地玩家受到的伤害（供图腾触发与死亡时读取伤害来源）。 */
    private RegistryEntry<DamageType> lastDamageType;
    private int lastDamageCauseId = -1;
    private long lastDamageTimeMs;

    /** 缓存最近一次本地玩家的死亡广播（聊天栏 death.* 翻译消息），供 DeathMessageS2CPacket.message 为空时回退使用。 */
    private Text lastDeathBroadcastText;
    private long lastDeathBroadcastTimeMs;

    public TotemNoticeModule() {
        super(QueueNoticeAddon.CATEGORY, "图腾提醒", "监控不死图腾触发与玩家死亡并通过飞书 Webhook 推送提醒，附带剩余图腾数量与死亡信息。");
    }

    @Override
    public void onActivate() {
        playerName = null;
        lastDamageType = null;
        lastDamageCauseId = -1;
        lastDamageTimeMs = 0;
        lastDeathBroadcastText = null;
        lastDeathBroadcastTimeMs = 0;
        capturePlayerName();
    }

    /**
     * 拦截数据包：死亡（DeathMessageS2CPacket）、伤害（EntityDamageS2CPacket）、
     * 死亡广播（GameMessageS2CPacket death.*）、图腾触发（EntityStatusS2CPacket 状态码 35）。
     *
     * <p>vanilla 受伤流程中伤害包先于图腾包到达，本模块缓存最近一次伤害供图腾触发与死亡时读取。
     * 3c3u 死亡时聊天广播先于死亡屏幕包到达，本模块缓存最近的本地玩家死亡广播供 message 为空时回退。</p>
     */
    @SuppressWarnings("unused")
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        // 玩家死亡（死亡屏幕包）：优先处理（独立于图腾触发）
        if (event.packet instanceof DeathMessageS2CPacket deathPacket) {
            handleDeathMessage(deathPacket);
            return;
        }

        // 伤害包：缓存最近一次本地玩家受到的伤害（供图腾触发 + 死亡回退读取伤害来源）
        if (event.packet instanceof EntityDamageS2CPacket dmgPacket) {
            cacheDamageIfLocalPlayer(dmgPacket);
            return;
        }

        // 死亡广播消息：缓存（方案 B，3c3u 清空 DeathMessageS2CPacket.message 时回退使用）
        if (event.packet instanceof GameMessageS2CPacket msgPacket) {
            cacheDeathBroadcastIfLocalPlayer(msgPacket);
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
     * 缓存本地玩家的死亡广播（聊天栏 death.* 翻译消息）。
     *
     * <p>3c3u 服务端把 {@link DeathMessageS2CPacket#message()} 清空，但聊天栏死亡广播照常发送，
     * 通过监听 {@link GameMessageS2CPacket} 缓存死亡文本，供死亡屏幕包 message 为空时回退使用（方案 B）。</p>
     *
     * <p>双重过滤：①content 是翻译文本且 key 以 "death." 开头（排除普通聊天 / 系统消息）；
     * ②文本含本地玩家名（排除其他玩家的死亡广播）。死亡广播先于死亡屏幕包到达，缓存时 mc.player 仍在。</p>
     */
    private void cacheDeathBroadcastIfLocalPlayer(GameMessageS2CPacket packet) {
        Text content = packet.content();
        if (!(content.getContent() instanceof TranslatableTextContent ttc)) {
            return;
        }
        String key = ttc.getKey();
        if (key == null || !key.startsWith("death.")) {
            return;
        }
        capturePlayerName();
        if (playerName == null || playerName.isEmpty()) {
            return;
        }
        String text = content.getString();
        if (!text.contains(playerName)) {
            return;
        }
        lastDeathBroadcastText = content;
        lastDeathBroadcastTimeMs = System.currentTimeMillis();
    }

    /**
     * 处理玩家死亡数据包：仅处理本地玩家的死亡，推送携带死亡文本与触发伤害的提醒。
     *
     * <p>关键：{@code mc.player} 为 null 时仍发送死亡提醒。图腾触发后极短时间内被杀死时，
     * 客户端可能处于过渡状态导致 {@code mc.player} 被清空，而 {@link DeathMessageS2CPacket}
     * 是服务端专门发给当前客户端的死亡通知，{@code playerId()} 就是本地玩家，不应丢弃。
     * 仅在 {@code mc.player} 存在时校验 {@code playerId} 防止其他玩家死亡误报。</p>
     *
     * <p>死亡文本优先级：①DeathMessageS2CPacket.message（正常服务器，message 非空时直接用）；
     * ②最近 1 秒内本地玩家死亡广播 GameMessageS2CPacket 缓存（方案 B，3c3u 清空 message 时回退）；
     * ③用最近伤害缓存的伤害类型完整键名构造简化描述（方案 A）；④兜底「已死亡」。
     * 触发伤害字段统一从最近伤害缓存读取完整键名 + 来源实体名。</p>
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

        // 死亡文本：先走正常路径（message），为空再走 3c3u 兜底逻辑（方案 B → 方案 A → 兜底）
        String deathText = packet.message().getString();
        if (deathText == null || deathText.isEmpty()) {
            deathText = resolveDeathTextFromCache();
        }
        // 触发伤害：从最近伤害缓存读取完整键名 + 来源实体名
        String damageDesc = buildDamageDescription();
        sendDeathReminder(deathText, damageDesc);
    }

    /** 死亡文本回退：先取死亡广播缓存（方案 B），再取伤害缓存构造简单描述（方案 A），最后兜底「已死亡」。 */
    private String resolveDeathTextFromCache() {
        // 方案 B：从 GameMessageS2CPacket 缓存取死亡广播文本（含完整死因描述）
        if (lastDeathBroadcastText != null
                && System.currentTimeMillis() - lastDeathBroadcastTimeMs <= DEATH_BROADCAST_WINDOW_MS) {
            String text = lastDeathBroadcastText.getString();
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        // 方案 A：从 EntityDamageS2CPacket 缓存取伤害类型键名，构造简化描述
        String damageKey = getDamageKeyFromCache();
        if (damageKey != null) {
            return "已死亡(" + damageKey + ")";
        }
        return "已死亡";
    }

    /** 从最近伤害缓存读取伤害类型键名（DAMAGE_WINDOW_MS 时间窗内），无缓存或已过期返回 null。 */
    private String getDamageKeyFromCache() {
        if (lastDamageType == null || lastDamageTimeMs == 0
                || System.currentTimeMillis() - lastDamageTimeMs > DAMAGE_WINDOW_MS) {
            return null;
        }
        return getDamageKey(lastDamageType);
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
     * 构造伤害来源描述（图腾触发与死亡共用）。
     *
     * <p>格式：有来源实体时为「伤害类型键名(来源实体名)」；环境伤害仅显示键名；
     * 无缓存或已过期显示「未知」。键名形如 fall / drown / mob_attack（去掉 minecraft: 命名空间前缀）。</p>
     */
    private String buildDamageDescription() {
        if (lastDamageType == null || lastDamageTimeMs == 0
                || System.currentTimeMillis() - lastDamageTimeMs > DAMAGE_WINDOW_MS) {
            return "未知";
        }

        String damageKey = getDamageKey(lastDamageType);
        if (damageKey == null || damageKey.isEmpty()) {
            damageKey = "未知";
        }

        if (lastDamageCauseId == -1 || mc.world == null) {
            return damageKey;
        }
        try {
            Entity cause = mc.world.getEntityById(lastDamageCauseId);
            if (cause != null) {
                String name = cause.getName().getString();
                if (name != null && !name.isEmpty()) {
                    return damageKey + "(" + name + ")";
                }
            }
        } catch (Throwable ignored) {
        }
        return damageKey;
    }

    /**
     * 获取伤害类型键名（去掉 minecraft: 命名空间前缀）。
     *
     * <p>优先走 {@link RegistryEntry#getKey()} 拿 {@link RegistryKey}，再取 {@code Identifier.toString()}
     * 并 strip minecraft: 前缀。回退用 {@code DamageType.msgId()}（本身已是不带前缀的短名）。</p>
     */
    private String getDamageKey(RegistryEntry<DamageType> entry) {
        try {
            Optional<RegistryKey<DamageType>> keyOpt = entry.getKey();
            if (keyOpt.isPresent()) {
                return stripMinecraftNamespace(keyOpt.get().getValue().toString());
            }
        } catch (Throwable ignored) {
        }
        try {
            return entry.value().msgId();
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    /** 去掉 minecraft: 命名空间前缀（如 minecraft:fall → fall），其他 namespace 保留。 */
    private String stripMinecraftNamespace(String fullKey) {
        if (fullKey != null && fullKey.startsWith("minecraft:")) {
            return fullKey.substring("minecraft:".length());
        }
        return fullKey;
    }

    /** 构造并发送玩家死亡提醒。第一行为游戏返回的死亡信息（红色加粗）。 */
    private void sendDeathReminder(String deathMessage, String damageDescription) {
        FeishuWebhookModule webhook = Modules.get().get(FeishuWebhookModule.class);
        if (webhook != null) {
            webhook.sendMarkdown(buildDeathMarkdown(deathMessage, damageDescription));
        }
    }

    private String buildDeathMarkdown(String deathMessage, String damageDescription) {
        capturePlayerName();
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String player = (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
        String msg = (deathMessage == null || deathMessage.isEmpty()) ? "已死亡" : deathMessage;
        String damage = (damageDescription == null || damageDescription.isEmpty()) ? "未知" : damageDescription;

        return "<font color=\"red\">**" + msg + "**</font>\n"
                + "玩家:" + player + "\n"
                + "触发伤害:" + damage + "\n"
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
