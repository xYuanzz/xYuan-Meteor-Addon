package cn.anyho.xyuan.blockesp;

import com.mojang.logging.LogUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 单个方块的已编译状态过滤谓词。
 * 规则语法：每行一条规则，多条规则之间为 OR；规则内用 '&' 连接多个条件（AND）；
 * 条件格式为 property=value1|value2（多个值为 OR，不区分大小写）。
 * 例：ominous=true、facing=north|south、waterlogged=false。
 *
 * 额外支持 rewarded 虚拟属性（仅对 vault 有意义）：
 *   rewarded=true  表示未开启，rewarded=false 表示已开启。
 *   该属性由宝库增强模块维护的已开坐标集合判定，不依赖方块状态本身。
 */
public final class StateFilter {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String REWARDED = "rewarded";

    private final StateFilterData.FilterMode mode;
    private final StateFilterData.RuleLogic ruleLogic;
    private final List<Condition[]> rules;

    private StateFilter(StateFilterData.FilterMode mode, StateFilterData.RuleLogic ruleLogic, List<Condition[]> rules) {
        this.mode = mode;
        this.ruleLogic = ruleLogic;
        this.rules = rules;
    }

    /** 将配置文本编译为谓词；无有效规则时返回 null（等价于未配置过滤）。 */
    public static StateFilter compile(Block block, StateFilterData data) {
        List<Condition[]> compiled = new ArrayList<>(data.rules.size());

        for (String rule : data.rules) {
            String line = rule.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("&");
            List<Condition> conditions = new ArrayList<>(parts.length);
            boolean invalid = false;

            for (String part : parts) {
                Condition condition = parseCondition(block, part.trim());
                if (condition == null) {
                    invalid = true;
                    break;
                }
                conditions.add(condition);
            }

            // 含有未知属性或语法错误的规则视为永不命中，避免白名单模式下误透视
            if (invalid) conditions = List.of(Condition.NEVER);
            if (!conditions.isEmpty()) compiled.add(conditions.toArray(new Condition[0]));
        }

        if (compiled.isEmpty()) return null;
        return new StateFilter(data.mode, data.ruleLogic, compiled);
    }

    private static Condition parseCondition(Block block, String text) {
        int eq = text.indexOf('=');
        if (eq <= 0 || eq == text.length() - 1) {
            LOG.warn("[BlockESP state filter] 忽略无效条件 '{}', 方块 {}", text, block);
            return null;
        }

        String name = text.substring(0, eq).trim();

        Set<String> values = new HashSet<>();
        for (String value : text.substring(eq + 1).split("\\|")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) values.add(trimmed.toLowerCase(Locale.ROOT));
        }

        if (values.isEmpty()) {
            LOG.warn("[BlockESP state filter] 条件 '{}' 方块 {} 无有效值", text, block);
            return null;
        }

        // rewarded 虚拟属性：不查方块的 stateManager
        if (name.equalsIgnoreCase(REWARDED)) {
            return new Condition(null, values, true);
        }

        Property<?> property = null;
        for (Property<?> candidate : block.getStateManager().getProperties()) {
            if (candidate.getName().equalsIgnoreCase(name)) {
                property = candidate;
                break;
            }
        }

        if (property == null) {
            LOG.warn("[BlockESP state filter] 方块 {} 没有属性 '{}'", block, name);
            return null;
        }

        return new Condition(property, values, false);
    }

    /**
     * 判定给定方块状态是否允许透视。
     *
     * <p>每条规则内部用 '&' 连接的条件为 AND；多条规则之间的组合由 {@link StateFilterData.RuleLogic} 决定：
     * MatchAny 任一规则命中即视为匹配；MatchAll 需全部规则命中才视为匹配。
     * 白名单模式下“匹配”表示透视该状态；黑名单模式下“匹配”表示跳过该状态。</p>
     */
    public boolean allows(BlockState state, BlockPos pos) {
        boolean anyMatched = false;
        boolean allMatched = !rules.isEmpty();

        for (Condition[] rule : rules) {
            boolean ruleMatched = true;
            for (Condition condition : rule) {
                if (!condition.test(state, pos)) {
                    ruleMatched = false;
                    break;
                }
            }

            if (ruleMatched) {
                anyMatched = true;
            } else {
                allMatched = false;
            }
        }

        boolean matched = ruleLogic == StateFilterData.RuleLogic.MatchAll ? allMatched : anyMatched;
        return mode == StateFilterData.FilterMode.Whitelist ? matched : !matched;
    }

    private record Condition(Property<?> property, Set<String> values, boolean rewarded) {
        private static final Condition NEVER = new Condition(null, Set.of(), false);

        @SuppressWarnings({"unchecked", "rawtypes"})
        private boolean test(BlockState state, BlockPos pos) {
            if (rewarded) {
                // rewarded=true 表示未开启，rewarded=false 表示已开启
                boolean opened = pos != null && BlockStateFilters.isOpened(pos);
                String expected = opened ? "false" : "true";
                return values.contains(expected);
            }

            if (property == null) return false;

            // 使用 Property.name() 的规范化字符串（与方块状态 JSON 一致）进行比较
            Property raw = property;
            String actual = raw.name(state.get(raw));

            return values.contains(actual.toLowerCase(Locale.ROOT));
        }
    }
}
