package cn.anyho.xyuan.blockesp;

import cn.anyho.xyuan.compat.StateFilterDataMeteorBinding;
import cn.anyho.xyuan.compat.Via;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.BlockDataSetting;
import meteordevelopment.meteorclient.settings.GenericSetting;
import meteordevelopment.meteorclient.settings.IBlockData;
import meteordevelopment.meteorclient.utils.misc.IChangeable;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.List;

/** BlockESP 单方块状态过滤数据，序列化方式对齐原版 ESPBlockData。 */
public class StateFilterData implements StateFilterDataMeteorBinding, IChangeable, IBlockData<StateFilterData> {
    public boolean enabled;
    public FilterMode mode;
    public RuleLogic ruleLogic;
    public final List<String> rules;

    private boolean changed;

    public StateFilterData(boolean enabled, FilterMode mode, RuleLogic ruleLogic, List<String> rules) {
        this.enabled = enabled;
        this.mode = mode;
        this.ruleLogic = ruleLogic;
        this.rules = new ArrayList<>(rules);
    }

    @Override
    public WidgetScreen createScreen(GuiTheme theme, Block block, BlockDataSetting<StateFilterData> setting) {
        return new StateFilterDataScreen(theme, this, block, setting);
    }

    @Override
    public WidgetScreen createScreen(GuiTheme theme, GenericSetting<StateFilterData> setting) {
        return new StateFilterDataScreen(theme, this, setting);
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    public void changed() {
        changed = true;
    }

    @Override
    public StateFilterData set(StateFilterData value) {
        enabled = value.enabled;
        mode = value.mode;
        ruleLogic = value.ruleLogic;

        rules.clear();
        rules.addAll(value.rules);

        changed = value.changed;

        return this;
    }

    @Override
    public StateFilterData copy() {
        return new StateFilterData(enabled, mode, ruleLogic, rules);
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();

        tag.putBoolean("enabled", enabled);
        tag.putString("mode", mode.name());
        tag.putString("ruleLogic", ruleLogic.name());

        Via.writeStringList(tag, "rules", rules);

        tag.putBoolean("changed", changed);

        return tag;
    }

    @Override
    public StateFilterData fromTag(NbtCompound tag) {
        enabled = Via.readBoolean(tag, "enabled", false);
        mode = FilterMode.valueOf(Via.readString(tag, "mode", "Whitelist"));
        ruleLogic = RuleLogic.valueOf(Via.readString(tag, "ruleLogic", "MatchAny"));

        rules.clear();
        rules.addAll(Via.readStringList(tag, "rules"));

        changed = Via.readBoolean(tag, "changed", false);

        return this;
    }

    /** 过滤模式：白名单仅透视命中规则的状态，黑名单跳过命中规则的状态。 */
    public enum FilterMode {
        Whitelist,
        Blacklist
    }

    /** 多条规则之间的组合逻辑：MatchAny 命中任一即生效；MatchAll 需全部命中才生效。 */
    public enum RuleLogic {
        MatchAny,
        MatchAll
    }
}
