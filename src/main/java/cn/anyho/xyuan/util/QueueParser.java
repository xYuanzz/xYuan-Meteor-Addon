package cn.anyho.xyuan.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 队列文本识别工具。
 *
 * <p>适配 3c3u.org 等排队服常见的中英文队列消息格式（聊天、标题、副标题、动作栏）。
 * 匹配均为轻量 {@link Pattern} 工作，适合在每次数据包事件上执行。</p>
 */
public final class QueueParser {

    private QueueParser() {
    }

    /** 队列位置正则。捕获组 1 为数字位置，按从严格到宽松顺序匹配，首个命中即返回。 */
    private static final Pattern[] QUEUE_PATTERNS = {
            Pattern.compile("你在队列中第\\s*(\\d+)\\s*位"),
            Pattern.compile("正在.*?队列位置[:：]\\s*(\\d+)"),
            Pattern.compile("队列位置[:：]\\s*(\\d+)"),
            Pattern.compile("排队.*?第\\s*(\\d+)\\s*位"),
            Pattern.compile("Position in queue[:：]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Queue[^\\d]*?position[:：]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
    };

    /**
     * 从文本中提取队列位置。
     *
     * @param text 聊天 / 标题 / 动作栏的纯文本内容
     * @return 解析出的位置（{@code >= 0}），未找到返回 {@code -1}
     */
    public static int parsePosition(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }

        for (Pattern pattern : QUEUE_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                try {
                    int position = Integer.parseInt(matcher.group(1));
                    if (position >= 0) {
                        return position;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return -1;
    }
}
