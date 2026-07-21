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
 * Meteor 附属模块：监控 3c3u.org 服务器队列位置，并通过飞书自定义机器人 Webhook
 * 推送多场景排队提醒（入队 / 进度 / 即将进入服务器 / 完成 / 无排队进入 / 模块关闭 / 退出服务器 / 异常断开）。
 *
 * <h2>生效范围</h2>
 * 仅在当前连接的服务器地址包含 {@code 3c3u.org} 时生效；非目标服务器下，即使手动
 * 开启模块，也不执行任何队列监听、消息推送逻辑。校验时机：模块启用时、客户端完成
 * 服务器连接时（{@link GameJoinedEvent}）。可在「高级」分类启用「不校验服务器地址」
 * 跳过白名单，允许在任意服务器触发排队提醒。
 *
 * <h2>触发场景</h2>
 * <ol>
 *   <li><b>入队提醒</b>：首次检测到有效队列位置时触发。</li>
 *   <li><b>进度提醒</b>：按「进度通知模式」触发——默认模式下位置 ≤ 5 每名通知、
 *       其余仅整十位置通知；自定义模式下自上次提醒以来前进位数 ≥ 提醒步长时触发。</li>
 *   <li><b>即将进入服务器提醒</b>：队列位置到达 1 时触发（仅一次），表示即将进服。</li>
 *   <li><b>完成提醒</b>：BungeeCord/Velocity 切换子服时触发（由 {@link GameJoinedEvent}
 *       检测，对应代理切换走配置阶段 + 重新 Login 流程，而非维度切换）。</li>
 *   <li><b>无排队进入提醒</b>：未排队直接切换子服进入游戏世界时触发（如登录子服直接传送进服）。
 *       <b>仅在本会话首次进入主服时触发</b>，避免主服 ↔ 登录服反复切换时重复发送
 *       （通过 {@code enteredMainServer} 标志防重复；真正退出服务器后重置）。</li>
 *   <li><b>模块关闭提醒</b>：玩家手动关闭本模块时触发（{@link #onDeactivate()}）。</li>
 *   <li><b>正常退出服务器提醒</b>：玩家主动断开连接（退出到主菜单）时触发（延迟判定），
 *       类型字段为「离队」。</li>
 *   <li><b>异常断开提醒</b>：服务器发送 {@link DisconnectS2CPacket} 主动断开时触发，
 *       携带断开时的报错原文，类型字段为「离队」。</li>
 * </ol>
 *
 * <h2>退出与切换子服的区分</h2>
 * Meteor 在 {@link GameLeftEvent} 时会自动停用 {@code runInMainMenu=false} 的模块（调用
 * {@link #onDeactivate()}）。BungeeCord 切换子服也会触发 {@link GameLeftEvent}，但紧随其后
 * 会触发 {@link GameJoinedEvent}（重新 {@link #onActivate()}）。本模块用高优先级
 * {@link GameLeftEvent} 处理器（{@link #onGameLeftHigh(GameLeftEvent)}）先于 Meteor 的
 * {@code Modules.onGameLeft} 执行，设置 {@code leavingGame} 标志，并在 {@code onDeactivate}
 * 中不重置排队状态；若 {@link #EXIT_DETECT_DELAY_SECONDS}（5 秒）内收到 {@link GameJoinedEvent}
 * （切换子服），则发完成提醒并取消退出通知；否则判定为真正退出，发送退出/离队通知。
 * 延迟任务还会二次校验 {@code mc.world != null}：若届时已连接到新世界（切换子服完成但
 * {@code onActivate} 尚未触发），同样判定为切换子服，不发通知。
 *
 * <h2>无排队进入提醒的防重复</h2>
 * BungeeCord 主服 ↔ 登录服切换都会触发 {@code onActivate} 且 {@code inQueue=false}，无法
 * 仅凭队列状态区分方向。本模块通过 {@code enteredMainServer} 会话标志解决：首次进入主服
 * （无论是否排队）后置 {@code true}，后续未排队切换不再发送「本次没有排队」提醒；
 * 仅在真正退出服务器（延迟任务触发）或首次连接时重置为 {@code false}。
 *
 * <p>所有 Webhook 请求在独立守护线程异步执行，不阻塞主线程；自带防重复触发逻辑。
 * 仅客户端生效，无任何服务端逻辑。</p>
 */
public class QueueNoticeModule extends Module {

    /**
     * 进度通知模式（二选一）。
     * <ul>
     *   <li>{@link #DEFAULT}：整十位置通知（90/80/70…），且排名 ≤ 5 时每前进一名都通知。</li>
     *   <li>{@link #CUSTOM}：按自定义步长通知（每前进 N 名触发一次）。</li>
     * </ul>
     *
     * <p>重写 {@link #toString()} 返回中文，Meteor {@code EnumSetting} 的 GUI 显示、
     * 命令补全与配置序列化均使用 {@code toString()}。</p>
     */
    public enum NotifyMode {
        /** 默认模式：整十位置通知 + 前 5 每名通知。 */
        DEFAULT,
        /** 自定义模式：按「提醒步长」自定义通知频率。 */
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

    /** 目标服务器地址关键字（包含即生效）。 */
    private static final String TARGET_SERVER = "3c3u.org";

    /**
     * 退出判定延迟（秒）：在此时间内收到 {@link GameJoinedEvent}（即 {@link #onActivate()}
     * 被调用）视为切换子服，发完成提醒；否则判定为真正退出，发送退出/离队通知。
     *
     * <p>取 5 秒以兼容 BungeeCord 切换子服的耗时（disconnect → 重新连接 → GameJoinedEvent
     * 全流程可能超过 2 秒）。延迟任务内部还会二次校验 {@code mc.world}：若届时已连接到
     * 新子服，同样判定为切换子服，不发通知。</p>
     */
    private static final long EXIT_DETECT_DELAY_SECONDS = 5;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("高级");

    // ---------- 基础设置 ----------

    /** 进度通知模式：默认（整十 + 前5每名）或自定义（按步长），二选一。 */
    private final Setting<NotifyMode> notifyMode = sgGeneral.add(new EnumSetting.Builder<NotifyMode>()
            .name("进度通知模式")
            .description("默认：整十位置通知（90/80/70…），且排名 ≤ 5 时每前进一名都通知。"
                    + "自定义：按「提醒步长」自定义通知频率。两者二选一。")
            .defaultValue(NotifyMode.DEFAULT)
            .build()
    );

    /**
     * 自定义提醒步长：每前进多少名触发一次进度提醒（最小值 1）。
     * 仅在「进度通知模式」选择「自定义」时显示与生效。
     */
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

    // ---------- 高级设置 ----------

    /** 跳过 3c3u.org 白名单校验，允许在任意服务器触发排队提醒。 */
    private final Setting<Boolean> skipServerCheck = sgAdvanced.add(new BoolSetting.Builder()
            .name("不校验服务器地址")
            .description("跳过 3c3u.org 白名单校验，允许在任意服务器触发排队提醒。")
            .defaultValue(false)
            .build()
    );

    // ---------- 运行时状态 ----------

    /** 是否处于目标服务器（3c3u.org）。非目标服务器下所有逻辑禁用。 */
    private boolean onTargetServer;
    /** 是否正在排队（已检测到队列位置）。 */
    private boolean inQueue;
    /** 最后一次有效队列位置（-1 = 无）。 */
    private int lastPosition;
    /** 上一次进度提醒发出时的位置（-1 = 无）。 */
    private int lastNotifiedPosition;
    /** 是否已发送过本次排队的「即将进入服务器」提醒（防重复）。 */
    private boolean notifiedAboutToEnter;
    /** 是否收到服务器主动断开包（异常断开标志）。 */
    private boolean capturedAbnormalDisconnect;
    /** 服务器断开包携带的报错原文。 */
    private String capturedDisconnectReason;
    /** 缓存的玩家名（在游戏中捕获，断开后仍可用于通知，区分不同账号）。 */
    private String playerName;
    /**
     * 是否正在离开游戏（{@link GameLeftEvent} 触发，用于区分手动关闭与退出/切换子服）。
     * 高优先级处理器先于 Meteor 的 {@code Modules.onGameLeft} 设置此标志。
     */
    private volatile boolean leavingGame;
    /** 是否取消了待发的退出通知（切换子服时由 {@link #onActivate()} 设置为 true）。 */
    private volatile boolean exitCancelled;
    /**
     * 本次会话是否已进入过主服（完成排队或无排队直接进入）。
     * 用于避免在主服 ↔ 登录服之间反复切换时重复发送「本次没有排队」提醒。
     * 仅在首次连接目标服务器或真正退出（延迟任务触发）时重置为 false。
     */
    private boolean enteredMainServer;

    /** 退出判定延迟执行器（守护线程，不阻塞 JVM 退出）。 */
    private final ScheduledExecutorService exitDetector = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "QueueNotice-ExitDetector");
        t.setDaemon(true);
        return t;
    });

    public QueueNoticeModule() {
        super(QueueNoticeAddon.CATEGORY, "队列提醒", "监控 3c3u.org 服务器队列位置并通过飞书 Webhook 推送排队进度提醒。");
    }

    @Override
    public void onActivate() {
        // 切换子服后 Meteor 会重新调用 onActivate：取消待发的退出通知
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

            // 仅当之前在目标服务器且现在也在目标服务器时发提醒
            if (wasOnTarget && onTargetServer) {
                if (wasInQueue) {
                    // 排队中切换子服 → 完成提醒
                    if (enableCompleteNotify.get()) {
                        sendReminder(buildMarkdown("排队已完成-", "完成", 0, pos, null));
                    }
                    enteredMainServer = true;
                } else {
                    // 未排队直接切换子服 → 仅在本次会话首次进入主服时发送无排队提醒。
                    // 避免主服 ↔ 登录服反复切换时重复发送。
                    if (enableNoQueueNotify.get() && !enteredMainServer) {
                        sendReminder(buildMarkdown("", "通知", 0, 0, "本次没有排队,成功进入服务器!"));
                    }
                    enteredMainServer = true;
                }
            }
        } else {
            // 首次启用或手动重新启用：重置会话状态
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
            // 手动关闭模块（非退出/切换子服触发）
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

    // ---------- 事件监听 ----------

    /**
     * 高优先级 {@link GameLeftEvent} 处理器：先于 Meteor 的 {@code Modules.onGameLeft} 执行，
     * 设置 {@code leavingGame} 标志并安排延迟退出通知。
     *
     * <p>关键：Meteor 的 {@code Modules.onGameLeft} 会在 {@link GameLeftEvent} 期间调用
     * {@link #onDeactivate()}，但此时 {@code mc.world} 仍非 null（{@link GameLeftEvent} 在
     * {@code Minecraft.disconnect()} 的 HEAD 处 post），因此不能用 {@code mc.world} 区分
     * 手动关闭与退出。改用此标志位区分。</p>
     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onGameLeftHigh(GameLeftEvent event) {
        leavingGame = true;
        exitCancelled = false;
        if (inQueue) {
            // 排队中：延迟判定，若 EXIT_DETECT_DELAY_SECONDS 内未收到 onActivate（切换子服），
            // 则判定为真正退出，发送退出/离队通知
            exitDetector.schedule(() -> {
                if (exitCancelled) {
                    // 切换子服，onActivate 已取消
                    return;
                }
                // 二次校验：若此时已连接到新世界（切换子服完成但 onActivate 尚未触发），
                // 同样判定为切换子服，不发退出通知
                if (mc.world != null) {
                    return;
                }
                // 真正退出：发送退出/离队通知，重置会话状态
                leavingGame = false;
                enteredMainServer = false;
                sendExitOrAbnormalReminder();
            }, EXIT_DETECT_DELAY_SECONDS, TimeUnit.SECONDS);
        } else {
            // 未排队：延迟重置 leavingGame，避免下次 onActivate 误判为切换子服。
            // 若 EXIT_DETECT_DELAY_SECONDS 内收到 onActivate（切换子服）则取消，正常处理。
            exitDetector.schedule(() -> {
                if (exitCancelled) {
                    return;
                }
                // 真正退出：重置会话状态
                leavingGame = false;
                enteredMainServer = false;
            }, EXIT_DETECT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 客户端完成服务器连接：更新白名单标志并捕获玩家名。 */
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        // 完成提醒已在 onActivate 中处理（切换子服场景）
        onTargetServer = isOnTargetServer();
        capturePlayerName();
    }

    /** 聊天消息（系统 + 玩家聊天）。 */
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        handleText(event.getMessage().getString());
    }

    /**
     * 数据包接收：标题 / 副标题 / 动作栏（解析队列文本）、服务器主动断开（捕获异常原因）。
     */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.packet;

        if (packet instanceof TitleS2CPacket p) {
            handleText(p.text().getString());
        } else if (packet instanceof SubtitleS2CPacket p) {
            handleText(p.text().getString());
        } else if (packet instanceof OverlayMessageS2CPacket p) {
            handleText(p.text().getString());
        } else if (packet instanceof DisconnectS2CPacket p) {
            // 服务器主动断开（异常）：捕获报错原文，供退出通知使用
            if (onTargetServer) {
                capturedAbnormalDisconnect = true;
                capturedDisconnectReason = p.reason().getString();
            }
        }
    }

    // ---------- 核心逻辑 ----------

    /** 解析文本中的队列位置（仅在目标服务器生效）。 */
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

    /**
     * 更新队列状态并触发入队 / 进度 / 即将进入服务器提醒。
     *
     * @param position 新解析出的队列位置（≥ 0）
     */
    private void onQueuePosition(int position) {
        // 本次前进位数（首次检测或后退时为 0）
        int forward = (inQueue && lastPosition > 0) ? (lastPosition - position) : 0;
        if (forward < 0) {
            forward = 0;
        }

        // ----- 首次检测 → 入队提醒 -----
        if (!inQueue) {
            inQueue = true;
            lastPosition = position;
            lastNotifiedPosition = position;

            if (enableJoinNotify.get()) {
                sendReminder(buildMarkdown("", "入队", 0, position, null));
            }

            // 位置为 1 → 即将进入服务器提醒（仅一次）
            if (position == 1) {
                fireAboutToEnterReminder(0);
            }
            return;
        }

        // ----- 已在队列中：更新位置 -----
        lastPosition = position;

        // ----- 位置到达 1 → 即将进入服务器提醒（仅一次） -----
        if (position == 1) {
            if (!notifiedAboutToEnter) {
                fireAboutToEnterReminder(forward);
            }
            return; // 位置 1 不再触发进度提醒
        }

        // ----- 进度提醒 -----
        if (forward > 0 && shouldNotifyProgress(position)) {
            int advance = lastNotifiedPosition - position;
            sendReminder(buildMarkdown("", "进度", advance, position, null));
            lastNotifiedPosition = position;
        }
    }

    /**
     * 判断当前前进是否应触发进度提醒。
     *
     * <ul>
     *   <li>{@link NotifyMode#DEFAULT}：位置 ≤ 5 时每前进一名都通知；否则当自上次通知以来
     *       <b>跨过（含恰好落到）任意一个整十位置</b>时通知。例如 76 → 66 跨过 70，应通知；
     *       76 → 70 恰好落到 70，应通知；76 → 71 未跨过整十位置，不通知。</li>
     *   <li>{@link NotifyMode#CUSTOM}：自上次提醒以来前进位数 ≥ {@link #reminderStep} 时通知。</li>
     * </ul>
     *
     * @param position 当前队列位置（已前进，调用方保证 forward > 0）
     */
    private boolean shouldNotifyProgress(int position) {
        if (notifyMode.get() == NotifyMode.DEFAULT) {
            // 默认模式：前 5 每名通知
            if (position <= 5) {
                return true;
            }
            // 其余位置：检查 [position, lastNotifiedPosition) 区间内是否存在 10 的倍数。
            // 即是否存在整数 k 满足 position <= 10k < lastNotifiedPosition，
            // 等价于 ceil(position/10) <= floor((lastNotifiedPosition-1)/10)。
            // 用整数除法实现：ceil(position/10) = (position + 9) / 10，
            // floor((lastNotifiedPosition-1)/10) = (lastNotifiedPosition - 1) / 10。
            // 这样无论一次跨过多少名、是否恰好落到整十位置，都能正确触发通知。
            int lowestMultipleAtOrAbovePos = (position + 9) / 10;
            int highestMultipleBelowLast = (lastNotifiedPosition - 1) / 10;
            return highestMultipleBelowLast >= lowestMultipleAtOrAbovePos;
        }
        // 自定义模式：按步长通知
        return (lastNotifiedPosition - position) >= reminderStep.get();
    }

    /**
     * 触发「即将进入服务器」提醒（位置=1），自带防重复。
     *
     * @param forward 本次前进位数（首次检测到位置 1 时为 0；从其他位置前进到 1 时为实际差值）
     */
    private void fireAboutToEnterReminder(int forward) {
        notifiedAboutToEnter = true;
        sendReminder(buildMarkdown("", "通知", forward, 1, "即将进入服务器!"));
    }

    /**
     * 发送退出服务器 / 异常断开提醒（根据是否捕获到异常断开包区分）。
     * 由 {@link #onGameLeftHigh(GameLeftEvent)} 的延迟任务调用（真正退出时）。
     *
     * <p>两者的「类型」字段统一为「离队」，便于在飞书群按类型筛选。</p>
     */
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

    // ---------- 消息构造与推送 ----------

    /**
     * 构造飞书交互式卡片的 Markdown 内容，严格遵循场景模板。
     *
     * <p>所有模板第二行固定为「玩家:xxx」，用于在同一 Webhook 下区分不同账号。</p>
     *
     * @param titlePrefix 标题前缀（完成提醒使用 "排队已完成-"，其余为空）
     * @param type        类型字段值（入队 / 进度 / 完成 / 通知）
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

    /**
     * 异步推送提醒到飞书 Webhook（通过 FeishuWebhookModule 统一管理配置，不阻塞主线程）。
     *
     * <p>不校验 {@code FeishuWebhookModule.isActive()}：该模块未启用，
     * 其中的 Webhook 地址、签名等设置仍能生效，供队列提醒与图腾提醒共用。
     * {@link FeishuWebhookModule#sendMarkdown(String)} 内部会在 Webhook 地址为空时直接返回，
     * 避免无效请求。</p>
     */
    private void sendReminder(String markdownContent) {
        FeishuWebhookModule webhook = Modules.get().get(FeishuWebhookModule.class);
        if (webhook != null) {
            webhook.sendMarkdown(markdownContent);
        }
    }

    // ---------- 工具方法 ----------

    /** 在玩家存在时缓存玩家名，便于断开后发送通知时仍可区分账号。 */
    private void capturePlayerName() {
        if ((playerName == null || playerName.isEmpty()) && mc.player != null) {
            try {
                playerName = mc.player.getName().getString();
            } catch (Throwable ignored) {
                // 忽略，保持 null
            }
        }
    }

    /**
     * 判断当前是否在目标服务器。
     *
     * <p>启用「不校验服务器地址」时直接返回 true，允许在任意服务器触发排队提醒；
     * 否则按地址包含 {@code 3c3u.org} 判定。</p>
     */
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

    /** 重置排队状态变量（不影响白名单标志与缓存的玩家名）。 */
    private void resetQueueState() {
        inQueue = false;
        lastPosition = -1;
        lastNotifiedPosition = -1;
        notifiedAboutToEnter = false;
    }
}
