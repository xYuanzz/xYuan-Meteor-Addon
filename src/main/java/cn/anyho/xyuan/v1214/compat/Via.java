package cn.anyho.xyuan.v1214.compat;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 版本兼容实现 —— Minecraft <b>1.21.4</b>。
 *
 * <p>本文件是「源」：构建期会被复制到共享包 {@code cn.anyho.xyuan.compat.Via}
 * 并重写 package 声明。业务代码只认 {@code cn.anyho.xyuan.compat.Via}。
 *
 * <p><b>⚠️ 维护提示</b>：1.21.4 与 1.21.1 用到的原版 API 面几乎完全一致
 * （NbtCompound 的带默认值重载与 getListOrEmpty 都是 1.21.5 才加入），
 * 因此本文件与 {@code v1211/compat/Via.java} <b>只有两处</b>不同：
 * {@code version()} 的返回值，以及 {@code soundEventId} 的方法体
 * （1.21.1 是 {@code getId()}，1.21.4 起是 {@code id()}）。其余逐字相同。
 * 修改其中一个时请同步另一个；构建期的 {@code verifyVersionParity} 任务会强制校验这一点。
 *
 * <p><b>1.21.4 的原版 API 特征</b>（已用 yarn 1.21.4+build.8 映射实测）：
 * <ul>
 *   <li>{@code NbtCompound} <b>没有</b> {@code getString(String,String)} /
 *       {@code getBoolean(String,boolean)} 带默认值的重载；</li>
 *   <li>{@code NbtCompound#getListOrEmpty(String)} <b>不存在</b>，用 {@code getList(String,int)}；</li>
 *   <li>{@code NbtElement#asString()} 返回 {@code String}；</li>
 *   <li>{@code CommandExecutionC2SPacket(String)} 已存在（不存在改名问题）。</li>
 * </ul>
 */
public final class Via {

    private Via() {
    }

    // ------------------------------------------------------------------
    //  元信息
    // ------------------------------------------------------------------

    /** 当前版本号，仅用于日志与诊断。 */
    public static String version() {
        return "1.21.4";
    }

    // ------------------------------------------------------------------
    //  网络
    // ------------------------------------------------------------------

    /**
     * 以命令通道发送一条指令（不含前导 "/"）。
     *
     * <p>直接构造 {@link CommandExecutionC2SPacket} 发包，不经过原版聊天确认链路，
     * 服务端排队场景下更可靠。实现与 1.21.11 完全一致。
     *
     * @return 网络处理器不可用时返回 {@code false}（调用方应跳过后续提示）
     */
    public static boolean sendCommand(String command) {
        ClientPlayNetworkHandler handler = networkHandler();
        if (handler == null) {
            return false;
        }
        handler.sendPacket(new CommandExecutionC2SPacket(command));
        return true;
    }

    /** 取当前网络处理器；未连接时为 {@code null}。 */
    private static ClientPlayNetworkHandler networkHandler() {
        var mc = MeteorClient.mc;
        return mc == null ? null : mc.getNetworkHandler();
    }

    // ------------------------------------------------------------------
    //  文本
    // ------------------------------------------------------------------

    /**
     * 取可翻译文本的翻译键（如 {@code death.attack.fall}）；非翻译文本返回 {@code null}。
     * 实现与 1.21.11 完全一致。
     */
    public static String translatableKey(Text text) {
        if (text == null) {
            return null;
        }
        return text.getContent() instanceof TranslatableTextContent content ? content.getKey() : null;
    }

    // ------------------------------------------------------------------
    //  声音
    // ------------------------------------------------------------------

    /**
     * 取声音事件对应的注册 ID。
     *
     * <p>1.21.2 起 {@code SoundEvent} 由普通类变为 record，访问器由 {@code getId()} 改名为
     * {@code id()}。已用 javap 对 Loom remap 后的原版 jar 实测确认：
     * 1.21.4 / 1.21.11 只有 {@code id()}（1.21.1 只有 {@code getId()}）。</p>
     */
    public static Identifier soundEventId(SoundEvent event) {
        return event.id();
    }

    // ------------------------------------------------------------------
    //  NBT —— 读取（与 1.21.11 的实现不同，见下方注释）
    // ------------------------------------------------------------------

    /**
     * 读取字符串，键缺失<b>或类型不符</b>时返回 {@code fallback}。
     *
     * <p><b>注意</b>：不能写成 {@code tag.contains(key) ? tag.getString(key) : fallback}。
     * 原版 {@code getString(key)} 在「键存在但类型不是字符串」时返回空串，
     * 会把「类型不符」错误地当作「值是空串」，与 1.21.11 的
     * {@code getString(key, fallback)} 语义不一致。因此先判类型再取值。
     */
    public static String readString(NbtCompound tag, String key, String fallback) {
        if (tag == null) {
            return fallback;
        }
        NbtElement element = tag.get(key);
        if (!(element instanceof NbtString)) {
            return fallback;
        }
        return tag.getString(key);
    }

    /**
     * 读取布尔值，键缺失或类型不符时返回 {@code fallback}。
     * 原版以 {@link NbtByte} 存储布尔值，同样先判类型再取值。
     */
    public static boolean readBoolean(NbtCompound tag, String key, boolean fallback) {
        if (tag == null) {
            return fallback;
        }
        NbtElement element = tag.get(key);
        if (!(element instanceof NbtByte)) {
            return fallback;
        }
        return tag.getBoolean(key);
    }

    /**
     * 读取字符串列表；键缺失时返回空列表，列表内的非字符串元素被忽略。
     *
     * <p>用 {@code getList(key, NbtElement.STRING_TYPE)}：该重载在键不存在或元素类型不符时
     * 都返回空列表，与 1.21.11 的 {@code getListOrEmpty} + {@code asString()} 组合语义一致。
     */
    public static List<String> readStringList(NbtCompound tag, String key) {
        List<String> values = new ArrayList<>();
        if (tag == null) {
            return values;
        }
        NbtList list = tag.getList(key, NbtElement.STRING_TYPE);
        for (int i = 0; i < list.size(); i++) {
            values.add(list.getString(i));
        }
        return values;
    }

    // ------------------------------------------------------------------
    //  NBT —— 写入（NbtList / NbtString API 三版本一致，实现同 1.21.11）
    // ------------------------------------------------------------------

    /** 写入字符串列表（覆盖同名键）。 */
    public static void writeStringList(NbtCompound tag, String key, Collection<String> values) {
        if (tag == null) {
            return;
        }
        NbtList list = new NbtList();
        for (String value : values) {
            list.add(NbtString.of(value));
        }
        tag.put(key, list);
    }
}
