package cn.anyho.xyuan.v1211.compat;

import cn.anyho.xyuan.blockesp.StateFilterData;
import cn.anyho.xyuan.blockesp.StateFilterDataScreen;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.utils.IScreenFactory;
import meteordevelopment.meteorclient.settings.GenericSetting;
import meteordevelopment.meteorclient.utils.misc.ICopyable;
import meteordevelopment.meteorclient.utils.misc.ISerializable;

/**
 * 「Meteor 设置数据类型约束」的版本绑定接口 —— <b>1.21.1</b> 实现。
 *
 * <p>1.21.1 的 Meteor 还没有 {@code IGeneric}，{@code GenericSetting} 的类型约束是
 * {@code T extends ICopyable<T> & ISerializable<T> & IScreenFactory}。
 * 所以业务类 {@link StateFilterData} 在这里需要继承的是这三个接口，
 * 而不是 1.21.11 的那一个 {@code IGeneric}。
 *
 * <p>本接口同时负责「补齐 1.21.11 才有、低版本没有」的方法声明：
 * <ul>
 *   <li>{@link IScreenFactory#createScreen(GuiTheme)} —— 1.21.11 已把它移除，
 *       低版本用 default 方法补齐；</li>
 *   <li>{@link #createScreen(GuiTheme, GenericSetting)} —— 1.21.11 由 {@code IGeneric} 声明，
 *       低版本没有任何超类型声明它，若不在这里显式声明，
 *       业务类 {@code StateFilterData} 上的 {@code @Override} 在低版本会直接编译失败。</li>
 * </ul>
 *
 * <p>本文件是「源」，构建期会被复制到共享包 {@code cn.anyho.xyuan.compat}。
 * 1.21.4 的实现与本文除 package 外完全相同。
 */
public interface StateFilterDataMeteorBinding
        extends ICopyable<StateFilterData>, ISerializable<StateFilterData>, IScreenFactory {

    /**
     * 由 {@code GenericSetting} 触发打开配置界面。
     *
     * <p>声明在此仅为了让业务类的 {@code @Override} 在低版本也有超类型方法可覆盖，
     * 由 {@link StateFilterData} 实现。</p>
     */
    WidgetScreen createScreen(GuiTheme theme, GenericSetting<StateFilterData> setting);

    /**
     * 由设置项触发打开配置界面。
     *
     * <p>注意：本项目并未实际构造 {@code GenericSetting<StateFilterData>}（该类型只为满足
     * Meteor 的类型约束而存在），所以这条路径在正常使用中不会被触发；
     * 这里提供一个可用实现，保证契约完整、不会因为缺失实现而编译失败。</p>
     */
    @Override
    default WidgetScreen createScreen(GuiTheme theme) {
        return new StateFilterDataScreen(theme, (StateFilterData) this);
    }
}
