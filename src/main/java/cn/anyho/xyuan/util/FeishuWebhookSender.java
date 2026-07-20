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
import java.util.concurrent.TimeUnit;

/**
 * Sends notifications to a Feishu (Lark) custom-bot webhook.
 *
 * <p>Implements the official custom-bot specification described at
 * <a href="https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot">the Feishu docs</a>:
 * <ul>
 *   <li>POST {@code application/json} to the webhook URL.</li>
 *   <li>Uses the <b>interactive card</b> message type ({@code msg_type: "interactive"})
 *       with a single Markdown element, so that Feishu Markdown syntax such as
 *       {@code <font color="red">**XXX**</font>} renders as red bold text.</li>
 *   <li>Optional signature verification: the string {@code timestamp + "\n" + secret}
 *       is used as the <b>HMAC key</b> to HmacSHA256-sign an <b>empty</b> byte
 *       array, then Base64-encoded; {@code timestamp} (seconds, as a string) and
 *       {@code sign} are added to the request body. The timestamp is valid for
 *       1 hour.</li>
 * </ul>
 *
 * <p>All network I/O runs on a dedicated daemon thread so the Minecraft main
 * thread is never blocked. Failures are logged but never propagated to the
 * caller, so the game cannot crash because of a webhook problem.</p>
 */
public final class FeishuWebhookSender {

    /** Shared HTTP client (thread-safe by design). */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /** Single-thread daemon executor: serialises sends which also helps with rate limiting. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Feishu-Webhook-Sender");
        thread.setDaemon(true);
        return thread;
    });

    private FeishuWebhookSender() {
    }

    /**
     * Asynchronously sends an interactive-card message containing the given
     * Markdown content to the given Feishu webhook.
     *
     * <p>The {@code prefix} is prepended to the Markdown content so that Feishu
     * keyword-safety verification can pass.</p>
     *
     * @param webhookUrl       full webhook URL (e.g. {@code https://open.feishu.cn/open-apis/bot/v2/hook/xxx})
     * @param prefix           keyword-safety prefix prepended to the markdown (may be empty / null)
     * @param markdownContent  the markdown body (multi-line, may include {@code <font color="red">**..**</font>})
     * @param enableSign       whether to attach the HmacSHA256 signature
     * @param secret           the bot signing secret (required when {@code enableSign} is true)
     * @param logger           logger used for error reporting
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
                // Never let a webhook failure escape to the caller.
                logger.error("[xYuan's Mod] Feishu webhook send failed: {}", throwable.toString());
            }
        });
    }

    private static void sendSync(String webhookUrl, String prefix, String markdownContent,
                                 boolean enableSign, String secret, Logger logger) throws Exception {
        String fullMarkdown = (prefix == null ? "" : prefix) + markdownContent;

        // Current time in seconds (Feishu requires second-precision timestamps).
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

        // 交互式卡片 (interactive): 一个 markdown 元素承载结构化文本,
        // 使 <font color="red">**..**</font> 红色加粗样式生效.
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

        // Feishu returns HTTP 200 even on logical failures; inspect the JSON "code" field.
        int httpCode = response.statusCode();
        int bizCode = -1;
        String bizMsg = "";
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("code")) bizCode = json.get("code").getAsInt();
            if (json.has("msg")) bizMsg = json.get("msg").getAsString();
        } catch (Throwable ignored) {
            // Non-JSON / empty body; fall back to raw text below.
        }

        if (httpCode == 200 && bizCode == 0) {
            logger.info("[xYuan's Mod] Feishu webhook delivered.");
        } else {
            logger.error("[xYuan's Mod] Feishu webhook rejected: http={}, code={}, msg={}, body={}",
                httpCode, bizCode, bizMsg, response.body());
        }
    }

    /**
     * Generates the Feishu custom-bot signature.
     *
     * <p>Per the official spec: use {@code timestamp + "\n" + secret} as the
     * HmacSHA256 key to sign an <b>empty</b> byte array, then Base64-encode.
     * The timestamp is expressed in seconds and is valid for 1 hour.</p>
     */
    static String genSign(String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(new byte[]{});

        return Base64.getEncoder().encodeToString(signData);
    }

    /** Best-effort shutdown hook (invoked from the addon / module deactivate if desired). */
    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
