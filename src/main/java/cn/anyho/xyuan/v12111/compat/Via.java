package cn.anyho.xyuan.v12111.compat;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
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
 * 版本兼容实现 —— Minecraft <b>1.21.11</b>（当前生产基线）。
 *
 * <p>本文件是「源」：构建期会被复制到共享包 {@code cn.anyho.xyuan.compat.Via}
 * 并重写 package 声明。业务代码只认 {@code cn.anyho.xyuan.compat.Via}，
 * 永远不要直接 import 本类。
 *
 * <p><b>1.21.11 的原版 API 特征</b>（已用 yarn 1.21.11+build.3/6 映射实测）：
 * <ul>
 *   <li>{@code NbtCompound#getString(String,String)} / {@code getBoolean(String,boolean)}
 *       带默认值的重载存在；</li>
 *   <li>{@code NbtCompound#getListOrEmpty(String)} 存在（1.21.5 起取代 {@code getList(String,int)}）；</li>
 *   <li>{@code NbtElement#asString()} 返回 {@code Optional<String>}（1.21.5 起的改动）；</li>
 *   <li>{@code CommandExecutionC2SPacket(String)} 三版本（1.21.1/1.21.4/1.21.11）均存在。</li>
 * </ul>
 *
 * <p><b>方法契约</b>：所有方法 public static，方法名与语义在 v1211 / v1214 / v12111
 * 三个目录中必须完全一致。新增功能时在这里加方法，然后在各版本目录补实现，
 * 业务代码直接调用即可，不需要写任何版本判断。
 */
public final class Via {

    private Via() {
    }

    // ------------------------------------------------------------------
    //  元信息
    // ------------------------------------------------------------------

    /** 当前版本号，仅用于日志与诊断。 */
    public static String version() {
        return "1.21.11";
    }

    // ------------------------------------------------------------------
    //  网络
    // ------------------------------------------------------------------

    /**
     * 以命令通道发送一条指令（不含前导 "/"）。
     *
     * <p>直接构造 {@link CommandExecutionC2SPacket} 发包，不经过原版聊天确认链路，
     * 服务端排队场景下更可靠。
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

    /** 取可翻译文本的翻译键（如 {@code death.attack.fall}）；非翻译文本返回 {@code null}。 */
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
    //  NBT —— 读取
    // ------------------------------------------------------------------

    /** 读取字符串，键缺失或类型不符时返回 {@code fallback}。 */
    public static String readString(NbtCompound tag, String key, String fallback) {
        return tag == null ? fallback : tag.getString(key, fallback);
    }

    /** 读取布尔值，键缺失或类型不符时返回 {@code fallback}。 */
    public static boolean readBoolean(NbtCompound tag, String key, boolean fallback) {
        return tag == null ? fallback : tag.getBoolean(key, fallback);
    }

    /** 读取字符串列表；键缺失时返回空列表，列表内的非字符串元素被忽略。 */
    public static List<String> readStringList(NbtCompound tag, String key) {
        List<String> values = new ArrayList<>();
        if (tag == null) {
            return values;
        }
        for (NbtElement element : tag.getListOrEmpty(key)) {
            element.asString().ifPresent(values::add);
        }
        return values;
    }

    // ------------------------------------------------------------------
    //  NBT —— 写入
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
