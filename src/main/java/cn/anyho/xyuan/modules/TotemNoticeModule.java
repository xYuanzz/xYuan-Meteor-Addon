package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Meteor 附属模块：监控本地玩家不死图腾触发与玩家死亡，并通过飞书自定义机器人 Webhook
 * 推送提醒（图腾触发附带背包剩余图腾数量；死亡携带游戏返回的死亡信息）。
 *
 * <h2>图腾检测原理</h2>
 * <p>当玩家触发不死图腾时，服务端会向所有可见客户端广播一条
 * {@link EntityStatusS2CPacket}，状态码为 {@value #TOTEM_STATUS}。本模块通过
 * Meteor 的 {@link PacketEvent.Receive} 零 Mixin 拦截该数据包，并校验目标实体
 * 是否为本地玩家，避免其他玩家触发图腾时误报。</p>
 *
 * <h2>死亡检测原理</h2>
 * <p>当本地玩家死亡时，服务端会发送一条 {@link DeathMessageS2CPacket}（用于触发
 * 死亡界面），其中 {@code message()} 即为游戏返回的死亡信息（如「被xxx杀死了」）。
 * 本模块拦截该数据包，校验 {@code playerId} 为本地玩家后推送死亡提醒。</p>
 *
 *
 * <h2>剩余图腾统计</h2>
 * <p>触发时遍历 {@link net.minecraft.entity.player.PlayerInventory} 全部格子
 * （主背包 + 快捷栏 + 副手），累计 {@link Items#TOTEM_OF_UNDYING} 堆叠数量。</p>
 *
 * <h2>同步延迟处理</h2>
 * <p>服务端在触发图腾时会立即消耗一个图腾并广播 {@link EntityStatusS2CPacket}，
 * 但客户端的背包同步包（{@code ScreenHandlerSlotUpdateS2CPacket}）可能稍后到达。
 * 若直接读取背包，被消耗的图腾可能仍显示在副手/主手中，导致计数偏多 1、紧急提醒
 * （剩余 0）无法触发。本模块按 vanilla 检查顺序（副手优先 → 主手）扣除被消耗的
 * 那一个图腾，确保计数正确。</p>
 *
 * <p>所有 Webhook 请求在独立守护线程异步执行，不阻塞主线程。
 * 仅客户端生效，无任何服务端逻辑。</p>
 */
public class TotemNoticeModule extends Module {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 实体状态码 35 = 不死图腾触发（vanilla 协议约定）。 */
    private static final byte TOTEM_STATUS = 35;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("高级");

    // ---------- 基础设置 ----------

    /** 剩余图腾数为 0 时使用紧急提醒样式（整行加粗红色 + 「图腾已耗尽-」前缀）。 */
    private final Setting<Boolean> urgentWhenEmpty = sgGeneral.add(new BoolSetting.Builder()
            .name("耗尽时紧急提醒")
            .description("剩余图腾为 0 时整行加粗红色并追加「图腾已耗尽-」前缀，便于快速补图腾。")
            .defaultValue(true)
            .build()
    );

    /** 玩家死亡时推送死亡提醒（携带游戏返回的死亡信息）。 */
    private final Setting<Boolean> enableDeathNotify = sgGeneral.add(new BoolSetting.Builder()
            .name("启用死亡提醒")
            .description("玩家死亡时推送提醒，携带游戏返回的死亡信息（如「被xxx杀死了」）。")
            .defaultValue(true)
            .build()
    );

    // ---------- 高级设置 ----------

    /** 跳过 3c3u.org 白名单校验，允许在任意服务器触发图腾提醒。 */
    private final Setting<Boolean> skipServerCheck = sgAdvanced.add(new BoolSetting.Builder()
            .name("不校验服务器地址")
            .description("跳过 3c3u.org 白名单校验，允许在任意服务器触发图腾提醒。")
            .defaultValue(false)
            .build()
    );

    // ---------- 运行时状态 ----------

    /** 缓存的玩家名（在游戏中捕获，用于通知中区分账号）。 */
    private String playerName;

    public TotemNoticeModule() {
        super(QueueNoticeAddon.CATEGORY, "图腾提醒", "监控不死图腾触发与玩家死亡并通过飞书 Webhook 推送提醒，附带剩余图腾数量与死亡信息。");
    }

    @Override
    public void onActivate() {
        playerName = null;
        capturePlayerName();
    }

    @Override
    public void onDeactivate() {
        // 图腾模块无持久状态需要清理
    }

    // ---------- 事件监听 ----------

    /**
     * 拦截数据包：图腾触发（{@link EntityStatusS2CPacket} 状态码 35）与玩家死亡
     * （{@link DeathMessageS2CPacket}）。
     *
     * <p>注意：{@link EntityStatusS2CPacket#getEntity(net.minecraft.world.World)} 需要
     * 在客户端世界中查询实体；若 {@code mc.world} 为 null（退出服务器瞬间）则跳过图腾处理。</p>
     *
     * <p>死亡数据包仅在本地玩家死亡时由服务端发送（用于触发死亡界面），其中
     * {@code message()} 即为游戏返回的死亡信息（如「被xxx杀死了」）。</p>
     */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        // 玩家死亡：优先处理（独立于图腾触发）
        if (event.packet instanceof DeathMessageS2CPacket deathPacket) {
            handleDeathMessage(deathPacket);
            return;
        }

        if (!(event.packet instanceof EntityStatusS2CPacket packet)) {
            return;
        }
        if (packet.getStatus() != TOTEM_STATUS) {
            return;
        }
        if (mc.world == null || mc.player == null) {
            return;
        }

        // 校验目标实体是否为本地玩家（其他玩家爆图腾不通知）
        Entity entity = packet.getEntity(mc.world);
        if (entity != mc.player) {
            return;
        }

        // 服务器门控
        if (!isOnTargetServer()) {
            return;
        }

        // 统计剩余图腾并发送通知（不做防重复去抖，避免漏掉关键信息）
        capturePlayerName();
        int remaining = countTotemsAfterConsumption();
        sendTotemReminder(remaining);
    }

    /**
     * 处理玩家死亡数据包：仅处理本地玩家的死亡，推送携带死亡信息的提醒。
     *
     * <p>同样受 {@link #isOnTargetServer()} 服务器门控约束，与图腾触发一致。</p>
     */
    private void handleDeathMessage(DeathMessageS2CPacket packet) {
        if (!enableDeathNotify.get()) {
            return;
        }
        if (mc.player == null) {
            return;
        }
        // 仅处理本地玩家的死亡（packet.playerId 为死亡玩家的实体 ID）
        if (packet.playerId() != mc.player.getId()) {
            return;
        }
        if (!isOnTargetServer()) {
            return;
        }
        capturePlayerName();
        String deathMessage = packet.message().getString();
        sendDeathReminder(deathMessage);
    }

    // ---------- 核心逻辑 ----------

    /**
     * 统计触发图腾后背包剩余的不死图腾数量。
     *
     * <p>遍历玩家全部背包格子（{@link net.minecraft.entity.player.PlayerInventory#size()}
     * 已包含主背包 + 快捷栏 + 护甲 + 副手。</p>
     *
     * <p>由于服务端消耗图腾与客户端背包同步之间存在网络延迟，触发时被消耗的图腾
     * 可能仍显示在副手/主手中。按 vanilla 检查顺序（副手优先 → 主手）扣除被消耗的
     * 那一个图腾，确保计数正确，紧急提醒（剩余 0）能正常触发。</p>
     */
    private int countTotemsAfterConsumption() {
        if (mc.player == null) {
            return 0;
        }

        int count = 0;
        // 遍历全部背包格子（已包含主背包 + 快捷栏 + 护甲 + 副手）
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) {
                count += stack.getCount();
            }
        }

        // 扣除本次触发消耗的那一个图腾（vanilla 检查顺序：副手优先 → 主手）
        ItemStack offHand = mc.player.getOffHandStack();
        if (offHand.getItem() == Items.TOTEM_OF_UNDYING) {
            count -= 1;
        } else {
            ItemStack mainHand = mc.player.getMainHandStack();
            if (mainHand.getItem() == Items.TOTEM_OF_UNDYING) {
                count -= 1;
            }
        }

        return Math.max(0, count);
    }

    /**
     * 发送图腾触发提醒。
     *
     *
     * <p>第二行追加「玩家:xxx」便于在同一 Webhook 下区分不同账号；
     * 剩余图腾数为 0 时追加「图腾已耗尽-」前缀（与完成提醒的「排队已完成-」前缀风格一致，
     * 可在设置中关闭）。</p>
     */
    private void sendTotemReminder(int remaining) {
        String markdown = buildMarkdown(remaining);
        FeishuWebhookModule webhook = Modules.get().get(FeishuWebhookModule.class);
        if (webhook != null) {
            webhook.sendMarkdown(markdown);
        }
    }

    /** 构造图腾提醒 Markdown 内容。 */
    private String buildMarkdown(int remaining) {
        capturePlayerName();
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String player = (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
        boolean urgent = (remaining == 0) && urgentWhenEmpty.get();

        StringBuilder sb = new StringBuilder();
        // 第一行：[警告]图腾已被触发 —— 飞书 markdown 加粗用 **text**，颜色用 <font color="...">
        // 紧急提醒（剩余 0）时整行加粗红色，并追加「图腾已耗尽-」前缀以突出状态
        if (urgent) {
            sb.append("<font color=\"red\">**图腾已耗尽-[警告]图腾已被触发**</font>\n");
        } else {
            sb.append("<font color=\"red\">**[警告]图腾已被触发**</font>\n");
        }
        sb.append("玩家:").append(player).append("\n");
        // 第二行：剩余图腾数量（数量为 0 时红色加粗，否则普通显示）
        if (remaining == 0) {
            sb.append("剩余图腾:").append("<font color=\"red\">**").append(remaining).append("**</font>\n");
        } else {
            sb.append("剩余图腾:").append(remaining).append("\n");
        }
        sb.append("触发时间:").append(time);
        return sb.toString();
    }

    /**
     *
     * <p>第一行为游戏返回的死亡信息（{@link DeathMessageS2CPacket#message()}），
     * 以红色加粗显示；若死亡信息为空则回退为「已死亡」。</p>
     */
    private void sendDeathReminder(String deathMessage) {
        String markdown = buildDeathMarkdown(deathMessage);
        FeishuWebhookModule webhook = Modules.get().get(FeishuWebhookModule.class);
        if (webhook != null) {
            webhook.sendMarkdown(markdown);
        }
    }

    /** 死亡提醒 Markdown 内容。 */
    private String buildDeathMarkdown(String deathMessage) {
        capturePlayerName();
        String time = LocalDateTime.now().format(TIME_FORMAT);
        String player = (playerName == null || playerName.isEmpty()) ? "unknown" : playerName;
        String msg = (deathMessage == null || deathMessage.isEmpty()) ? "已死亡" : deathMessage;

        StringBuilder sb = new StringBuilder();
        // 第一行：游戏返回的死亡信息（红色加粗）
        sb.append("<font color=\"red\">**").append(msg).append("**</font>\n");
        sb.append("玩家:").append(player).append("\n");
        sb.append("触发时间:").append(time);
        return sb.toString();
    }

    // ---------- 工具方法 ----------

    /** 在玩家存在时缓存玩家名。 */
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
     * <p>启用「不校验服务器地址」时直接返回 true；否则按地址包含 {@code 3c3u.org} 判定。</p>
     */
    private boolean isOnTargetServer() {
        if (skipServerCheck.get()) {
            return true;
        }
        if (mc.getCurrentServerEntry() == null) {
            return false;
        }
        String address = mc.getCurrentServerEntry().address;
        return address != null && address.toLowerCase().contains("3c3u.org");
    }
}
