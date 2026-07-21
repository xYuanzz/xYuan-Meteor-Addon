package cn.anyho.xyuan.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 */
public final class FeishuWebhookSender {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Feishu-Webhook-Sender");
        thread.setDaemon(true);
        return thread;
    });

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

        EXECUTOR.submit(() -> {
            try {
                sendSync(webhookUrl, prefix, markdownContent, enableSign, secret, logger);
            } catch (Throwable throwable) {
                logger.error("[xYuan's Mod] Feishu webhook send failed: {}", throwable.toString());
            }
        });
    }

    private static void sendSync(String webhookUrl, String prefix, String markdownContent,
                                 boolean enableSign, String secret, Logger logger) throws Exception {
        String fullMarkdown = (prefix == null ? "" : prefix) + markdownContent;

        long timestamp = System.currentTimeMillis() / 1000L;

        JsonObject body = new JsonObject();
        if (enableSign) {
            if (secret == null || secret.isEmpty()) {
                logger.warn("[xYuan's Mod] Signature verification is enabled but no secret is set; skipping sign fields.");
            } else {
                String sign = genSign(secret, timestamp);
                body.addProperty("timestamp", String.valueOf(timestamp));
                body.addProperty("sign", sign);
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        // 飞书逻辑失败也返回 HTTP 200，需检查 JSON code 字段
        int httpCode = response.statusCode();
        int bizCode = -1;
        String bizMsg = "";
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("code")) bizCode = json.get("code").getAsInt();
            if (json.has("msg")) bizMsg = json.get("msg").getAsString();
        } catch (Throwable ignored) {
        }

        if (httpCode == 200 && bizCode == 0) {
            logger.info("[xYuan's Mod] Feishu webhook delivered.");
        } else {
            logger.error("[xYuan's Mod] Feishu webhook rejected: http={}, code={}, msg={}, body={}",
                    httpCode, bizCode, bizMsg, response.body());
        }
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
