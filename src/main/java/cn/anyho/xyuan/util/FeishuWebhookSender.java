package cn.anyho.xyuan.util;

import cn.anyho.xyuan.QueueNoticeAddon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 飞书自定义机器人 Webhook 发送器。
 *
 * <p>实现官方自定义机器人规范：POST application/json，使用交互式卡片（msg_type: "interactive"）
 * + 单个 Markdown 元素承载结构化文本，使 {@code <font color="red">**XXX**</font>} 红色加粗生效。
 * 可选签名校验：以 {@code timestamp + "\n" + secret} 作为 HmacSHA256 密钥对空字节数组签名，
 * 再 Base64 编码，timestamp 为秒级字符串（1 小时有效）。</p>
 *
 * <p>所有网络 I/O 在独立守护线程异步执行，不阻塞 Minecraft 主线程。
 * 失败仅记录日志，不会向上抛出异常导致游戏崩溃。</p>
 *
 * <p><b>重试机制</b>：只对<b>临时性失败</b>重试（限流 429 / 业务码 9499 / 5xx / 网络异常），
 * 最多 {@link #MAX_RETRIES} 次，间隔递增（1s / 2s / 3s）。签名错误、token 失效这类
 * 永久性失败立即放弃——重试它们只会白占唯一的一条发送线程，把后续提醒一起拖住。</p>
 *
 * <p><b>队列</b>：待发队列<b>有界</b>（{@link #QUEUE_CAPACITY}）。单线程 + 递增退避天然起限流作用，
 * 但网络长时间不可达时队列仍会积压；有界 + 丢弃计数保证不会无上限吃内存，
 * 且丢弃行为是可见的（日志），不会静默丢消息。</p>
 */
public final class FeishuWebhookSender {

    /** 最大重试次数（首次发送 + 重试次数）。 */
    private static final int MAX_RETRIES = 3;

    /** 待发队列容量上限。 */
    private static final int QUEUE_CAPACITY = 256;

    /** 飞书「请求过于频繁」业务码 —— 属于可重试。 */
    private static final int CODE_RATE_LIMITED = 9499;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 因队列满而丢弃的消息数。 */
    private static final AtomicLong DROPPED = new AtomicLong();

    private static final BlockingQueue<Runnable> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, QUEUE,
            r -> {
                Thread thread = new Thread(r, "Feishu-Webhook-Sender");
                thread.setDaemon(true);
                return thread;
            },
            (task, executor) -> {
                long total = DROPPED.incrementAndGet();
                // 节流输出，避免丢消息时把日志刷爆
                if (total == 1 || total % 50 == 0) {
                    QueueNoticeAddon.LOG.warn(
                            "[xYuan's Mod] 飞书提醒待发队列已满，累计丢弃 {} 条（发送线程被慢速重试占住或网络不可达）。",
                            total);
                }
            }
    );

    /** 单次发送的结果，用于区分「值得重试」与「重试也没用」。 */
    private enum Outcome {
        /** 飞书已接受。 */
        SUCCESS,
        /** 临时性失败（限流 / 5xx / 网络异常），可以重试。 */
        RETRYABLE,
        /** 配置类失败（签名错误 / token 失效 / 地址非法等），重试无意义。 */
        PERMANENT
    }

    private FeishuWebhookSender() {
    }

    /**
     * 异步发送交互式卡片消息。
     *
     * @param webhookUrl      完整 Webhook URL
     * @param prefix          关键词安全校验前缀（可为空 / null）
     * @param markdownContent Markdown 正文（多行，可含红色加粗样式）
     * @param enableSign      是否附加 HmacSHA256 签名
     * @param secret          签名密钥（enableSign 为 true 时必填）
     * @param logger          日志记录器
     */
    public static void sendAsync(String webhookUrl, String prefix, String markdownContent,
                                 boolean enableSign, String secret, Logger logger) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        // 先校验地址：永久性失败的请求没必要进队列占坑
        try {
            parseWebhookUri(webhookUrl);
        } catch (IllegalArgumentException e) {
            logger.error("[xYuan's Mod] Webhook 地址非法，已跳过发送: {}", e.getMessage());
            return;
        }

        EXECUTOR.submit(() -> {
            try {
                sendWithRetry(webhookUrl, prefix, markdownContent, enableSign, secret, logger);
            } catch (Throwable throwable) {
                logger.error("[xYuan's Mod] Feishu webhook send failed: {}", throwable.toString());
            }
        });
    }

    /**
     * 带重试的发送：<b>只对临时性失败</b>重试，最多 {@link #MAX_RETRIES} 次。
     *
     * <p>原实现把所有非成功结果一律当作可重试，包括签名错误、token 失效等永久性失败。
     * 最坏情况下唯一的一条发送线程会被占用约 4×15s（请求超时）+ 1+2+3s（退避）≈ 66 秒，
     * 期间后面所有提醒（图腾、玩家预警这些时效性强的）都在排队，等到发出去已经没意义了。</p>
     */
    private static void sendWithRetry(String webhookUrl, String prefix, String markdownContent,
                                      boolean enableSign, String secret, Logger logger) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            Outcome outcome = sendOnce(webhookUrl, prefix, markdownContent, enableSign, secret, logger);

            if (outcome == Outcome.SUCCESS) {
                return;
            }
            if (outcome == Outcome.PERMANENT) {
                logger.error("[xYuan's Mod] Feishu webhook 发送失败且不可重试，已放弃。请检查 Webhook 地址与签名密钥。");
                return;
            }
            if (attempt < MAX_RETRIES) {
                long waitMs = 1000L * (attempt + 1);
                logger.warn("[xYuan's Mod] Feishu webhook 第 {} 次尝试失败，{}ms 后重试", attempt + 1, waitMs);
                if (!sleep(waitMs)) {
                    return;
                }
            }
        }
        logger.error("[xYuan's Mod] Feishu webhook 重试 {} 次后仍失败", MAX_RETRIES);
    }

    /** @return false 表示线程被中断，调用方应立即放弃。 */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 发送一次请求并判定结果类型。 */
    private static Outcome sendOnce(String webhookUrl, String prefix, String markdownContent,
                                    boolean enableSign, String secret, Logger logger) {
        HttpRequest request;
        try {
            request = buildRequest(webhookUrl, prefix, markdownContent, enableSign, secret, logger);
        } catch (IllegalArgumentException e) {
            logger.error("[xYuan's Mod] Webhook 地址非法，已跳过发送: {}", e.getMessage());
            return Outcome.PERMANENT;
        } catch (Exception e) {
            logger.error("[xYuan's Mod] 构造飞书请求失败: {}", e.toString());
            return Outcome.PERMANENT;
        }

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return interpret(response, logger);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.PERMANENT;
        } catch (IOException e) {
            logger.error("[xYuan's Mod] Feishu webhook 网络异常: {}", e.toString());
            return Outcome.RETRYABLE;
        }
    }

    /** 构造请求体与请求对象。 */
    private static HttpRequest buildRequest(String webhookUrl, String prefix, String markdownContent,
                                            boolean enableSign, String secret, Logger logger) throws Exception {
        URI uri = parseWebhookUri(webhookUrl);

        String fullMarkdown = (prefix == null ? "" : prefix) + markdownContent;
        long timestamp = System.currentTimeMillis() / 1000L;

        JsonObject body = new JsonObject();
        if (enableSign) {
            if (secret == null || secret.isEmpty()) {
                logger.warn("[xYuan's Mod] 已开启签名校验但未设置密钥，跳过签名字段。");
            } else {
                body.addProperty("timestamp", String.valueOf(timestamp));
                body.addProperty("sign", genSign(secret, timestamp));
            }
        }

        // 交互式卡片：一个 markdown 元素承载结构化文本
        body.addProperty("msg_type", "interactive");

        JsonObject card = new JsonObject();
        JsonArray elements = new JsonArray();

        JsonObject markdownElement = new JsonObject();
        markdownElement.addProperty("tag", "markdown");
        markdownElement.addProperty("content", fullMarkdown);
        elements.add(markdownElement);

        card.add("elements", elements);
        body.add("card", card);

        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 校验 Webhook 地址是否是可用的网络地址。
     *
     * <p>只要求「绝对的 http/https 地址 + 有主机名」，用于拦掉 {@code file:}、{@code jar:}、
     * 相对路径这类明显不是网络请求的输入。</p>
     *
     * <p>刻意<b>不做域名白名单</b>：飞书支持私有化部署使用自定义域名，白名单会误伤正常用法；
     * 而本模块的威胁模型是「用户填自己的地址」，加白名单收益有限。</p>
     *
     * @throws IllegalArgumentException 地址不合法
     */
    private static URI parseWebhookUri(String webhookUrl) {
        URI uri;
        try {
            uri = new URI(webhookUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("无法解析为 URI: " + e.getMessage(), e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("只支持 http/https，实际为 " + scheme);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("缺少主机名: " + webhookUrl);
        }
        return uri;
    }

    /** 把 HTTP 响应映射为发送结果。 */
    private static Outcome interpret(HttpResponse<String> response, Logger logger) {
        int httpCode = response.statusCode();
        int bizCode = -1;
        String bizMsg = "";
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("code")) bizCode = json.get("code").getAsInt();
            if (json.has("msg")) bizMsg = json.get("msg").getAsString();
        } catch (Throwable ignored) {
            // 响应体不是 JSON，交给状态码判断
        }

        if (httpCode == 200 && bizCode == 0) {
            logger.info("[xYuan's Mod] Feishu webhook delivered.");
            return Outcome.SUCCESS;
        }

        logger.error("[xYuan's Mod] Feishu webhook rejected: http={}, code={}, msg={}, body={}",
                httpCode, bizCode, bizMsg, response.body());

        boolean retryable = httpCode == 429 || httpCode >= 500 || bizCode == CODE_RATE_LIMITED;
        return retryable ? Outcome.RETRYABLE : Outcome.PERMANENT;
    }

    /**
     * 生成飞书自定义机器人签名。
     *
     * <p>以 {@code timestamp + "\n" + secret} 作为 HmacSHA256 密钥对空字节数组签名，再 Base64 编码。
     * timestamp 为秒级字符串，有效期为 1 小时。</p>
     */
    static String genSign(String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(new byte[]{});

        return Base64.getEncoder().encodeToString(signData);
    }
}
