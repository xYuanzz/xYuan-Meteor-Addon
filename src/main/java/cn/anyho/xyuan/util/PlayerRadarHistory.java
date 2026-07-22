package cn.anyho.xyuan.util;

import cn.anyho.xyuan.QueueNoticeAddon;
import cn.anyho.xyuan.util.ThreatLevelCalculator.ThreatResult;

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
 * 附近玩家历史记录器：将进入/离开事件异步写入本地文件。
 *
 * <p>记录内容含时间、玩家名、距离、持续时长、血量、威胁等级、完整装备与附魔详情。
 * 所有磁盘 I/O 在单线程守护线程池执行，主线程零阻塞。
 * 模块禁用时通过 {@link #shutdown()} 优雅关闭 executor。</p>
 */
public final class PlayerRadarHistory {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单线程守护线程池：所有文件 I/O 在此执行。 */
    private final ExecutorService writerThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayerRadar-History-Writer");
        t.setDaemon(true);
        return t;
    });

    private volatile BufferedWriter writer;
    private final String filePath;
    private final boolean truncateOnStart;

    public PlayerRadarHistory(String filePath, boolean truncateOnStart) {
        this.filePath = filePath;
        this.truncateOnStart = truncateOnStart;
    }

    /** 在后台线程打开文件。模块启用时调用。 */
    public void open() {
        writerThread.submit(() -> {
            try {
                Path path = Paths.get(filePath);
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StandardOpenOption openOption = truncateOnStart
                        ? StandardOpenOption.TRUNCATE_EXISTING
                        : StandardOpenOption.APPEND;
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, openOption);
                writer.write("=== xYuan's Mod 附近玩家雷达历史记录启动 === " + LocalDateTime.now().format(TIME_FORMAT));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                QueueNoticeAddon.LOG.error("[xYuan's Mod] PlayerRadarHistory open file failed: {}", e.toString());
            }
        });
    }

    /**
     * 记录玩家进入视野事件。
     *
     * @param playerName     玩家名
     * @param distance       进入时距离（米）
     * @param health         进入时血量
     * @param threatResult   进入时威胁评估结果（含完整装备详情）
     */
    public void recordEnter(String playerName, double distance, float health, ThreatResult threatResult) {
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String record = formatEnterRecord(time, playerName, distance, health, threatResult);
        writerThread.submit(() -> appendToFile(record));
    }

    /**
     * 记录玩家离开视野事件。
     *
     * @param playerName        玩家名
     * @param lastDistance      离开时最后距离（米）
     * @param durationSeconds   在视野内持续时长（秒）
     * @param health            离开时血量
     * @param cachedThreatResult 进入时缓存的威胁评估结果
     */
    public void recordLeave(String playerName, double lastDistance, long durationSeconds,
                            float health, ThreatResult cachedThreatResult) {
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String record = formatLeaveRecord(time, playerName, lastDistance, durationSeconds, health, cachedThreatResult);
        writerThread.submit(() -> appendToFile(record));
    }

    /** 清空历史记录文件。 */
    public void clearHistory() {
        writerThread.submit(() -> {
            try {
                if (writer != null) {
                    writer.flush();
                    writer.close();
                }
                Path path = Paths.get(filePath);
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                writer.write("=== 历史记录已清空 === " + LocalDateTime.now().format(TIME_FORMAT));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                QueueNoticeAddon.LOG.error("[xYuan's Mod] PlayerRadarHistory clear failed: {}", e.toString());
            }
        });
    }

    /** 优雅关闭：刷新缓冲并关闭 writer 与 executor。模块禁用时调用。 */
    public void shutdown() {
        writerThread.submit(() -> {
            if (writer != null) {
                try {
                    writer.write("=== 附近玩家雷达历史记录停止 === " + LocalDateTime.now().format(TIME_FORMAT));
                    writer.newLine();
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    QueueNoticeAddon.LOG.error("[xYuan's Mod] PlayerRadarHistory close failed: {}", e.toString());
                }
                writer = null;
            }
        });
        writerThread.shutdown();
    }

    // ---------- 格式化 ----------

    private String formatEnterRecord(String time, String playerName, double distance,
                                     float health, ThreatResult threat) {
        return "========================================\n"
                + "[" + time + "] 进入视野\n"
                + "----------------------------------------\n"
                + "玩家: " + playerName + "\n"
                + "距离: " + String.format("%.1f", distance) + "米 | 持续: - | 血量: " + String.format("%.0f", health) + "/20\n"
                + "威胁等级: " + threat.tier() + " (" + String.format("%.2f", threat.score()) + "分)\n"
                + "----------------------------------------\n"
                + "装备详情:\n"
                + threat.details() + "\n"
                + "========================================\n";
    }

    private String formatLeaveRecord(String time, String playerName, double lastDistance,
                                     long durationSeconds, float health, ThreatResult threat) {
        String duration = formatDuration(durationSeconds);
        return "========================================\n"
                + "[" + time + "] 离开视野\n"
                + "----------------------------------------\n"
                + "玩家: " + playerName + "\n"
                + "距离: " + String.format("%.1f", lastDistance) + "米 | 持续: " + duration + " | 血量: " + String.format("%.0f", health) + "/20\n"
                + "威胁等级: " + threat.tier() + " (" + String.format("%.2f", threat.score()) + "分) [进入时]\n"
                + "----------------------------------------\n"
                + "装备详情: (进入时记录)\n"
                + threat.details() + "\n"
                + "========================================\n";
    }

    /** 持续时长格式化：N分N秒 / N秒。 */
    private String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        }
        long minutes = seconds / 60;
        long remainSec = seconds % 60;
        return minutes + "分" + remainSec + "秒";
    }

    private void appendToFile(String record) {
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        try {
            w.write(record);
            w.newLine();
            w.flush();
        } catch (IOException e) {
            QueueNoticeAddon.LOG.error("[xYuan's Mod] PlayerRadarHistory write failed: {}", e.toString());
        }
    }
}
