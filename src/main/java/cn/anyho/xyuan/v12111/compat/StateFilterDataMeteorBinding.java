package cn.anyho.xyuan.v12111.compat;

import cn.anyho.xyuan.blockesp.StateFilterData;
import meteordevelopment.meteorclient.settings.IGeneric;

/**
 * 「Meteor 设置数据类型约束」的版本绑定接口 —— <b>1.21.11</b> 实现。
 *
 * <p>Meteor 在 1.21.11 把 {@code GenericSetting} 的类型约束从
 * {@code ICopyable<T> & ISerializable<T> & IScreenFactory} 改成了 {@code IGeneric<T>}，
 * 并移除了 {@code IScreenFactory}（改动点位于 {@code meteordevelopment.meteorclient.settings}）。
 * Meteor 自己的 {@code ESPBlockData} 也是这样迁移的。
 *
 * <p>为了让业务类 {@link StateFilterData} 保持共享、不写版本判断，
 * 这里用「每版本一个绑定接口」把差异封住：业务类只 {@code implements} 本接口，
 * 由本接口在不同版本里继承不同的 Meteor 类型。
 *
 * <p>本文件是「源」，构建期会被复制到共享包 {@code cn.anyho.xyuan.compat}。
 */
public interface StateFilterDataMeteorBinding extends IGeneric<StateFilterData> {
}
