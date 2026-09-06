package me.frost.superspawners.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

public class SuperSpawnerItem extends BlockItem implements PolymerItem {
    public SuperSpawnerItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Item getPolymerItem(ItemStack itemStack, ServerPlayer player) {
        return Items.SPAWNER;
    }
}
