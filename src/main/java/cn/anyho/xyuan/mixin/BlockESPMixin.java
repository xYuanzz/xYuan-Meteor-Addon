package cn.anyho.xyuan.mixin;

import cn.anyho.xyuan.blockesp.BlockStateFilters;
import cn.anyho.xyuan.blockesp.IRefreshableBlockESP;
import cn.anyho.xyuan.blockesp.StateFilterData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.settings.BlockDataSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.render.blockesp.BlockESP;
import meteordevelopment.meteorclient.systems.modules.render.blockesp.ESPChunk;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** 为原版 BlockESP 注入方块状态过滤设置与状态感知的增量更新逻辑。 */
@Mixin(BlockESP.class)
public abstract class BlockESPMixin extends Module implements IRefreshableBlockESP {
    @Shadow private Setting<List<Block>> blocks;

    @Shadow @Final private Long2ObjectMap<ESPChunk> chunks;
    @Shadow @Final private ExecutorService workerThread;
    @Shadow @Final private BlockPos.Mutable blockPos;

    @Unique
    private Setting<Map<Block, StateFilterData>> xyuan$stateFilterConfigs;

    private BlockESPMixin(Category category, String name, String description) {
        super(category, name, description);
    }

    @Shadow
    private void updateBlock(int x, int y, int z) {}

    /** 在原版设置组之后追加“状态过滤”组，配置随模块一起保存与加载。 */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void xyuan$addStateFilterSettings(CallbackInfo ci) {
        SettingGroup sgStateFilters = settings.createGroup("状态过滤");

        xyuan$stateFilterConfigs = sgStateFilters.add(new BlockDataSetting.Builder<StateFilterData>()
            .name("状态过滤配置")
            .description("按方块配置状态过滤规则。例如只透视不祥宝库(ominous=true)、只看朝北的楼梯、忽略含水的楼梯；vault 还支持 rewarded=true(未开启)/rewarded=false(已开启) 虚拟属性。")
            .defaultData(() -> new StateFilterData(false, StateFilterData.FilterMode.Whitelist, StateFilterData.RuleLogic.MatchAny, List.of()))
            .onChanged(configs -> xyuan$onStateFiltersChanged())
            .build()
        );
    }

    /** 激活扫描前重建过滤表，保证扫描线程读到最新配置。 */
    @Inject(method = "onActivate", at = @At("HEAD"))
    private void xyuan$rebuildFiltersOnActivate(CallbackInfo ci) {
        BlockStateFilters.rebuild(xyuan$stateFilterConfigs.get());
    }

    /**
     * 原版的增量更新只比较方块类型，同方块不同状态的变化会被忽略。
     * 启用状态过滤后，按“方块类型 + 状态规则”重算增删，逻辑其余部分与原版一致。
     */
    @Inject(method = "onBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void xyuan$onBlockUpdate(BlockUpdateEvent event, CallbackInfo ci) {
        if (!BlockStateFilters.hasFilters()) return;

        ci.cancel();

        // Minecraft probably reuses the event.pos BlockPos instance because it causes problems when trying to use it inside another thread
        int bx = event.pos.getX();
        int by = event.pos.getY();
        int bz = event.pos.getZ();

        int chunkX = bx >> 4;
        int chunkZ = bz >> 4;
        long key = ChunkPos.toLong(chunkX, chunkZ);

        boolean newPass = xyuan$passes(event.newState, event.pos);
        boolean oldPass = xyuan$passes(event.oldState, event.pos);

        boolean added = newPass && !oldPass;
        boolean removed = !added && !newPass && oldPass;

        if (added || removed) {
            workerThread.submit(() -> {
                synchronized (chunks) {
                    ESPChunk chunk = chunks.get(key);

                    if (chunk == null) {
                        chunk = new ESPChunk(chunkX, chunkZ);
                        if (chunk.shouldBeDeleted()) return;

                        chunks.put(key, chunk);
                    }

                    blockPos.set(bx, by, bz);

                    if (added) chunk.add(blockPos);
                    else chunk.remove(blockPos);

                    // Update neighbour blocks
                    for (int x = -1; x < 2; x++) {
                        for (int z = -1; z < 2; z++) {
                            for (int y = -1; y < 2; y++) {
                                if (x == 0 && y == 0 && z == 0) continue;

                                updateBlock(bx + x, by + y, bz + z);
                            }
                        }
                    }
                }
            });
        }
    }

    @Unique
    private boolean xyuan$passes(BlockState state, BlockPos pos) {
        return blocks.get().contains(state.getBlock()) && BlockStateFilters.allows(state, pos);
    }

    @Unique
    private void xyuan$onStateFiltersChanged() {
        BlockStateFilters.rebuild(xyuan$stateFilterConfigs.get());
        if (isActive() && Utils.canUpdate()) onActivate();
    }

    /** 宝库增强模块解锁记录变化后调用：重新扫描以反映 rewarded 过滤。 */
    @Override
    @Unique
    public void xyuanRefreshVaultFilter() {
        if (isActive() && Utils.canUpdate()) onActivate();
    }
}
