package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import cn.anyho.xyuan.util.QueueParser;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 队列提醒模块：监控 3c3u.org 服务器队列位置，通过飞书 Webhook 推送多场景排队提醒。
 *
 * <p>支持场景：入队 / 进度 / 即将进入服务器 / 完成 / 无排队进入 / 模块关闭 / 退出服务器 / 异常断开。
 * 通过高优先级 {@link GameLeftEvent} 处理器区分手动关闭、退出服务器与 BungeeCord 切换子服。</p>
 */
public class QueueNoticeModule extends Module {

    /** 进度通知模式（二选一）。重写 toString 返回中文供 Meteor EnumSetting 显示。 */
    public enum NotifyMode {
        DEFAULT,
        CUSTOM;

        @Override
        public String toString() {
            return switch (this) {
                case DEFAULT -> "默认";
                case CUSTOM -> "自定义";
            };
        }
    }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String TARGET_SERVER = "3c3u.org";

    /** 退出判定延迟（秒）：此时间内收到 GameJoinedEvent 视为切换子服，否则视为真正退出。 */
    private static final long EXIT_DETECT_DELAY_SECONDS = 5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("高级");

    private final Setting<NotifyMode> notifyMode = sgGeneral.add(new EnumSetting.Builder<NotifyMode>()
            .name("进度通知模式")
            .description("默认：整十位置通知（90/80/70…），且排名 ≤ 5 时每前进一名都通知。"
                    + "自定义：按「提醒步长」自定义通知频率。两者二选一。")
            .defaultValue(NotifyMode.DEFAULT)
            .build()
    );

    private final Setting<Integer> reminderStep = sgGeneral.add(new IntSetting.Builder()
            .name("提醒步长")
            .description("每前进多少名触发一次进度提醒。仅在「进度通知模式」选择「自定义」时生效。最小值 1。")
            .defaultValue(10)
            .range(1, 1000)
            .sliderRange(1, 100)
            .visible(() -> notifyMode.get() == NotifyMode.CUSTOM)
            .build()
    );

    private final Setting<Boolean> enableJoinNotify = sgGeneral.add(new BoolSetting.Builder()
            .name("启用入队提醒")
            .description("首次检测到排队位置时触发入队提醒。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> enableCompleteNotify = sgGeneral.add(new BoolSetting.Builder()
            .name("启用完成提醒")
            .description("排队结束、切换子服进入游戏世界时触发完成提醒。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> enableNoQueueNotify = sgGeneral.add(new BoolSetting.Builder()
            .name("启用无排队进入提醒")
            .description("未排队直接进入游戏子服时触发提醒（如登录子服直接传送进服）。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> enableModuleCloseNotify = sgGeneral.add(new BoolSetting.Builder()
            .name("启用模块关闭提醒")
            .description("玩家手动关闭本模块时触发提醒。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> enableExitNotify = sgGeneral.add(new BoolSetting.Builder()
            .name("启用退出服务器提醒")
            .description("玩家主动断开服务器连接时触发提醒。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> enableAbnormalNotify = sgGeneral.add(new BoolSetting.Builder()
            .name("启用异常断开提醒")
            .description("服务器异常断开连接时触发提醒，携带断开时的报错原文。")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> skipServerCheck = sgAdvanced.add(new BoolSetting.Builder()
            .name("不校验服务器地址")
            .description("跳过 3c3u.org 白名单校验，允许在任意服务器触发排队提醒。")
            .defaultValue(false)
            .build()
    );

    // ---------- 运行时状态 ----------

    private boolean onTargetServer;
    private boolean inQueue;
    private int lastPosition = -1;
    private int lastNotifiedPosition = -1;
    private boolean notifiedAboutToEnter;
    private boolean capturedAbnormalDisconnect;
    private String capturedDisconnectReason;
    private String playerName;
    /** 高优先级处理器先于 Meteor 的 Modules.onGameLeft 设置，用于区分手动关闭与退出/切换子服。 */
    private volatile boolean leavingGame;
    private volatile boolean exitCancelled;
    /** 本次会话是否已进入过主服，避免主服 ↔ 登录服反复切换时重复发送无排队提醒。 */
    private boolean enteredMainServer;

    private final ScheduledExecutorService exitDetector = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "QueueNotice-ExitDetector");
        t.setDaemon(true);
        return t;
    });

    public QueueNoticeModule() {
        super(QueueNoticeAddon.CATEGORY, "队列提醒", "监控3c3u排队进度并通过飞书Webhook推送提醒。");
    }

    @Override
    public void onActivate() {
        exitCancelled = true;

        if (leavingGame) {
            // 切换子服完成（GameLeftEvent 后紧随 GameJoinedEvent）
            int pos = lastPosition;
            boolean wasInQueue = inQueue;
            boolean wasOnTarget = onTargetServer;
            resetQueueState();
            onTargetServer = isOnTargetServer();
            capturedAbnormalDisconnect = false;
            capturedDisconnectReason = null;
            leavingGame = false;

            if (wasOnTarget && onTargetServer) {
                if (wasInQueue) {
                    if (enableCompleteNotify.get()) {
                        sendReminder(buildMarkdown("排队已完成-", "完成", 0, pos, null));
                    }
                    enteredMainServer = true;
                } else {
                    // 仅在本次会话首次进入主服时发送无排队提醒
                    if (enableNoQueueNotify.get() && !enteredMainServer) {
                        sendReminder(buildMarkdown("", "通知", 0, 0, "本次没有排队,成功进入服务器!"));
                    }
                    enteredMainServer = true;
                }
            }
        } else {
            resetQueueState();
            onTargetServer = isOnTargetServer();
            capturedAbnormalDisconnect = false;
            capturedDisconnectReason = null;
            leavingGame = false;
            enteredMainServer = false;
        }
        capturePlayerName();
    }

    @Override
    public void onDeactivate() {
        if (!leavingGame) {
            // 手动关闭模块
            if (inQueue && enableModuleCloseNotify.get()) {
                sendReminder(buildMarkdown("", "通知", 0, lastPosition, "模块已关闭,停止通知!"));
            }
            resetQueueState();
            onTargetServer = false;
            capturedAbnormalDisconnect = false;
            capturedDisconnectReason = null;
        }
        // leavingGame=true 时：退出或切换子服触发的停用，不重置状态，
        // 等 onActivate（切换子服）或延迟任务（真正退出）处理
    }

    /**
     * 高优先级 GameLeftEvent 处理器：先于 Meteor 的 Modules.onGameLeft 执行，
     * 设置 leavingGame 标志并安排延迟退出通知。
     */
    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.HIGH)
    private void onGameLeftHigh(GameLeftEvent event) {
        leavingGame = true;
        exitCancelled = false;
        if (inQueue) {
            // 排队中：延迟判定，若 EXIT_DETECT_DELAY_SECONDS 内未收到 onActivate 则视为真正退出
            exitDetector.schedule(() -> {
                if (exitCancelled) {
                    return;
                }
                // 二次校验：若此时已连接到新世界（切换子服完成但 onActivate 尚未触发），同样判定为切换子服
                if (mc.world != null) {
                    return;
                }
                leavingGame = false;
                enteredMainServer = false;
                sendExitOrAbnormalReminder();
            }, EXIT_DETECT_DELAY_SECONDS, TimeUnit.SECONDS);
        } else {
            // 未排队：延迟重置 leavingGame，避免下次 onActivate 误判为切换子服
            exitDetector.schedule(() -> {
                if (exitCancelled) {
                    return;
                }
                leavingGame = false;
                enteredMainServer = false;
            }, EXIT_DETECT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        onTargetServer = isOnTargetServer();
        capturePlayerName();
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        handleText(event.getMessage().getString());
    }

    /** 数据包接收：标题 / 副标题 / 动作栏（解析队列文本）、服务器主动断开（捕获异常原因）。 */
    @SuppressWarnings("unused")
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.packet;

        if (packet instanceof TitleS2CPacket(var text)) {
            handleText(text.getString());
        } else if (packet instanceof SubtitleS2CPacket(var text)) {
            handleText(text.getString());
        } else if (packet instanceof OverlayMessageS2CPacket(var text)) {
            handleText(text.getString());
        } else if (packet instanceof DisconnectS2CPacket(var reason)) {
            if (onTargetServer) {
                capturedAbnormalDisconnect = true;
                capturedDisconnectReason = reason.getString();
            }
        }
    }

    private void handleText(String text) {
        if (!onTargetServer) {
            return;
        }
        capturePlayerName();
        int position = QueueParser.parsePosition(text);
        if (position < 0) {
            return;
        }
        onQueuePosition(position);
    }

    /** 更新队列状态并触发入队 / 进度 / 即将进入服务器提醒。 */
    private void onQueuePosition(int position) {
        int forward = (inQueue && lastPosition > 0) ? (lastPosition - position) : 0;
        if (forward < 0) {
            forward = 0;
        }

        if (!inQueue) {
            // 首次检测 → 入队提醒
            inQueue = true;
            lastPosition = position;
            lastNotifiedPosition = position;

            if (enableJoinNotify.get()) {
                sendReminder(buildMarkdown("", "入队", 0, position, null));
            }
            if (position == 1) {
                fireAboutToEnterReminder(0);
            }
            return;
        }

        lastPosition = position;

        // 位置到达 1 → 即将进入服务器提醒（仅一次）
        if (position == 1) {
            if (!notifiedAboutToEnter) {
                fireAboutToEnterReminder(forward);
            }
            return;
        }

        // 进度提醒
        if (forward > 0 && shouldNotifyProgress(position)) {
            int advance = lastNotifiedPosition - position;
            sendReminder(buildMarkdown("", "进度", advance, position, null));
            lastNotifiedPosition = position;
        }
    }

    /**
     * 判断当前前进是否应触发进度提醒。
     *
     * <p>默认模式：位置 ≤ 5 每名通知；否则检查 [position, lastNotifiedPosition) 区间内
     * 是否存在 10 的倍数（跨过或恰好落到整十位置都触发）。</p>
     *
     * <p>自定义模式：自上次提醒以来前进位数 ≥ 提醒步长时通知。</p>
     */
    private boolean shouldNotifyProgress(int position) {
        if (notifyMode.get() == NotifyMode.DEFAULT) {
            if (position <= 5) {
                return true;
            }
            // 等价于：存在整数 k 满足 position <= 10k < lastNotifiedPosition
            int lowestMultipleAtOrAbovePos = (position + 9) / 10;
            int highestMultipleBelowLast = (lastNotifiedPosition - 1) / 10;
            return highestMultipleBelowLast >= lowestMultipleAtOrAbovePos;
        }
        return (lastNotifiedPosition - position) >= reminderStep.get();
    }

    private void fireAboutToEnterReminder(int forward) {
        notifiedAboutToEnter = true;
        sendReminder(buildMarkdown("", "通知", forward, 1, "即将进入服务器!"));
    }

    /** 发送退出服务器 / 异常断开提醒（根据是否捕获到异常断开包区分）。类型统一为「离队」。 */
    private void sendExitOrAbnormalReminder() {
        int pos = lastPosition;
        boolean abnormal = capturedAbnormalDisconnect;
        String reason = capturedDisconnectReason;

        if (abnormal) {
            if (enableAbnormalNotify.get()) {
                sendReminder(buildMarkdown("", "离队", 0, pos,
                        "异常断开连接(" + (reason == null ? "未知原因" : reason) + ")"));
            }
        } else if (enableExitNotify.get()) {
            sendReminder(buildMarkdown("", "离队", 0, pos, "已退出服务器,排队已停止!"));
        }
    }

    /**
     * 构造飞书交互式卡片的 Markdown 内容。
     *
     * @param titlePrefix 标题前缀（完成提醒使用 "排队已完成-"，其余为空）
     * @param type        类型字段值（入队 / 进度 / 完成 / 通知 / 离队）
     * @param forward     本次前进位数
     * @param position    当前/最后位置
     * @param note        备注内容（非空时使用"最后位置"行 + 备注行；为空时使用"当前位置"行）
     */
    private String buildMarkdown(String titlePrefix, String type, int forward, int position, String note) {
        capturePlayerName();
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String player = (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
        StringBuilder sb = new StringBuilder();
        sb.append(titlePrefix).append("[3C3U队列提醒]\n");
        sb.append("玩家:").append(player).append("\n");
        sb.append("类型: ").append(type).append("\n");
        sb.append("本次前进: ").append(forward).append("\n");
        sb.append("触发时间: ").append(time).append("\n");
        if (note != null) {
            sb.append("最后位置: ").append(redBold(position)).append("\n");
            sb.append("备注: ").append(note);
        } else {
            sb.append("当前位置: ").append(redBold(position));
        }
        return sb.toString();
    }

    /** 红色加粗样式：<font color="red">**数值**</font> */
    private String redBold(int value) {
        return "<font color=\"red\">**" + value + "**</font>";
    }

    /** 异步推送提醒到飞书 Webhook。不校验 FeishuWebhookModule.isActive()，未启用时设置仍生效。 */
    private void sendReminder(String markdownContent) {
        FeishuWebhookModule webhook = Modules.get().get(FeishuWebhookModule.class);
        if (webhook != null) {
            webhook.sendMarkdown(markdownContent);
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

    /** 启用「不校验服务器地址」时直接返回 true；否则按地址包含 3c3u.org 判定。 */
    private boolean isOnTargetServer() {
        if (skipServerCheck.get()) {
            return true;
        }
        if (mc.getCurrentServerEntry() == null) {
            return false;
        }
        String address = mc.getCurrentServerEntry().address;
        return address != null && address.toLowerCase().contains(TARGET_SERVER);
    }

    private void resetQueueState() {
        inQueue = false;
        lastPosition = -1;
        lastNotifiedPosition = -1;
        notifiedAboutToEnter = false;
    }
}
