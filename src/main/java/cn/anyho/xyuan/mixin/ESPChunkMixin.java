package cn.anyho.xyuan.mixin;

import cn.anyho.xyuan.blockesp.BlockStateFilters;
import meteordevelopment.meteorclient.systems.modules.render.blockesp.ESPChunk;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** 让原版 BlockESP 的区块扫描按方块状态规则过滤。 */
@Mixin(ESPChunk.class)
public abstract class ESPChunkMixin {
    /**
     * 仅在配置了状态过滤时接管扫描；否则走原版逻辑，不产生额外开销。
     * 扫描主体与原版 ESPChunk#searchChunk 一致，仅在匹配时追加状态判定。
     */
    @Inject(method = "searchChunk", at = @At("HEAD"), cancellable = true)
    private static void xyuan$searchChunkFiltered(Chunk chunk, List<Block> blocks, CallbackInfoReturnable<ESPChunk> cir) {
        if (!BlockStateFilters.hasFilters()) return;

        ESPChunk schunk = new ESPChunk(chunk.getPos().x, chunk.getPos().z);
        if (schunk.shouldBeDeleted()) {
            cir.setReturnValue(schunk);
            return;
        }

        BlockPos.Mutable blockPos = new BlockPos.Mutable();

        for (int x = chunk.getPos().getStartX(); x <= chunk.getPos().getEndX(); x++) {
            for (int z = chunk.getPos().getStartZ(); z <= chunk.getPos().getEndZ(); z++) {
                int height = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(x - chunk.getPos().getStartX(), z - chunk.getPos().getStartZ());

                for (int y = mc.world.getBottomY(); y < height; y++) {
                    blockPos.set(x, y, z);
                    BlockState bs = chunk.getBlockState(blockPos);

                    if (blocks.contains(bs.getBlock()) && BlockStateFilters.allows(bs, blockPos)) schunk.add(blockPos, false);
                }
            }
        }

        cir.setReturnValue(schunk);
    }
}
