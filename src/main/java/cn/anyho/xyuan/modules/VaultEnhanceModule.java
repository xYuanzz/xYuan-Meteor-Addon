package cn.anyho.xyuan.modules;

import cn.anyho.xyuan.QueueNoticeAddon;
import cn.anyho.xyuan.blockesp.BlockStateFilters;
import cn.anyho.xyuan.blockesp.IRefreshableBlockESP;
import cn.anyho.xyuan.vault.VaultRecord;
import cn.anyho.xyuan.vault.VaultRecordStore;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PlaySoundPacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ProvidedStringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.blockesp.BlockESP;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宝库增强：监听本地玩家右键宝库并确认解锁成功后，记录坐标与时间，按服务器/世界标签/维度/密室组持久化。
 * 维护的“已开坐标集合”会同步给 BlockESP 的状态过滤（rewarded 虚拟属性），
 * 使方块透视能识别未开启(rewarded=true)/已开启(rewarded=false)的宝库。
 */
public class VaultEnhanceModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    /** 当前世界标签：从已维护标签列表中选择，也可输入新标签（自动入列表，避免遗忘丢失）。 */
    private final Setting<String> currentWorldLabel = sgGeneral.add(new ProvidedStringSetting.Builder()
        .name("当前世界标签")
        .description("当前所在子服/世界存档的标签。用于区分同一服务器下的不同世界。输入新标签会自动加入下方列表。")
        .defaultValue("默认世界")
        .supplier(this::availableLabels)
        .onChanged(s -> onWorldLabelChanged())
        .build()
    );

    /** 世界标签列表：手动维护所有标签，避免输入后遗忘导致数据孤立。 */
    private final Setting<List<String>> worldLabels = sgGeneral.add(new StringListSetting.Builder()
        .name("世界标签列表")
        .description("所有用过的世界标签。切换世界时从“当前世界标签”选择，新增标签会自动加入这里。")
        .defaultValue("默认世界")
        .build()
    );

    /** 右键后等待解锁成功声音的时间窗口（毫秒）。 */
    private final Setting<Integer> detectWindowMs = sgGeneral.add(new IntSetting.Builder()
        .name("解锁检测窗口")
        .description("右键宝库后，等待解锁成功声音的时间窗口（毫秒）。超时未听到成功声则不记录。")
        .defaultValue(500)
        .min(100)
        .max(2000)
        .sliderRange(100, 2000)
        .build()
    );

    /** 本地玩家右键宝库的待确认记录：posKey → 右键时间戳。 */
    private final ConcurrentHashMap<Long, Long> pendingInteracts = new ConcurrentHashMap<>();

    private String loadedServerKey;
    private String lastDimension;

    public VaultEnhanceModule() {
        super(QueueNoticeAddon.CATEGORY, "宝库增强", "记录本地玩家开启过的宝库坐标与时间，按服务器/世界/维度/密室分组持久化，并让方块透视通过 rewarded 虚拟属性过滤未开/已开宝库。");
    }

    @Override
    public void onActivate() {
        reloadForCurrentContext(true);
    }

    @Override
    public void onDeactivate() {
        pendingInteracts.clear();
        BlockStateFilters.setOpenedPositions(null);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        pendingInteracts.clear();
    }

    /** 本地玩家右键方块：若目标是宝库，记入待确认。 */
    @EventHandler(priority = EventPriority.LOW)
    private void onInteractBlock(InteractBlockEvent event) {
        if (mc.world == null || mc.player == null) return;
        if (!(event.result instanceof BlockHitResult hit)) return;
        if (hit.getType() == HitResult.Type.MISS) return;

        BlockPos pos = hit.getBlockPos();
        if (mc.world.getBlockState(pos).getBlock() != Blocks.VAULT) return;

        pendingInteracts.put(pos.asLong(), System.currentTimeMillis());
    }

    /**
     * 收到声音包：若是宝库插入钥匙声，邻近匹配最近的待确认右键并记录解锁。
     *
     * <p>服务端播放声音的坐标可能与方块坐标存在偏移（如方块顶部、+0.5 中心点），
     * 精确匹配会导致漏记。这里在时间窗口内的待确认右键里取距离声音坐标最近的一项，
     * 阈值 2 格内即视为同一宝库，并以右键坐标（而非声音坐标）记录，保证与方块坐标一致。</p>
     */
    @EventHandler
    private void onPlaySound(PlaySoundPacketEvent event) {
        if (mc.world == null || mc.player == null) return;
        PlaySoundS2CPacket packet = event.packet;
        if (!packet.getSound().matchesId(SoundEvents.BLOCK_VAULT_INSERT_ITEM.id())) return;

        double sx = packet.getX();
        double sy = packet.getY();
        double sz = packet.getZ();
        long now = System.currentTimeMillis();
        long window = detectWindowMs.get();

        Long bestKey = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Map.Entry<Long, Long> e : pendingInteracts.entrySet()) {
            long elapsed = now - e.getValue();
            if (elapsed > window || elapsed < 0) continue;
            BlockPos p = BlockPos.fromLong(e.getKey());
            double dx = p.getX() + 0.5 - sx;
            double dy = p.getY() + 0.5 - sy;
            double dz = p.getZ() + 0.5 - sz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestKey = e.getKey();
            }
        }
        // 阈值 4.0 = 2 格距离平方，容忍声音在方块顶部/中心偏移，避免误匹配远处宝库
        if (bestKey == null || bestDistSq > 4.0) return;

        pendingInteracts.remove(bestKey);
        recordUnlock(BlockPos.fromLong(bestKey));
    }

    /** 每 tick 检测服务器/维度变化，变化时重新加载对应记录；顺带清理超时待确认项。 */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;

        long now = System.currentTimeMillis();
        long window = detectWindowMs.get();
        pendingInteracts.entrySet().removeIf(e -> now - e.getValue() > window);

        String serverKey = VaultRecordStore.computeServerKey(mc);
        String dimension = mc.world.getRegistryKey().getValue().toString();

        if (!serverKey.equals(loadedServerKey) || dimension == null || !dimension.equals(lastDimension)) {
            reloadForCurrentContext(false);
        }
    }

    /** 当前世界标签改变：刷新已开坐标集合与透视。 */
    private void onWorldLabelChanged() {
        String label = currentWorldLabel.get();
        if (label != null && !label.isBlank() && !worldLabels.get().contains(label)) {
            List<String> list = new java.util.ArrayList<>();
            for (String s : worldLabels.get()) if (!list.contains(s)) list.add(s);
            list.add(label);
            worldLabels.set(list);
        }
        updateOpenedPositions();
        refreshBlockESP();
    }

    /** 提供“当前世界标签”下拉选项：合并已维护列表与当前服务器已有标签。 */
    private String[] availableLabels() {
        Set<String> merged = new LinkedHashSet<>(worldLabels.get());
        merged.addAll(VaultRecordStore.get().getWorldLabels());
        return merged.toArray(new String[0]);
    }

    /** 切换服务器/维度时重载记录。 */
    private void reloadForCurrentContext(boolean serverChanged) {
        String serverKey = VaultRecordStore.computeServerKey(mc);
        if (serverChanged || !serverKey.equals(loadedServerKey)) {
            VaultRecordStore.get().load(serverKey);
            loadedServerKey = serverKey;
        }
        lastDimension = mc.world != null ? mc.world.getRegistryKey().getValue().toString() : null;
        pendingInteracts.clear();
        updateOpenedPositions();
        refreshBlockESP();
    }

    /** 记录一次成功解锁并刷新透视。 */
    private void recordUnlock(BlockPos pos) {
        if (mc.world == null) return;
        String dimension = mc.world.getRegistryKey().getValue().toString();
        String group = VaultRecordStore.chamberGroupOf(pos);
        VaultRecord record = new VaultRecord(pos, dimension, group);

        boolean added = VaultRecordStore.get().addRecord(currentWorldLabel.get(), dimension, record);
        if (added) {
            info("记录宝库解锁: " + pos.toShortString()
                + " (世界: " + currentWorldLabel.get()
                + " / 维度: " + dimension
                + " / 组: " + group
                + " / 时间: " + record.openedAt + ")");
            updateOpenedPositions();
            refreshBlockESP();
        }
    }

    /** 将当前世界标签+维度下的已开坐标集合同步给 BlockESP 过滤。 */
    private void updateOpenedPositions() {
        if (mc.world == null || currentWorldLabel.get() == null) {
            BlockStateFilters.setOpenedPositions(null);
            return;
        }
        String dimension = mc.world.getRegistryKey().getValue().toString();
        Set<Long> keys = VaultRecordStore.get().getOpenedPositionKeys(currentWorldLabel.get(), dimension);
        BlockStateFilters.setOpenedPositions(new HashSet<>(keys));
    }

    /** 触发 BlockESP 重新扫描以反映 rewarded 过滤。 */
    private void refreshBlockESP() {
        BlockESP blockEsp = Modules.get().get(BlockESP.class);
        if (blockEsp instanceof IRefreshableBlockESP aware) aware.xyuanRefreshVaultFilter();
    }
}
