package cn.anyho.xyuan.blockesp;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.settings.BlockDataSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.GenericSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringListSetting;
import net.minecraft.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

/** BlockESP 单方块状态过滤配置界面，结构与原版 ESPBlockDataScreen 一致。 */
public class StateFilterDataScreen extends WindowScreen {
    private final StateFilterData filterData;
    private final Setting<?> setting;
    private final @Nullable Runnable firstChangeConsumer;

    public StateFilterDataScreen(GuiTheme theme, StateFilterData filterData, Block block, BlockDataSetting<StateFilterData> setting) {
        this(theme, filterData, setting, () -> setting.get().put(block, filterData));
    }

    public StateFilterDataScreen(GuiTheme theme, StateFilterData filterData, GenericSetting<StateFilterData> setting) {
        this(theme, filterData, setting, null);
    }

    private StateFilterDataScreen(GuiTheme theme, StateFilterData filterData, Setting<?> setting, @Nullable Runnable firstChangeConsumer) {
        super(theme, "配置状态过滤");

        this.filterData = filterData;
        this.setting = setting;
        this.firstChangeConsumer = firstChangeConsumer;
    }

    @Override
    public void initWidgets() {
        Settings settings = new Settings();
        SettingGroup sgGeneral = settings.getDefaultGroup();

        sgGeneral.add(new BoolSetting.Builder()
            .name("启用")
            .description("为该方块启用状态过滤。")
            .defaultValue(false)
            .onModuleActivated(boolSetting -> boolSetting.set(filterData.enabled))
            .onChanged(aBoolean -> {
                if (filterData.enabled != aBoolean) {
                    filterData.enabled = aBoolean;
                    onChanged();
                }
            })
            .build()
        );

        sgGeneral.add(new EnumSetting.Builder<StateFilterData.FilterMode>()
            .name("模式")
            .description("白名单：仅透视命中规则的状态；黑名单：跳过命中规则的状态。")
            .defaultValue(StateFilterData.FilterMode.Whitelist)
            .onModuleActivated(modeSetting -> modeSetting.set(filterData.mode))
            .onChanged(mode -> {
                if (filterData.mode != mode) {
                    filterData.mode = mode;
                    onChanged();
                }
            })
            .build()
        );

        sgGeneral.add(new EnumSetting.Builder<StateFilterData.RuleLogic>()
            .name("规则逻辑")
            .description("多条规则之间的组合方式。满足任一：命中任一规则即生效（默认，OR）；满足全部：需同时命中全部规则才生效（AND）。规则内用 '&' 连接的条件始终为 AND。")
            .defaultValue(StateFilterData.RuleLogic.MatchAny)
            .onModuleActivated(logicSetting -> logicSetting.set(filterData.ruleLogic))
            .onChanged(logic -> {
                if (filterData.ruleLogic != logic) {
                    filterData.ruleLogic = logic;
                    onChanged();
                }
            })
            .build()
        );

        sgGeneral.add(new StringListSetting.Builder()
            .name("规则")
            .description("每行一条规则：属性=值1|值2，多属性用 '&' 连接。示例：ominous=true、facing=north|south、waterlogged=false。vault 额外支持 rewarded=true(未开启)/rewarded=false(已开启)。需要同时满足 ominous=true 与 rewarded=true 时，把『规则逻辑』设为『满足全部』并分两行写 ominous=true 和 rewarded=true，或单行写 ominous=true&rewarded=true。")
            .defaultValue(new ArrayList<>())
            .onModuleActivated(listSetting -> listSetting.set(new ArrayList<>(filterData.rules)))
            .onChanged(list -> {
                if (!filterData.rules.equals(list)) {
                    filterData.rules.clear();
                    filterData.rules.addAll(list);
                    onChanged();
                }
            })
            .build()
        );

        settings.onActivated();
        add(theme.settings(settings)).expandX();
    }

    private void onChanged() {
        if (!filterData.isChanged() && firstChangeConsumer != null) {
            firstChangeConsumer.run();
        }

        setting.onChanged();
        filterData.changed();
    }
}
