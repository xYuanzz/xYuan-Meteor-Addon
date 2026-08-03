package cn.anyho.xyuan.vault;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Type;

/**
 * 宝库解锁记录的持久化存储。
 * 按服务器分文件：&lt;configdir&gt;/xyuan-vault-records/&lt;serverKey&gt;.json
 * 内部按 世界标签 → 维度 → 密室组(3x3区块网格) → 记录列表 分层。
 */
public final class VaultRecordStore {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("xyuan-vault-records");

    private static VaultRecordStore instance;

    /** serverKey → 世界标签 → 维度 → 密室组 → 记录列表 */
    private Map<String, Map<String, Map<String, Map<String, List<VaultRecord>>>>> data = new LinkedHashMap<>();
    private String currentServerKey;

    private VaultRecordStore() {}

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
        return s == null || s.isBlank() ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 3x3 区块网格分组 ID（取网格左下角区块坐标）。 */
    public static String chamberGroupOf(BlockPos pos) {
        int gx = Math.floorDiv(pos.getX() >> 4, 3) * 3;
        int gz = Math.floorDiv(pos.getZ() >> 4, 3) * 3;
        return gx + "_" + gz;
    }

    /** 切换服务器时加载对应文件。 */
    public synchronized void load(String serverKey) {
        this.currentServerKey = serverKey;
        try {
            Files.createDirectories(DIR);
            Path file = DIR.resolve(serverKey + ".json");
            if (Files.exists(file)) {
                String json = Files.readString(file);
                Type type = new TypeToken<Map<String, Map<String, Map<String, Map<String, List<VaultRecord>>>>>>() {}.getType();
                Map<String, Map<String, Map<String, Map<String, List<VaultRecord>>>>> loaded =
                    GSON.fromJson(json, type);
                data = loaded != null ? loaded : new LinkedHashMap<>();
            } else {
                data = new LinkedHashMap<>();
            }
        } catch (IOException e) {
            LOG.error("[VaultRecordStore] 加载失败: {}", serverKey, e);
            data = new LinkedHashMap<>();
        }
    }

    /** 写入当前服务器对应的文件。 */
    public synchronized void save() {
        if (currentServerKey == null) return;
        try {
            Files.createDirectories(DIR);
            Path file = DIR.resolve(currentServerKey + ".json");
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException e) {
            LOG.error("[VaultRecordStore] 保存失败: {}", currentServerKey, e);
        }
    }

    /** 新增一条解锁记录（重复坐标不重复添加）。 */
    public synchronized boolean addRecord(String worldLabel, String dimension, VaultRecord record) {
        if (currentServerKey == null) return false;
        Map<String, Map<String, Map<String, List<VaultRecord>>>> worlds = data.computeIfAbsent(currentServerKey, k -> new LinkedHashMap<>());
        Map<String, Map<String, List<VaultRecord>>> dims = worlds.computeIfAbsent(worldLabel, k -> new LinkedHashMap<>());
        Map<String, List<VaultRecord>> groups = dims.computeIfAbsent(dimension, k -> new LinkedHashMap<>());
        List<VaultRecord> records = groups.computeIfAbsent(record.chamberGroup, k -> new ArrayList<>());

        for (VaultRecord r : records) {
            if (r.x == record.x && r.y == record.y && r.z == record.z) return false;
        }
        records.add(record);
        save();
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
            for (VaultRecord r : records) keys.add(BlockPos.asLong(r.x, r.y, r.z));
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
