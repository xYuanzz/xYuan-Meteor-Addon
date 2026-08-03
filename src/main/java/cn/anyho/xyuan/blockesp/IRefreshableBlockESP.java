package cn.anyho.xyuan.blockesp;

/**
 * 由 BlockESPMixin 实现，供宝库增强模块在解锁记录变化后触发透视刷新。
 * BlockESP 实例可强转为该接口调用。
 */
public interface IRefreshableBlockESP {
    /** 刷新已开宝库坐标集合并重新扫描。 */
    void xyuanRefreshVaultFilter();
}
