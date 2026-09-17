package cn.anyho.xyuan.blockesp;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * BlockESP 状态过滤的编译产物注册表。
 * 通过 volatile 快照发布，扫描线程（BlockESP workerThread）可无锁读取。
 * openedPositions 为当前世界+维度下已开宝库坐标集合，供 rewarded 虚拟属性判定。
 */
public final class BlockStateFilters {
    private static volatile Map<Block, StateFilter> filters = Collections.emptyMap();
    private static volatile Set<Long> openedPositions = Collections.emptySet();

    private BlockStateFilters() {}

    public static boolean hasFilters() {
        return !filters.isEmpty();
    }

    /** 未配置过滤的方块直接放行；配置了过滤的方块按规则判定（含 rewarded 虚拟属性）。 */
    public static boolean allows(BlockState state, BlockPos pos) {
        StateFilter filter = filters.get(state.getBlock());
        return filter == null || filter.allows(state, pos);
    }

    /** 当前坐标是否为已开宝库（由宝库增强模块维护）。 */
    public static boolean isOpened(BlockPos pos) {
        return openedPositions.contains(pos.asLong());
    }

    /** 由设置当前值重建过滤表。 */
    public static void rebuild(Map<Block, StateFilterData> configs) {
        Map<Block, StateFilter> compiled = new HashMap<>();

        for (Map.Entry<Block, StateFilterData> entry : configs.entrySet()) {
            StateFilterData data = entry.getValue();
            if (data == null || !data.enabled || data.rules.isEmpty()) continue;

            StateFilter filter = StateFilter.compile(entry.getKey(), data);
            if (filter != null) compiled.put(entry.getKey(), filter);
        }

        filters = compiled.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(compiled);
    }

    /**
     * 更新已开宝库坐标集合（由宝库增强模块在解锁/切换世界维度时调用）。
     *
     * <p>这里做一次防御性拷贝再发布：{@code Collections.unmodifiableSet} 只是包装，
     * 若直接包住调用方传入的集合、而调用方事后原地修改它，扫描线程读到的就不再是不可变快照，
     * volatile 发布的可见性保证会被打破。拷贝后封装，快照才真正不可变。</p>
     */
    public static void setOpenedPositions(Set<Long> positions) {
        if (positions == null || positions.isEmpty()) {
            openedPositions = Collections.emptySet();
        } else {
            openedPositions = Collections.unmodifiableSet(new HashSet<>(positions));
        }
    }
}
