package cn.anyho.xyuan.vault;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 宝库解锁记录的持久化存储。
 * 按服务器分文件：&lt;configdir&gt;/xyuan-vault-records/&lt;serverKey&gt;.json
 * 内部按 世界标签 → 维度 → 密室组(3x3区块网格) → 记录列表 分层。
 *
 * <p><b>写盘策略</b>：调用方（主线程）只做内存变更与去重，落盘交给后台守护线程并做
 * {@link #SAVE_DEBOUNCE_MS} 防抖合并，避免每次解锁都在渲染线程上同步写整个文件。
 * 退出游戏 / 模块停用时请调用 {@link #flush()}，把防抖窗口内的改动立即落盘。</p>
 *
 * <p><b>写入原子性</b>：落盘采用「同目录临时文件 → fsync → 原子改名」，
 * 任何时刻目标文件要么是旧的完整内容、要么是新的完整内容，不会出现半截 JSON。</p>
 */
public final class VaultRecordStore {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 记录文件的结构版本，写入旁挂的 &lt;serverKey&gt;.schema 文件，便于将来做数据迁移。 */
    private static final int SCHEMA_VERSION = 1;

    /** 落盘防抖窗口：窗口内的多次变更合并成一次写入。 */
    private static final long SAVE_DEBOUNCE_MS = 2000L;

    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("xyuan-vault-records");

    /**
     * 服务器地址清洗用的预编译正则。
     * 原实现每次调用 {@code String.replaceAll} 都会重新编译一次 Pattern，而它被
     * {@code VaultEnhanceModule} 的每 tick 逻辑间接调用，属于纯浪费。
     */
    private static final Pattern UNSAFE_SERVER_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    private static final Type DATA_TYPE =
            new TypeToken<Map<String, Map<String, Map<String, Map<String, List<VaultRecord>>>>>>() {
            }.getType();

    private static VaultRecordStore instance;

    /** serverKey → 世界标签 → 维度 → 密室组 → 记录列表 */
    private Map<String, Map<String, Map<String, Map<String, List<VaultRecord>>>>> data = new LinkedHashMap<>();
    private String currentServerKey;

    /** 后台写盘线程：把落盘从主线程挪走。 */
    private final ScheduledExecutorService saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VaultRecordStore-Writer");
        t.setDaemon(true);
        return t;
    });

    /**
     * 保护 pendingSave 与 dirty 的锁。
     * 注意：持有本锁时绝不获取实例锁（save() 是 synchronized），否则与「持实例锁调用 scheduleSave()」
     * 形成锁序反转。
     */
    private final Object pendingLock = new Object();
    private ScheduledFuture<?> pendingSave;
    private boolean dirty;

    private VaultRecordStore() {
    }

    public static synchronized VaultRecordStore get() {
        if (instance == null) instance = new VaultRecordStore();
        return instance;
    }

    /** 计算当前会话的服务器标识：多人用地址，单机用 singleplayer。 */
    public static String computeServerKey(net.minecraft.client.MinecraftClient mc) {
        if (mc.getCurrentServerEntry() != null) {
            return sanitize(mc.getCurrentServerEntry().address);
        }
        return "singleplayer";
    }

    private static String sanitize(String s) {
        return s == null || s.isBlank() ? "unknown" : UNSAFE_SERVER_CHARS.matcher(s).replaceAll("_");
    }

    /** 3x3 区块网格分组 ID（取网格左下角区块坐标）。 */
    public static String chamberGroupOf(BlockPos pos) {
        int gx = Math.floorDiv(pos.getX() >> 4, 3) * 3;
        int gz = Math.floorDiv(pos.getZ() >> 4, 3) * 3;
        return gx + "_" + gz;
    }

    /**
     * 切换服务器时加载对应文件。
     *
     * <p>解析失败时把文件改名留档（{@code .json.corrupt-<时间戳>}），而不是直接丢弃。
     * 注意这里 catch 的是 {@link Exception}：Gson 解析失败抛的是 {@code JsonSyntaxException}，
     * 那是 {@code RuntimeException}，只 catch {@code IOException} 会漏过它、把异常冒到调用方。</p>
     */
    public synchronized void load(String serverKey) {
        this.currentServerKey = serverKey;
        Path file = DIR.resolve(serverKey + ".json");
        try {
            Files.createDirectories(DIR);
            if (!Files.exists(file)) {
                data = new LinkedHashMap<>();
                return;
            }
            String json = Files.readString(file);
            Map<String, Map<String, Map<String, Map<String, List<VaultRecord>>>>> loaded =
                    GSON.fromJson(json, DATA_TYPE);
            data = loaded != null ? loaded : new LinkedHashMap<>();
        } catch (Exception e) {
            LOG.error("[VaultRecordStore] 加载失败，已将文件改名留档: {}", serverKey, e);
            backupCorruptFile(file, serverKey);
            data = new LinkedHashMap<>();
        }
    }

    /** 把无法解析的文件改名留档，避免下一次写入直接把用户数据覆盖掉。 */
    private static void backupCorruptFile(Path file, String serverKey) {
        if (!Files.exists(file)) {
            return;
        }
        Path target = file.resolveSibling(serverKey + ".json.corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            LOG.warn("[VaultRecordStore] 损坏文件已保留在 {}", target);
        } catch (IOException moveFailed) {
            LOG.error("[VaultRecordStore] 损坏文件备份失败: {}", moveFailed.toString());
        }
    }

    /**
     * 立即把当前数据原子地写到磁盘。
     *
     * <p>原实现直接用 {@code Files.writeString} 覆写目标文件——该调用是「先清空再写」，
     * 若在中间被杀进程 / 断电 / 磁盘写满，文件会变成半截 JSON，用户积累的宝库记录全部丢失。
     * 现在改为「临时文件 → fsync → 原子改名」，并在改名前留一份上一次成功的副本。</p>
     */
    public synchronized void save() {
        if (currentServerKey == null) {
            return;
        }
        Path target = DIR.resolve(currentServerKey + ".json");
        Path tmp = DIR.resolve(currentServerKey + ".json.tmp");
        Path backup = DIR.resolve(currentServerKey + ".json.bak");
        try {
            Files.createDirectories(DIR);
            byte[] payload = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);

            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(payload));
                channel.force(true);
            }

            if (Files.exists(target)) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 个别文件系统不支持原子改名，退化为普通覆盖
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            writeSchemaVersion();
        } catch (Exception e) {
            LOG.error("[VaultRecordStore] 保存失败: {}", currentServerKey, e);
        }
    }

    /**
     * 立刻落盘并取消防抖中的任务。
     * 用于退出游戏 / 模块停用等场景，保证防抖窗口内的改动不会丢。
     */
    public void flush() {
        synchronized (pendingLock) {
            dirty = false;
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }
        save();
    }

    /** 标记数据已变更，延迟 {@link #SAVE_DEBOUNCE_MS} 后在后台线程合并落盘。 */
    private void scheduleSave() {
        synchronized (pendingLock) {
            dirty = true;
            if (pendingSave != null && !pendingSave.isDone()) {
                return;
            }
            pendingSave = saveExecutor.schedule(this::flushIfDirty, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushIfDirty() {
        synchronized (pendingLock) {
            pendingSave = null;
            if (!dirty) {
                return;
            }
            dirty = false;
        }
        save();
    }

    /**
     * 写入结构版本到旁挂的 .schema 文件。
     * 之所以不改数据文件本身：该文件顶层就是「服务器标识 → 数据」的映射，
     * 加版本字段会与服务器标识争用同一层键空间；旁挂文件对既有数据完全无损。
     */
    private void writeSchemaVersion() {
        try {
            Files.writeString(DIR.resolve(currentServerKey + ".schema"), Integer.toString(SCHEMA_VERSION));
        } catch (IOException e) {
            LOG.warn("[VaultRecordStore] 写入结构版本失败: {}", e.toString());
        }
    }

    /** 新增一条解锁记录（重复坐标不重复添加）。落盘为异步防抖，见类注释。 */
    public synchronized boolean addRecord(String worldLabel, String dimension, VaultRecord record) {
        if (currentServerKey == null) return false;
        Map<String, Map<String, Map<String, List<VaultRecord>>>> worlds = data.computeIfAbsent(currentServerKey, k -> new LinkedHashMap<>());
        Map<String, Map<String, List<VaultRecord>>> dims = worlds.computeIfAbsent(worldLabel, k -> new LinkedHashMap<>());
        Map<String, List<VaultRecord>> groups = dims.computeIfAbsent(dimension, k -> new LinkedHashMap<>());
        List<VaultRecord> records = groups.computeIfAbsent(record.chamberGroup, k -> new ArrayList<>());

        for (VaultRecord r : records) {
            if (r == null) continue;
            if (r.x == record.x && r.y == record.y && r.z == record.z) return false;
        }
        records.add(record);
        scheduleSave();
        return true;
    }

    /** 返回当前服务器下某世界标签+维度的所有已开宝库坐标 key 集合（BlockPos.asLong）。 */
    public synchronized Set<Long> getOpenedPositionKeys(String worldLabel, String dimension) {
        if (currentServerKey == null) return Collections.emptySet();
        Map<String, Map<String, Map<String, List<VaultRecord>>>> worlds = data.get(currentServerKey);
        if (worlds == null) return Collections.emptySet();
        Map<String, Map<String, List<VaultRecord>>> dims = worlds.get(worldLabel);
        if (dims == null) return Collections.emptySet();
        Map<String, List<VaultRecord>> groups = dims.get(dimension);
        if (groups == null) return Collections.emptySet();

        Set<Long> keys = new HashSet<>();
        for (List<VaultRecord> records : groups.values()) {
            for (VaultRecord r : records) {
                if (r == null) continue;
                keys.add(BlockPos.asLong(r.x, r.y, r.z));
            }
        }
        return keys;
    }

    /** 当前服务器所有世界标签列表（供 UI 切换）。 */
    public synchronized List<String> getWorldLabels() {
        if (currentServerKey == null) return Collections.emptyList();
        Map<String, Map<String, Map<String, List<VaultRecord>>>> worlds = data.get(currentServerKey);
        if (worlds == null) return Collections.emptyList();
        return new ArrayList<>(worlds.keySet());
    }
}
