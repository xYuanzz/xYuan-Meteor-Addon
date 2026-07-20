package cn.anyho.xyuan.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates queue-text recognition.
 *
 * <p>Adapted from the regex approach used by
 * <a href="https://github.com/SnowZhouer/queue-notice-mod">queue-notice-mod</a>,
 * broadened to cover common Chinese and English queue-server message formats
 * (chat messages, titles, subtitles and the action bar).</p>
 *
 * <p>All matching is lightweight {@link Pattern} work, suitable for running on
 * every incoming chat/packet event without impacting performance.</p>
 */
public final class QueueParser {

    private QueueParser() {
    }

    /**
     * Queue-position patterns. Capture group 1 is the numeric position.
     * Ordered from most specific to most permissive; the first match wins.
     */
    private static final Pattern[] QUEUE_PATTERNS = {
        // 你在队列中第 50 位
        Pattern.compile("你在队列中第\\s*(\\d+)\\s*位"),
        // 正在游玩 ... 队列位置：50
        Pattern.compile("正在.*?队列位置[:：]\\s*(\\d+)"),
        // 队列位置：50  /  队列位置: 50
        Pattern.compile("队列位置[:：]\\s*(\\d+)"),
        // 排队中第 50 位
        Pattern.compile("排队.*?第\\s*(\\d+)\\s*位"),
        // Position in queue: 50
        Pattern.compile("Position in queue[:：]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
        // Queue position: 50  /  Queue ... position: 50
        Pattern.compile("Queue[^\\d]*?position[:：]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
    };

    /**
     * Tries to extract a queue position from a piece of received text.
     *
     * @param text the plain-text content of a chat message / title / action bar
     * @return the parsed position ({@code >= 0}), or {@code -1} if no queue
     *         information could be found
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
                    // Should never happen because the group is \\d+, but guard anyway.
                }
            }
        }

        return -1;
    }
}
