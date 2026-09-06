package me.frost.superspawners.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ConnectorMixinPlugin implements IMixinConfigPlugin {
    private static final String FORGE_ITEM_HANDLER_MIXIN = "me.frost.superspawners.mixin.ForgeItemHandlerMixin";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!FORGE_ITEM_HANDLER_MIXIN.equals(mixinClassName)) {
            return true;
        }

        try {
            Class.forName("net.minecraftforge.common.capabilities.ForgeCapabilities", false, ConnectorMixinPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}