package me.frost.superspawners.block;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import me.frost.superspawners.block.entity.SuperSpawnerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.jetbrains.annotations.Nullable;

public class SuperSpawnerBlock extends Block implements EntityBlock, PolymerBlock {
    public SuperSpawnerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public Block getPolymerBlock(BlockState state) {
        return Blocks.SPAWNER;
    }

    @Override
    public RenderShape getRenderShape(BlockState blockState) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new SuperSpawnerBlockEntity(blockPos, blockState);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, ServerPlayer player) {
        return Blocks.SPAWNER.defaultBlockState();
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(net.minecraft.world.level.Level level, BlockState state, BlockEntityType<T> type) {
        if (type == me.frost.superspawners.Superspawners.SUPER_SPAWNER_BLOCK_ENTITY) {
            return (lvl, pos, blockState, entity) -> SuperSpawnerBlockEntity.tick(lvl, pos, blockState, (SuperSpawnerBlockEntity) entity);
        }
        return null;
    }

    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        super.attack(state, level, pos, player);
        if (!level.isClientSide()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof SuperSpawnerBlockEntity superSpawnerBlockEntity) {
                superSpawnerBlockEntity.warnIfManyItems(player);
            }
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SuperSpawnerBlockEntity superSpawnerBlockEntity)) {
            return;
        }
        SuperSpawnerBlockEntity.readMobTypeFromItem(stack).ifPresent(superSpawnerBlockEntity::setMobType);
    }
}
