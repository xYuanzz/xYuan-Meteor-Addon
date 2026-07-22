package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据包调试模块：抓取接收（S2C）与发送（C2S）数据包，写入日志文件供分析。
 *
 * <p>支持全部抓取与分类过滤（按方向 + 状态 + 类名关键字三维组合）。
 * 所有文件 I/O 在独立守护线程异步执行，主线程零阻塞；
 * 数据包 toString() 在主线程立即调用（避免 Netty buffer 被回收），
 * 转成字符串后提交到后台线程写文件。</p>
 */
public class PacketDebugModule extends Module {

    /** 抓取模式。重写 toString 返回中文。 */
    public enum CaptureMode {
        ALL,
        FILTERED;

        @Override
        public String toString() {
            return switch (this) {
                case ALL -> "全部抓取";
                case FILTERED -> "分类过滤";
            };
        }
    }

    /** 数据包方向。 */
    public enum Direction {
        S2C("接收(S2C)"),
        C2S("发送(C2S)");

        private final String displayName;

        Direction(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 默认日志文件名（相对游戏根目录）。 */
    private static final String DEFAULT_LOG_FILE = "logs/packets-debug.log";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDirection = settings.createGroup("方向过滤");
    private final SettingGroup sgState = settings.createGroup("状态过滤");
    private final SettingGroup sgClassName = settings.createGroup("类名过滤");
    private final SettingGroup sgOutput = settings.createGroup("输出设置");
    private final SettingGroup sgAdvanced = settings.createGroup("高级");

    // ---------- 基础设置 ----------

    private final Setting<CaptureMode> captureMode = sgGeneral.add(new EnumSetting.Builder<CaptureMode>()
            .name("抓取模式")
            .description("全部抓取：不经过滤记录所有数据包。分类过滤：按方向 / 状态 / 类名关键字组合过滤。")
            .defaultValue(CaptureMode.FILTERED)
            .build()
    );

    // ---------- 方向过滤 ----------

    private final Setting<Boolean> captureS2C = sgDirection.add(new BoolSetting.Builder()
            .name("抓取接收包(S2C)")
            .description("记录服务端发往客户端的数据包。")
            .defaultValue(true)
            .visible(() -> captureMode.get() == CaptureMode.FILTERED)
            .build()
    );

    private final Setting<Boolean> captureC2S = sgDirection.add(new BoolSetting.Builder()
            .name("抓取发送包(C2S)")
            .description("记录客户端发往服务端的数据包。")
            .defaultValue(false)
            .visible(() -> captureMode.get() == CaptureMode.FILTERED)
            .build()
    );

    // ---------- 状态过滤 ----------

    private final Setting<Boolean> statePlay = sgState.add(new BoolSetting.Builder()
            .name("Play 状态")
            .description("游戏内主要数据包（实体 / 方块 / 聊天 / 移动等）。")
            .defaultValue(true)
            .visible(() -> captureMode.get() == CaptureMode.FILTERED)
            .build()
    );

    private final Setting<Boolean> stateCommon = sgState.add(new BoolSetting.Builder()
            .name("Common 状态")
            .description("通用数据包（KeepAlive / Disconnect / CustomPayload / Ping 等）。")
            .defaultValue(true)
            .visible(() -> captureMode.get() == CaptureMode.FILTERED)
            .build()
    );

    private final Setting<Boolean> stateConfiguration = sgState.add(new BoolSetting.Builder()
            .name("Configuration 状态")
            .description("配置阶段数据包（注册表同步 / 标签同步等）。")
            .defaultValue(false)
            .visible(() -> captureMode.get() == CaptureMode.FILTERED)
            .build()
    );

    private final Setting<Boolean> stateLogin = sgState.add(new BoolSetting.Builder()
            .name("Login 状态")
            .description("登录阶段数据包（加密握手 / 登录成功等）。")
            .defaultValue(false)
            .visible(() -> captureMode.get() == CaptureMode.FILTERED)
            .build()
    );

    private final Setting<Boolean> stateHandshake = sgState.add(new BoolSetting.Builder()
            .name("Handshake 状态")
            .description("握手阶段数据包（连接建立时的协议协商）。")
            .defaultValue(false)
            .visible(() -> captureMode.get() == CaptureMode.FILTERED)
            .build()
    );

    private final Setting<Boolean> stateUnknown = sgState.add(new BoolSetting.Builder()
            .name("未知状态")
            .description("记录无法归类到上述任一状态的数据包（如 BundlePacket 等特殊包）。")
            .defaultValue(true)
            .visible(() -> captureMode.get() == CaptureMode.FILTERED)
            .build()
    );

    // ---------- 类名关键字过滤 ----------

    private final Setting<String> includeKeywords = sgClassName.add(new StringSetting.Builder()
            .name("类名关键字(白名单)")
            .description("仅记录类名包含任一关键字的数据包，多个关键字用逗号分隔（如 EntityStatus,ChatMessage）。留空 = 不限制。不区分大小写。")
            .defaultValue("")
            .wide()
            .visible(() -> captureMode.get() == CaptureMode.FILTERED)
            .build()
    );

    // ---------- 输出设置 ----------

    private final Setting<String> logFile = sgOutput.add(new StringSetting.Builder()
            .name("日志文件路径")
            .description("数据包日志文件路径（相对游戏根目录）。默认 logs/packets-debug.log。")
            .defaultValue(DEFAULT_LOG_FILE)
            .wide()
            .build()
    );

    private final Setting<Integer> maxLineLength = sgOutput.add(new IntSetting.Builder()
            .name("单行最大长度")
            .description("单条日志最大字符数（防止超长 toString 撑爆文件）。超出部分截断并以 ...[truncated] 标记。")
            .defaultValue(2000)
            .range(100, 50000)
            .sliderRange(500, 5000)
            .build()
    );

    private final Setting<Boolean> clearOnActivate = sgOutput.add(new BoolSetting.Builder()
            .name("启用时清空文件")
            .description("模块启用时清空日志文件，避免历史数据干扰分析。")
            .defaultValue(true)
            .build()
    );

    // ---------- 高级 ----------

    private final Setting<Boolean> skipServerCheck = sgAdvanced.add(new BoolSetting.Builder()
            .name("不校验服务器地址")
            .description("跳过 3c3u.org 白名单校验，允许在任意服务器抓取数据包。")
            .defaultValue(true)
            .build()
    );

    // ---------- 运行时状态 ----------

    /** 单线程守护线程池：所有文件 I/O 在此执行，主线程零阻塞。 */
    private final ExecutorService writerThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PacketDebug-Writer");
        t.setDaemon(true);
        return t;
    });

    /** 文件写入器，仅在后台线程中访问，无需同步。 */
    private volatile BufferedWriter writer;

    public PacketDebugModule() {
        super(QueueNoticeAddon.CATEGORY, "数据包调试", "抓取接收/发送数据包并写入日志文件供分析，支持分类过滤。");
    }

    @Override
    public void onActivate() {
        writer = null;
        String filePath = logFile.get();
        boolean truncate = clearOnActivate.get();

        // 在后台线程打开文件，避免主线程阻塞
        writerThread.submit(() -> {
            try {
                Path path = Paths.get(filePath);
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StandardOpenOption openOption = truncate
                        ? StandardOpenOption.TRUNCATE_EXISTING
                        : StandardOpenOption.APPEND;
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, openOption);
                writer.write("=== xYuan's Mod 数据包调试启动 === " + LocalDateTime.now().format(TIME_FORMAT));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                QueueNoticeAddon.LOG.error("[xYuan's Mod] PacketDebug open file failed: {}", e.toString());
            }
        });
    }

    @Override
    public void onDeactivate() {
        // 在后台线程关闭 writer，确保所有缓冲数据落盘
        writerThread.submit(() -> {
            if (writer != null) {
                try {
                    writer.write("=== 数据包调试停止 === " + LocalDateTime.now().format(TIME_FORMAT));
                    writer.newLine();
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    QueueNoticeAddon.LOG.error("[xYuan's Mod] PacketDebug close file failed: {}", e.toString());
                }
                writer = null;
            }
        });
    }

    // ---------- 事件监听 ----------

    @SuppressWarnings("unused")
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        capturePacket(Direction.S2C, event.packet);
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        capturePacket(Direction.C2S, event.packet);
    }

    // ---------- 核心逻辑 ----------

    /** 主线程入口：过滤 → 转字符串 → 提交后台线程写文件。 */
    private void capturePacket(Direction direction, Packet<?> packet) {
        if (!shouldCapture(direction, packet)) {
            return;
        }

        // 在主线程立即调用 toString()，避免 Netty buffer 被回收后失效
        String packetStr;
        try {
            packetStr = packet.toString();
        } catch (Throwable t) {
            packetStr = "[toString() failed: " + t + "]";
        }

        String className = packet.getClass().getSimpleName();
        String state = classifyState(packet);
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String line = formatLine(time, direction, state, className, packetStr);

        // 提交后台线程写文件，主线程零阻塞
        writerThread.submit(() -> appendToFile(line));
    }

    /** 判断是否应抓取此数据包（按模式 + 方向 + 状态 + 类名关键字组合过滤）。 */
    private boolean shouldCapture(Direction direction, Packet<?> packet) {
        if (isNotOnTargetServer()) {
            return false;
        }

        // 全部抓取模式：跳过所有过滤
        if (captureMode.get() == CaptureMode.ALL) {
            return true;
        }

        // 方向过滤
        if (direction == Direction.S2C && !captureS2C.get()) {
            return false;
        }
        if (direction == Direction.C2S && !captureC2S.get()) {
            return false;
        }

        // 状态过滤
        String state = classifyState(packet);
        if (!isStateAllowed(state)) {
            return false;
        }

        // 类名关键字白名单过滤
        String keywords = includeKeywords.get();
        if (keywords != null && !keywords.isBlank()) {
            String className = packet.getClass().getSimpleName().toLowerCase();
            String[] kws = keywords.toLowerCase().split(",");
            boolean matched = false;
            for (String kw : kws) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty() && className.contains(trimmed)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        return true;
    }

    /** 根据数据包类名归类状态（play/common/configuration/login/handshake/unknown）。 */
    private String classifyState(Packet<?> packet) {
        String name = packet.getClass().getName();
        if (name.contains(".play.")) return "play";
        if (name.contains(".common.")) return "common";
        if (name.contains(".config.")) return "configuration";
        if (name.contains(".login.")) return "login";
        if (name.contains(".handshake.")) return "handshake";
        return "unknown";
    }

    /** 判断状态是否被设置项允许。 */
    private boolean isStateAllowed(String state) {
        return switch (state) {
            case "play" -> statePlay.get();
            case "common" -> stateCommon.get();
            case "configuration" -> stateConfiguration.get();
            case "login" -> stateLogin.get();
            case "handshake" -> stateHandshake.get();
            default -> stateUnknown.get();
        };
    }

    /** 格式化单条日志行。 */
    private String formatLine(String time, Direction direction, String state, String className, String packetStr) {
        int maxLen = maxLineLength.get();
        String body = packetStr;
        if (body != null && body.length() > maxLen) {
            body = body.substring(0, maxLen) + "...[truncated]";
        }
        return "[" + time + "] [" + direction + "] [" + state + "] " + className + " | " + body;
    }

    /** 后台线程调用：追加一行到日志文件。 */
    private void appendToFile(String line) {
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        try {
            w.write(line);
            w.newLine();
            w.flush();
        } catch (IOException e) {
            QueueNoticeAddon.LOG.error("[xYuan's Mod] PacketDebug write failed: {}", e.toString());
        }
    }

    // ---------- 工具方法 ----------

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
