package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import cn.anyho.xyuan.util.PlayerRadarHistory;
import cn.anyho.xyuan.util.ThreatLevelCalculator;
import cn.anyho.xyuan.util.ThreatLevelCalculator.ThreatResult;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 附近玩家预警模块：扫描附近陌生玩家并通过飞书 Webhook 推送提醒。
 *
 * <p>双轨通知机制：
 * <ul>
 *   <li>事件轨：玩家进入/离开视野即时推送（基于上一 tick 玩家集合 diff）</li>
 *   <li>定时轨：按可配周期推送当前陌生人完整名单快照</li>
 * </ul>
 *
 * <p>黑名单过滤：仅非好友、不在自定义白名单、非创造/旁观/隐形（按设置）的玩家触发通知。
 * 威胁等级按 /workspace/threat-level-spec.txt 规范计算。
 * 所有文件 I/O 在守护线程池异步执行，主线程零阻塞。</p>
 */
public class PlayerRadarModule extends Module {

    /** 威胁等级梯队枚举，重写 toString 返回中文。 */
    public enum ThreatTier {
        F,
        D,
        C,
        B,
        A,
        S;

        /** 梯队序号（越大威胁越高），用于过滤比较。 */
        public int rank() {
            return ordinal();
        }

        @Override
        public String toString() {
            return switch (this) {
                case F -> "F(全部)";
                case D -> "D及以上";
                case C -> "C及以上";
                case B -> "B及以上";
                case A -> "A及以上";
                case S -> "仅S";
            };
        }
    }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 默认历史记录文件路径（相对游戏根目录）。 */
    private static final String DEFAULT_HISTORY_FILE = "logs/player-radar-history.log";

    /** 每条通知最多显示的玩家数（防止超出飞书 Webhook 消息大小限制）。 */
    private static final int MAX_DISPLAY_PLAYERS = 10;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgNotify = settings.createGroup("通知类型");
    private final SettingGroup sgFilter = settings.createGroup("过滤规则");
    private final SettingGroup sgThreat = settings.createGroup("威胁等级");
    private final SettingGroup sgHistory = settings.createGroup("历史记录");

    // ---------- 基础 ----------

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
            .name("检测半径")
            .description("检测附近玩家的最大距离（方块/米）。超出此距离的玩家被忽略。")
            .defaultValue(64)
            .range(16, 512)
            .sliderRange(16, 256)
            .build()
    );

    private final Setting<Integer> pollPeriod = sgGeneral.add(new IntSetting.Builder()
            .name("轮询周期")
            .description("定时快照推送周期（秒）。仅「启用定时快照」开启时生效。")
            .defaultValue(2)
            .range(1, 60)
            .sliderRange(1, 30)
            .build()
    );

    private final Setting<Boolean> skipServerCheck = sgGeneral.add(new BoolSetting.Builder()
            .name("不校验服务器地址")
            .description("跳过 3c3u.org 白名单校验，允许在任意服务器触发预警。")
            .defaultValue(false)
            .build()
    );

    // ---------- 通知类型 ----------

    private final Setting<Boolean> enableEnterNotify = sgNotify.add(new BoolSetting.Builder()
            .name("启用进入通知")
            .description("玩家进入视野时即时推送一条通知。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> enableLeaveNotify = sgNotify.add(new BoolSetting.Builder()
            .name("启用离开通知")
            .description("玩家离开视野时即时推送一条通知，含持续时长。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> enableSnapshot = sgNotify.add(new BoolSetting.Builder()
            .name("启用定时快照")
            .description("按「轮询周期」周期性推送当前附近陌生人完整名单。")
            .defaultValue(true)
            .build()
    );

    // ---------- 过滤规则 ----------

    private final Setting<Boolean> ignoreFriends = sgFilter.add(new BoolSetting.Builder()
            .name("忽略 Meteor 好友")
            .description("Meteor Client 好友列表中的玩家不触发通知。")
            .defaultValue(true)
            .build()
    );

    private final Setting<String> whitelist = sgFilter.add(new StringSetting.Builder()
            .name("自定义白名单")
            .description("不触发通知的玩家名列表，逗号分隔（如 玩家1,玩家2）。不区分大小写。")
            .defaultValue("")
            .wide()
            .build()
    );

    private final Setting<Boolean> ignoreCreative = sgFilter.add(new BoolSetting.Builder()
            .name("忽略创造模式玩家")
            .description("创造模式玩家不触发通知。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> ignoreSpectator = sgFilter.add(new BoolSetting.Builder()
            .name("忽略旁观模式玩家")
            .description("旁观模式玩家不触发通知。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> ignoreInvisible = sgFilter.add(new BoolSetting.Builder()
            .name("忽略隐形玩家")
            .description("隐形玩家不触发通知。")
            .defaultValue(false)
            .build()
    );

    // ---------- 威胁等级 ----------

    private final Setting<Boolean> showThreat = sgThreat.add(new BoolSetting.Builder()
            .name("显示威胁等级")
            .description("在通知中显示威胁等级与胸甲名。关闭时仅显示玩家名与距离。")
            .defaultValue(true)
            .build()
    );

    private final Setting<ThreatTier> minNotifyTier = sgThreat.add(new EnumSetting.Builder<ThreatTier>()
            .name("最低通知等级")
            .description("仅推送威胁等级 ≥ 此值的玩家。F 表示全部通知。")
            .defaultValue(ThreatTier.F)
            .build()
    );

    // ---------- 历史记录 ----------

    private final Setting<Boolean> enableHistory = sgHistory.add(new BoolSetting.Builder()
            .name("启用历史记录")
            .description("将进入/离开事件异步写入本地文件，含完整装备与附魔详情。")
            .defaultValue(false)
            .build()
    );

    private final Setting<String> historyFile = sgHistory.add(new StringSetting.Builder()
            .name("历史记录文件路径")
            .description("历史记录文件路径（相对游戏根目录）。")
            .defaultValue(DEFAULT_HISTORY_FILE)
            .wide()
            .visible(enableHistory::get)
            .build()
    );

    private final Setting<Boolean> clearHistoryOnActivate = sgHistory.add(new BoolSetting.Builder()
            .name("启用时清空历史")
            .description("模块启用时清空历史记录文件。")
            .defaultValue(false)
            .visible(enableHistory::get)
            .build()
    );

    // ---------- 运行时状态 ----------

    private String playerName;
    /** 上一 tick 的附近陌生人 UUID 集合，用于 diff。 */
    private final Set<UUID> lastNearbyStrangers = new HashSet<>();
    /** 进入时缓存的威胁评估结果，供离开通知复用。 */
    private final Map<UUID, ThreatResult> cachedThreats = new HashMap<>();
    /** 进入时间戳（毫秒），用于计算持续时长。 */
    private final Map<UUID, Long> enterTimeMs = new HashMap<>();
    /** 上一 tick 缓存的玩家名与最后距离，供离开通知使用（实体已不在时仍可读名）。 */
    private final Map<UUID, String> cachedNames = new HashMap<>();
    private final Map<UUID, Double> cachedLastDistance = new HashMap<>();
    private long lastPollMs;

    private PlayerRadarHistory history;

    public PlayerRadarModule() {
        super(QueueNoticeAddon.CATEGORY, "附近玩家预警", "扫描附近陌生玩家并通过飞书 Webhook 推送提醒，含威胁等级评估与历史记录。");
    }

    @Override
    public void onActivate() {
        lastNearbyStrangers.clear();
        cachedThreats.clear();
        enterTimeMs.clear();
        cachedNames.clear();
        cachedLastDistance.clear();
        lastPollMs = 0;
        playerName = null;
        capturePlayerName();

        if (enableHistory.get()) {
            history = new PlayerRadarHistory(historyFile.get(), clearHistoryOnActivate.get());
            history.open();
        } else {
            history = null;
        }
    }

    @Override
    public void onDeactivate() {
        if (history != null) {
            history.shutdown();
            history = null;
        }
        lastNearbyStrangers.clear();
        cachedThreats.clear();
        enterTimeMs.clear();
        cachedNames.clear();
        cachedLastDistance.clear();
    }

    // ---------- 事件监听 ----------

    @SuppressWarnings("unused")
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || isNotOnTargetServer()) {
            return;
        }

        // 扫描当前附近陌生人
        Set<UUID> currentStrangers = new HashSet<>();
        Map<UUID, PlayerEntity> strangerEntities = new HashMap<>();
        Map<UUID, Double> currentDistances = new HashMap<>();
        Map<UUID, String> currentNames = new HashMap<>();

        try {
            List<? extends PlayerEntity> players = mc.world.getPlayers();
            int maxRadius = radius.get();
            double maxRadiusSq = (double) maxRadius * maxRadius;

            for (PlayerEntity player : players) {
                if (player == mc.player) {
                    continue;
                }
                // 距离过滤（3D 欧氏距离平方，避免开方开销）
                double distSq = player.squaredDistanceTo(mc.player);
                if (distSq > maxRadiusSq) {
                    continue;
                }
                // 黑名单过滤
                if (isExempt(player)) {
                    continue;
                }
                UUID uuid = player.getUuid();
                currentStrangers.add(uuid);
                strangerEntities.put(uuid, player);
                currentDistances.put(uuid, Math.sqrt(distSq));
                currentNames.put(uuid, player.getName().getString());
            }
        } catch (Throwable t) {
            // 扫描阶段异常：记录日志但不中断后续缓存更新
            cn.anyho.xyuan.QueueNoticeAddon.LOG.warn("[xYuan's Mod] PlayerRadar scan error: {}", t.toString());
        }

        long now = System.currentTimeMillis();

        // ---------- 事件轨：diff ----------
        // 新进入
        Set<UUID> entered = new HashSet<>(currentStrangers);
        entered.removeAll(lastNearbyStrangers);

        // 离开
        Set<UUID> left = new HashSet<>(lastNearbyStrangers);
        left.removeAll(currentStrangers);

        if (!entered.isEmpty() && enableEnterNotify.get()) {
            List<StrangerInfo> infos = new ArrayList<>();
            for (UUID uuid : entered) {
                PlayerEntity entity = strangerEntities.get(uuid);
                if (entity == null) continue;
                // 威胁计算加 try/catch：异常时降级为 null，不中断 onTick
                ThreatResult threat = null;
                if (showThreat.get()) {
                    try {
                        threat = ThreatLevelCalculator.calculate(entity);
                    } catch (Throwable t) {
                        cn.anyho.xyuan.QueueNoticeAddon.LOG.warn("[xYuan's Mod] Threat calc error for {}: {}",
                                uuid, t.toString());
                    }
                }
                if (threat != null && tierFilteredOut(threat.tier())) {
                    // 未达最低通知等级：仍记录缓存但跳过通知
                    cachedThreats.put(uuid, threat);
                    enterTimeMs.put(uuid, now);
                    continue;
                }
                if (threat != null) {
                    cachedThreats.put(uuid, threat);
                }
                enterTimeMs.put(uuid, now);
                double distance = currentDistances.getOrDefault(uuid, 0.0);
                String name = currentNames.getOrDefault(uuid, "unknown");
                infos.add(new StrangerInfo(name, uuid, distance, threat));
                if (history != null && threat != null) {
                    try {
                        history.recordEnter(name, distance, entity.getHealth(), threat);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!infos.isEmpty()) {
                sendMarkdown(buildEnterMarkdown(infos));
            }
        }

        if (!left.isEmpty() && enableLeaveNotify.get()) {
            List<LeaveInfo> infos = new ArrayList<>();
            for (UUID uuid : left) {
                Long enterMs = enterTimeMs.remove(uuid);
                long durationSec = enterMs == null ? 0 : (now - enterMs) / 1000;
                ThreatResult cached = cachedThreats.remove(uuid);
                // 离开时实体已不在，从上一 tick 缓存读取玩家名与最后距离
                String name = cachedNames.getOrDefault(uuid, "unknown");
                double lastDist = cachedLastDistance.getOrDefault(uuid, 0.0);
                infos.add(new LeaveInfo(name, uuid, lastDist, durationSec, cached));
                if (history != null && cached != null) {
                    try {
                        history.recordLeave(name, lastDist, durationSec, 0f, cached);
                    } catch (Throwable ignored) {
                    }
                }
            }
            sendMarkdown(buildLeaveMarkdown(infos));
        }

        // ---------- 定时轨：快照 ----------
        if (enableSnapshot.get() && !currentStrangers.isEmpty()) {
            long periodMs = pollPeriod.get() * 1000L;
            if (now - lastPollMs >= periodMs) {
                lastPollMs = now;
                List<StrangerInfo> infos = new ArrayList<>();
                for (UUID uuid : currentStrangers) {
                    PlayerEntity entity = strangerEntities.get(uuid);
                    if (entity == null) continue;
                    // 威胁计算加 try/catch：异常时降级为 null，不中断 onTick（与进入通知一致）
                    ThreatResult threat = null;
                    if (showThreat.get()) {
                        try {
                            threat = ThreatLevelCalculator.calculate(entity);
                        } catch (Throwable t) {
                            cn.anyho.xyuan.QueueNoticeAddon.LOG.warn("[xYuan's Mod] Threat calc error for {}: {}",
                                    uuid, t.toString());
                        }
                    }
                    if (threat != null && tierFilteredOut(threat.tier())) {
                        continue;
                    }
                    double distance = currentDistances.getOrDefault(uuid, 0.0);
                    String name = currentNames.getOrDefault(uuid, "unknown");
                    infos.add(new StrangerInfo(name, uuid, distance, threat));
                }
                if (!infos.isEmpty()) {
                    sendMarkdown(buildSnapshotMarkdown(infos));
                }
            }
        } else if (currentStrangers.isEmpty()) {
            // 无陌生人时重置计时，避免下次有人时立即触发（等满一个周期）
            lastPollMs = now;
        }

        // 更新上一 tick 缓存
        lastNearbyStrangers.clear();
        lastNearbyStrangers.addAll(currentStrangers);
        cachedNames.clear();
        cachedNames.putAll(currentNames);
        cachedLastDistance.clear();
        cachedLastDistance.putAll(currentDistances);
    }

    // ---------- 过滤逻辑 ----------

    /** 判断玩家是否被豁免（不触发通知）。 */
    private boolean isExempt(PlayerEntity player) {
        // 游戏模式过滤
        if (ignoreCreative.get() && player.isCreative()) {
            return true;
        }
        if (ignoreSpectator.get() && player.isSpectator()) {
            return true;
        }
        // 隐形过滤
        if (ignoreInvisible.get() && player.isInvisible()) {
            return true;
        }
        // Meteor 好友过滤
        if (ignoreFriends.get()) {
            try {
                if (meteordevelopment.meteorclient.systems.friends.Friends.get().isFriend(player)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // API 不可用时跳过好友检查（白名单在下方统一检查）
            }
        }
        // 自定义白名单
        if (isInWhitelist(player.getName().getString())) {
            return true;
        }
        return false;
    }

    /** 判断玩家名是否在自定义白名单中（不区分大小写）。 */
    private boolean isInWhitelist(String name) {
        String list = whitelist.get();
        if (list == null || list.isBlank()) {
            return false;
        }
        String[] names = list.split(",");
        for (String n : names) {
            String trimmed = n.trim();
            if (!trimmed.isEmpty() && trimmed.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 判断威胁梯队是否低于最低通知等级设置（应被过滤掉）。 */
    private boolean tierFilteredOut(String tier) {
        ThreatTier min = minNotifyTier.get();
        if (min == ThreatTier.F) {
            return false;
        }
        try {
            ThreatTier playerTier = ThreatTier.valueOf(tier);
            return playerTier.rank() < min.rank();
        } catch (IllegalArgumentException ignored) {
            return false; // 未知梯队默认放行
        }
    }

    // ---------- Markdown 构造 ----------

    /** 单个陌生人信息。 */
    private record StrangerInfo(String name, UUID uuid, double distance, ThreatResult threat) {
    }

    /** 离开信息。 */
    private record LeaveInfo(String name, UUID uuid, double lastDistance, long durationSec, ThreatResult cachedThreat) {
    }

    /** 构造玩家行：name(距离米, 胸甲:xx, 威胁等级:xx)。 */
    private String formatPlayerLine(StrangerInfo info) {
        String line = info.name + "(" + String.format("%.1f", info.distance) + "米";
        if (info.threat != null) {
            line += ", 胸甲:" + info.threat.chestplateName();
            line += ", 威胁等级:" + info.threat.tier();
        }
        return line + ")";
    }

    /** 构造通用 Markdown 头（标题 + 本地玩家 + 触发时间 + 总计）。 */
    private String buildHeader(String title, int total) {
        capturePlayerName();
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String player = (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
        return "<font color=\"red\">**" + title + "**</font>\n"
                + "玩家:" + player + "\n"
                + "触发时间:" + time + "\n"
                + "总计:" + total + "人";
    }

    private String buildEnterMarkdown(List<StrangerInfo> infos) {
        StringBuilder sb = new StringBuilder(buildHeader("[预警]非好友玩家进入视野", infos.size()));
        int showCount = Math.min(infos.size(), MAX_DISPLAY_PLAYERS);
        if (infos.size() > MAX_DISPLAY_PLAYERS) {
            // 玩家过多：用紧凑格式（玩家名+UUID）防止超出飞书 Webhook 字节限制
            sb.append("\n玩家id:");
            for (int i = 0; i < showCount; i++) {
                StrangerInfo info = infos.get(i);
                sb.append("\n").append(info.name()).append("(").append(info.uuid()).append(")");
            }
            sb.append("\n…等").append(infos.size()).append("名玩家");
        } else {
            for (StrangerInfo info : infos) {
                sb.append("\n进入玩家:").append(formatPlayerLine(info));
            }
        }
        return sb.toString();
    }

    private String buildLeaveMarkdown(List<LeaveInfo> infos) {
        StringBuilder sb = new StringBuilder(buildHeader("[预警]非好友玩家离开视野", infos.size()));
        int showCount = Math.min(infos.size(), MAX_DISPLAY_PLAYERS);
        if (infos.size() > MAX_DISPLAY_PLAYERS) {
            // 玩家过多：用紧凑格式（玩家名+UUID）防止超出飞书 Webhook 字节限制
            sb.append("\n玩家id:");
            for (int i = 0; i < showCount; i++) {
                LeaveInfo info = infos.get(i);
                sb.append("\n").append(info.name()).append("(").append(info.uuid()).append(")");
            }
            sb.append("\n…等").append(infos.size()).append("名玩家");
        } else {
            for (LeaveInfo info : infos) {
                String duration = formatDuration(info.durationSec);
                String tier = info.cachedThreat != null ? info.cachedThreat.tier() : "未知";
                sb.append("\n离开玩家:").append(info.name)
                        .append("(最后距离:").append(String.format("%.1f", info.lastDistance)).append("米")
                        .append(", 持续:").append(duration)
                        .append(", 威胁等级:").append(tier)
                        .append(")");
            }
        }
        return sb.toString();
    }

    private String buildSnapshotMarkdown(List<StrangerInfo> infos) {
        StringBuilder sb = new StringBuilder(buildHeader("[预警]附近有非好友玩家", infos.size()));
        int showCount = Math.min(infos.size(), MAX_DISPLAY_PLAYERS);
        if (infos.size() > MAX_DISPLAY_PLAYERS) {
            // 玩家过多：用紧凑格式（玩家名+UUID）防止超出飞书 Webhook 字节限制
            sb.append("\n玩家id:");
            for (int i = 0; i < showCount; i++) {
                StrangerInfo info = infos.get(i);
                sb.append("\n").append(info.name()).append("(").append(info.uuid()).append(")");
            }
            sb.append("\n…等").append(infos.size()).append("名玩家");
        } else {
            for (StrangerInfo info : infos) {
                sb.append("\n玩家ID:").append(formatPlayerLine(info));
            }
        }
        return sb.toString();
    }

    /** 持续时长格式化。 */
    private String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        }
        long minutes = seconds / 60;
        long remainSec = seconds % 60;
        return minutes + "分" + remainSec + "秒";
    }

    // ---------- 工具方法 ----------

    private void sendMarkdown(String markdown) {
        FeishuWebhookModule webhook = Modules.get().get(FeishuWebhookModule.class);
        if (webhook != null) {
            webhook.sendMarkdown(markdown);
        }
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
