package cn.anyho.xyuan.vault;

import net.minecraft.util.math.BlockPos;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单个宝库的解锁记录。
 * 记录坐标、解锁时间、所属维度与试炼密室分组（按 3x3 区块网格归组）。
 */
public final class VaultRecord {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public int x;
    public int y;
    public int z;
    public String dimension;
    public String chamberGroup;
    public String openedAt;

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
