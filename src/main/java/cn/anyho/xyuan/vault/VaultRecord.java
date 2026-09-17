package cn.anyho.xyuan.vault;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.math.BlockPos;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单个宝库的解锁记录。
 * 记录坐标、解锁时间、所属维度与试炼密室分组（按 3x3 区块网格归组）。
 *
 * <p>字段上的 {@link SerializedName} 把 JSON 里的键名固定下来：Gson 默认用 Java 字段名当键，
 * 一旦重命名字段，旧数据会被<b>静默丢弃</b>（反序列化成 null）。固定键名后，
 * 重命名 Java 字段不会影响既有存档的可读性。</p>
 */
public final class VaultRecord {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @SerializedName("x")
    public int x;

    @SerializedName("y")
    public int y;

    @SerializedName("z")
    public int z;

    @SerializedName("dimension")
    public String dimension;

    @SerializedName("chamberGroup")
    public String chamberGroup;

    @SerializedName("openedAt")
    public String openedAt;

    /**
     * Gson 反序列化专用。
     * 显式提供无参构造器后，Gson 不再需要退回 {@code Unsafe.allocateInstance} 绕过构造器来建实例——
     * 那个兜底在换序列化库或后续 JDK 上不一定还在，属于隐式依赖。
     */
    @SuppressWarnings("unused")
    private VaultRecord() {
    }

    public VaultRecord(int x, int y, int z, String dimension, String chamberGroup, String openedAt) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.chamberGroup = chamberGroup;
        this.openedAt = openedAt;
    }

    public VaultRecord(BlockPos pos, String dimension, String chamberGroup) {
        this(pos.getX(), pos.getY(), pos.getZ(), dimension, chamberGroup, LocalDateTime.now().format(FMT));
    }

    public long posKey() {
        return BlockPos.asLong(x, y, z);
    }

    public BlockPos toBlockPos() {
        return new BlockPos(x, y, z);
    }
}
