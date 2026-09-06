package me.frost.superspawners.mixin;

import me.frost.superspawners.block.entity.SuperSpawnerBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SuperSpawnerBlockEntity.class)
public abstract class ForgeItemHandlerMixin {
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction direction) {
        if (capability != ForgeCapabilities.ITEM_HANDLER) {
            return LazyOptional.empty();
        }

        WorldlyContainer container = (WorldlyContainer) (Object) this;
        IItemHandler itemHandler = direction == null ? new InvWrapper(container) : new SidedInvWrapper(container, direction);
        return LazyOptional.of(() -> itemHandler).cast();
    }
}