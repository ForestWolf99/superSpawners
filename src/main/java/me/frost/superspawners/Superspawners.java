package me.frost.superspawners;

import eu.pb4.polymer.core.api.item.PolymerItemGroupUtils;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import me.frost.superspawners.block.SuperSpawnerBlock;
import me.frost.superspawners.block.entity.SuperSpawnerBlockEntity;
import me.frost.superspawners.item.SuperSpawnerItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class Superspawners implements ModInitializer {
    public static final String MOD_ID = "superspawners";
    private static final ResourceLocation SUPER_SPAWNER_ID = new ResourceLocation(MOD_ID, "superspawner");

    public static final Block SUPER_SPAWNER_BLOCK = Registry.register(
            BuiltInRegistries.BLOCK,
            SUPER_SPAWNER_ID,
            new SuperSpawnerBlock(
                    BlockBehaviour.Properties.of()
                            .strength(5.0F)
                            .noOcclusion()
            )
    );

    public static final Item SUPER_SPAWNER_ITEM = Registry.register(
            BuiltInRegistries.ITEM,
            SUPER_SPAWNER_ID,
            new SuperSpawnerItem(
                    SUPER_SPAWNER_BLOCK,
                    new Item.Properties()
            )
    );

    public static final BlockEntityType<SuperSpawnerBlockEntity> SUPER_SPAWNER_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            new ResourceLocation(MOD_ID, "superspawner"),
            FabricBlockEntityTypeBuilder.create(SuperSpawnerBlockEntity::new, SUPER_SPAWNER_BLOCK).build()
    );

    public static final ResourceLocation POLYMER_TAB_ID = new ResourceLocation(MOD_ID, "spawners");

    @Override
    public void onInitialize() {
        PolymerResourcePackUtils.addModAssets(MOD_ID);
        PolymerBlockUtils.registerBlockEntity(SUPER_SPAWNER_BLOCK_ENTITY);
        registerPolymerTab();
        PlayerBlockBreakEvents.BEFORE.register(this::onBeforeBreak);
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide()) {
                return InteractionResult.PASS;
            }
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            BlockPos blockPos = hitResult.getBlockPos();
            BlockEntity blockEntity = level.getBlockEntity(blockPos);
            if (!(blockEntity instanceof SuperSpawnerBlockEntity superSpawnerBlockEntity)) {
                return InteractionResult.PASS;
            }

            ItemStack held = player.getItemInHand(hand);
            if (player.isCrouching() && held.isEmpty()) {
                superSpawnerBlockEntity.claimAll(player);
                return InteractionResult.SUCCESS;
            }

            if (held.isEmpty()) {
                player.openMenu(superSpawnerBlockEntity);
                return InteractionResult.SUCCESS;
            }

            if (superSpawnerBlockEntity.tryApplyUpgradeOrSpawner(held, player, !player.getAbilities().instabuild)) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    private boolean onBeforeBreak(net.minecraft.world.level.Level world, Player player, net.minecraft.core.BlockPos blockPos, BlockState blockState, BlockEntity blockEntity) {
        if (!(world instanceof ServerLevel serverLevel) || blockEntity == null) {
            return true;
        }
        if (blockState.is(SUPER_SPAWNER_BLOCK) && blockEntity instanceof SuperSpawnerBlockEntity superSpawnerBlockEntity) {
            if (superSpawnerBlockEntity.hasManyStoredItems()) {
                superSpawnerBlockEntity.sendBreakWarning(player);
            }
            Block.popResource(world, blockPos, superSpawnerBlockEntity.createDroppedSpawnerStack());
            superSpawnerBlockEntity.dropManagedSpawners(world, blockPos);
            superSpawnerBlockEntity.dropAppliedUpgrades(world, blockPos);
            superSpawnerBlockEntity.dropStoredItems(world, blockPos);
            world.removeBlock(blockPos, false);
            return false;
        }
        if (!blockState.is(Blocks.SPAWNER)) {
            return true;
        }
        if (!hasSilkTouch(player.getMainHandItem())) {
            return true;
        }

        ItemStack drop = new ItemStack(SUPER_SPAWNER_ITEM);
        CompoundTag blockEntityData = blockEntity.saveWithoutMetadata();
        if (blockEntityData.contains("SpawnData")) {
            CompoundTag entityTag = blockEntityData.getCompound("SpawnData").getCompound("entity");
            ResourceLocation parsed = ResourceLocation.tryParse(entityTag.getString("id"));
            if (parsed != null) {
                SuperSpawnerBlockEntity.writeMobTypeToItem(drop, parsed);
            }
        }

        Block.popResource(world, blockPos, drop);
        world.removeBlock(blockPos, false);
        return false;
    }

    private boolean hasSilkTouch(ItemStack stack) {
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0;
    }

    private static void registerPolymerTab() {
        CreativeModeTab tab = net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
                .title(net.minecraft.network.chat.Component.literal("Super Spawners"))
                .icon(() -> new ItemStack(SUPER_SPAWNER_ITEM))
                .displayItems((params, output) -> {
                    List<ResourceLocation> eggIds = new ArrayList<>();
                    for (Item item : BuiltInRegistries.ITEM) {
                        if (item instanceof SpawnEggItem) {
                            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                            if (id != null) {
                                eggIds.add(id);
                            }
                        }
                    }
                    eggIds.sort(Comparator.comparing(ResourceLocation::toString));

                    for (ResourceLocation eggId : eggIds) {
                        if (!eggId.getPath().endsWith("_spawn_egg")) {
                            continue;
                        }
                        String entityPath = eggId.getPath().substring(0, eggId.getPath().length() - "_spawn_egg".length());
                        ResourceLocation entityId = new ResourceLocation(eggId.getNamespace(), entityPath);
                        ItemStack spawnerStack = new ItemStack(SUPER_SPAWNER_ITEM);
                        SuperSpawnerBlockEntity.writeMobTypeToItem(spawnerStack, entityId);
                        output.accept(spawnerStack);
                    }
                })
                .build();

        PolymerItemGroupUtils.registerPolymerItemGroup(POLYMER_TAB_ID, tab);
    }
}
