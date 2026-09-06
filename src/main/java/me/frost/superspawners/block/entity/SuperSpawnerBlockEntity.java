package me.frost.superspawners.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.Level;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SuperSpawnerBlockEntity extends BlockEntity implements MenuProvider, WorldlyContainer {
    private static final String MOB_TYPE_KEY = "MobType";
    private static final int BASE_STORAGE = 54;
    private static final int BASE_INTERVAL_TICKS = 200;
    private static final int MIN_INTERVAL_TICKS = 10;
    public static final int MAX_SUGAR = 1805;
    private static final int STORAGE_PAGE_ROWS = 5;
    private static final int STORAGE_PAGE_SIZE = STORAGE_PAGE_ROWS * 9;
    private int tickCounter;
    private String mobType = "minecraft:pig";
    private int sugar;
    private int bonusSpawners;
    private int storedXp;
    private boolean hasNetherStar;
    private boolean hasTotem;
    private boolean inventoryInteractivityEnabled;
    private long lastWarningGameTime = -100L;
    private List<ItemStack> storedItems = new ArrayList<>();
    private final Map<UUID, Integer> storagePageByPlayer = new HashMap<>();

    public SuperSpawnerBlockEntity(BlockPos pos, BlockState blockState) {
        super(me.frost.superspawners.Superspawners.SUPER_SPAWNER_BLOCK_ENTITY, pos, blockState);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        List<ItemStack> serializableItems = new ArrayList<>();
        for (ItemStack stack : this.storedItems) {
            if (!stack.isEmpty()) {
                serializableItems.add(stack.copy());
            }
        }
        output.putInt("tickCounter", this.tickCounter);
        output.putString("mobType", this.mobType);
        output.putInt("sugar", this.sugar);
        output.putInt("bonusSpawners", this.bonusSpawners);
        output.putInt("storedXp", this.storedXp);
        output.putBoolean("hasNetherStar", this.hasNetherStar);
        output.putBoolean("hasTotem", this.hasTotem);
        output.putBoolean("inventoryInteractivityEnabled", this.inventoryInteractivityEnabled);
        output.store("storedItems", ItemStack.OPTIONAL_CODEC.listOf(), serializableItems);
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.tickCounter = input.getIntOr("tickCounter", 0);
        String loadedMobType = input.getStringOr("mobType", "");
        if (loadedMobType.isBlank()) {
            loadedMobType = input.getStringOr("MobType", "minecraft:pig");
        }
        this.mobType = loadedMobType;
        this.sugar = Math.min(MAX_SUGAR, Math.max(0, input.getIntOr("sugar", 0)));
        this.bonusSpawners = input.getIntOr("bonusSpawners", 0);
        this.storedXp = input.getIntOr("storedXp", 0);
        this.hasNetherStar = input.getBooleanOr("hasNetherStar", false);
        this.hasTotem = input.getBooleanOr("hasTotem", false);
        this.inventoryInteractivityEnabled = input.getBooleanOr("inventoryInteractivityEnabled", false);
        this.storedItems = new ArrayList<>(input.read("storedItems", ItemStack.OPTIONAL_CODEC.listOf())
                .or(() -> input.read("storedItems", ItemStack.CODEC.listOf()))
                .orElse(List.of()));
        this.normalizeStoredItems();
    }

    public static Optional<Identifier> readMobTypeFromItem(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return Optional.empty();
        }
        CompoundTag tag = customData.copyTag();
        Optional<String> mobTypeString = tag.getString(MOB_TYPE_KEY);
        if (mobTypeString.isEmpty()) {
            mobTypeString = tag.getString("mobType");
        }
        return mobTypeString
                .map(Identifier::tryParse)
                .filter(id -> id != null);
    }

    public void setMobType(Identifier mobType) {
        this.mobType = mobType.toString();
        this.setChanged();
    }

    public Identifier getMobType() {
        Identifier parsed = Identifier.tryParse(this.mobType);
        return parsed != null ? parsed : Identifier.fromNamespaceAndPath("minecraft", "pig");
    }

    public int getSugar() {
        return this.sugar;
    }

    public int getBonusSpawners() {
        return this.bonusSpawners;
    }

    public int getStoredXp() {
        return this.storedXp;
    }

    public boolean hasNetherStarUpgrade() {
        return this.hasNetherStar;
    }

    public boolean hasTotemUpgrade() {
        return this.hasTotem;
    }

    public boolean isInventoryInteractivityEnabled() {
        return this.inventoryInteractivityEnabled;
    }

    public boolean tryApplyUpgradeOrSpawner(ItemStack heldStack, Player player, boolean consumeItem) {
        if (heldStack.isEmpty()) {
            return false;
        }

        if (heldStack.is(Items.SUGAR)) {
            if (this.sugar >= MAX_SUGAR) {
                return false;
            }
            this.sugar++;
            if (consumeItem) {
                heldStack.shrink(1);
            }
            this.setChanged();
            return true;
        }

        if (heldStack.is(Items.NETHER_STAR) && !this.hasNetherStar) {
            this.hasNetherStar = true;
            if (consumeItem) {
                heldStack.shrink(1);
            }
            this.setChanged();
            return true;
        }

        if (heldStack.is(Items.TOTEM_OF_UNDYING) && !this.hasTotem) {
            this.hasTotem = true;
            if (consumeItem) {
                heldStack.shrink(1);
            }
            this.setChanged();
            return true;
        }

        if (heldStack.is(me.frost.superspawners.Superspawners.SUPER_SPAWNER_ITEM)) {
            Optional<Identifier> incoming = readMobTypeFromItem(heldStack);
            if (incoming.isPresent() && incoming.get().equals(this.getMobType())) {
                this.bonusSpawners++;
                if (consumeItem) {
                    heldStack.shrink(1);
                }
                this.normalizeStoredItems();
                this.setChanged();
                return true;
            }
        }

        return false;
    }

    public void claimAll(Player player) {
        this.claimAllItems(player);
        if (this.storedXp > 0) {
            player.giveExperiencePoints(this.storedXp);
            this.storedXp = 0;
            this.setChanged();
        }
    }

    public void claimAllItems(Player player) {
        this.normalizeStoredItems();
        boolean changed = false;
        for (int i = 0; i < this.storedItems.size(); i++) {
            ItemStack storedItem = this.storedItems.get(i);
            if (storedItem.isEmpty()) {
                continue;
            }
            ItemStack toAdd = storedItem.copy();
            player.getInventory().add(toAdd);
            if (toAdd.getCount() != storedItem.getCount()) {
                changed = true;
                if (toAdd.isEmpty()) {
                    this.storedItems.set(i, ItemStack.EMPTY);
                } else {
                    this.storedItems.set(i, toAdd);
                }
            }
        }
        if (changed) {
            this.normalizeStoredItems();
            this.setChanged();
        }
    }

    public boolean hasManyStoredItems() {
        int count = 0;
        int stacks = 0;
        for (ItemStack stack : this.storedItems) {
            if (!stack.isEmpty()) {
                count += stack.getCount();
                stacks++;
                if (count > 64 || stacks > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    public void sendBreakWarning(Player player) {
        if (player != null) {
            player.sendSystemMessage(Component.literal("Warning: Breaking a spawner with a lot of items in it will create tons of item stacks!").withStyle(ChatFormatting.YELLOW));
        }
    }

    public void warnIfManyItems(Player player) {
        if (player != null && this.hasManyStoredItems() && this.level != null) {
            long gameTime = this.level.getGameTime();
            if (gameTime - this.lastWarningGameTime > 60L) {
                this.lastWarningGameTime = gameTime;
                this.sendBreakWarning(player);
            }
        }
    }

    public ItemStack createDroppedSpawnerStack() {
        ItemStack dropped = new ItemStack(me.frost.superspawners.Superspawners.SUPER_SPAWNER_ITEM);
        writeMobTypeToItem(dropped, this.getMobType());
        return dropped;
    }

    public void dropManagedSpawners(Level level, BlockPos pos) {
        int remaining = this.bonusSpawners;
        while (remaining > 0) {
            ItemStack stack = this.createDroppedSpawnerStack();
            int dropCount = Math.min(stack.getMaxStackSize(), remaining);
            stack.setCount(dropCount);
            net.minecraft.world.level.block.Block.popResource(level, pos, stack);
            remaining -= dropCount;
        }
        this.bonusSpawners = 0;
        this.normalizeStoredItems();
        this.setChanged();
    }

    public void dropStoredItems(Level level, BlockPos pos) {
        for (ItemStack storedItem : this.storedItems) {
            if (!storedItem.isEmpty()) {
                net.minecraft.world.level.block.Block.popResource(level, pos, storedItem.copy());
            }
        }
        this.storedItems.clear();
        this.normalizeStoredItems();
        this.setChanged();
    }

    public void dropAppliedUpgrades(Level level, BlockPos pos) {
        if (this.sugar > 0) {
            int remainingSugar = this.sugar;
            while (remainingSugar > 0) {
                ItemStack sugarStack = new ItemStack(Items.SUGAR, Math.min(Items.SUGAR.getDefaultMaxStackSize(), remainingSugar));
                net.minecraft.world.level.block.Block.popResource(level, pos, sugarStack);
                remainingSugar -= sugarStack.getCount();
            }
        }
        if (this.hasNetherStar) {
            net.minecraft.world.level.block.Block.popResource(level, pos, new ItemStack(Items.NETHER_STAR));
        }
        if (this.hasTotem) {
            net.minecraft.world.level.block.Block.popResource(level, pos, new ItemStack(Items.TOTEM_OF_UNDYING));
        }

        this.sugar = 0;
        this.hasNetherStar = false;
        this.hasTotem = false;
        this.setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Super Spawner");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory, Player player) {
        return this.createHomeMenu(containerId, inventory, player);
    }

    private AbstractContainerMenu createHomeMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory, Player player) {
        int rows = 3;
        SimpleContainer home = new SimpleContainer(rows * 9) {
            @Override
            public ItemStack getItem(int slot) {
                if (slot == 4) {
                    return SuperSpawnerBlockEntity.this.namedItem(Items.EXPERIENCE_BOTTLE, "Stored XP: " + SuperSpawnerBlockEntity.this.storedXp + " (click to collect)");
                }
                return super.getItem(slot);
            }
        };

        home.setItem(13, this.namedItem(this.getMobTypeSpawnEggItem(), "Home"));
        home.setItem(12, this.namedItem(Items.SPAWNER, "Stats"));
        home.setItem(14, this.namedItem(Items.ZOMBIE_HEAD, "Upgrades"));
        home.setItem(22, this.namedItem(Items.CHEST, "Stored Items"));
        home.setItem(4, this.namedItem(Items.EXPERIENCE_BOTTLE, "Stored XP: " + this.storedXp + " (click to collect)"));

        return this.createLockedMenu(containerId, inventory, home, rows, "Super Spawner - Home", (slot, button, input, menuPlayer) -> {
            if (slot == 22) {
                menuPlayer.openMenu(new SimpleMenuProvider((id, inv, p) -> this.createStorageMenu(id, inv, p), Component.literal("Super Spawner - Storage")));
                return;
            }
            if (slot == 14) {
                menuPlayer.openMenu(new SimpleMenuProvider((id, inv, p) -> this.createUpgradesMenu(id, inv), Component.literal("Super Spawner - Upgrades")));
                return;
            }
            if (slot == 12) {
                menuPlayer.openMenu(new SimpleMenuProvider((id, inv, p) -> this.createStatsMenu(id, inv), Component.literal("Super Spawner - Stats")));
                return;
            }
            if (slot == 4 && this.storedXp > 0) {
                menuPlayer.giveExperiencePoints(this.storedXp);
                this.storedXp = 0;
                this.setChanged();
                menuPlayer.openMenu(this);
            }
        });
    }

    private AbstractContainerMenu createStorageMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory, Player player) {
        int rows = 6;
        int maxPage = Math.max(0, (this.getStorageSlots() - 1) / STORAGE_PAGE_SIZE);
        int page = Math.max(0, Math.min(maxPage, this.storagePageByPlayer.getOrDefault(player.getUUID(), 0)));
        this.storagePageByPlayer.put(player.getUUID(), page);
        int pageStart = page * STORAGE_PAGE_SIZE;

        ItemStack prevItem = this.namedItem(Items.ARROW, "Previous Page");
        ItemStack backItem = this.namedItem(Items.BARRIER, "Back");
        ItemStack pageItem = this.namedItem(Items.PAPER, "Page " + (page + 1) + " / " + (maxPage + 1));
        ItemStack claimItem = this.namedItem(Items.HOPPER, "Claim All Items");
        ItemStack nextItem = this.namedItem(Items.ARROW, "Next Page");

        Container storageContainer = new Container() {
            @Override
            public int getContainerSize() {
                return rows * 9;
            }

            @Override
            public boolean isEmpty() {
                for (int i = 0; i < STORAGE_PAGE_SIZE; i++) {
                    if (!SuperSpawnerBlockEntity.this.getItem(pageStart + i).isEmpty()) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public ItemStack getItem(int slot) {
                if (slot >= 0 && slot < STORAGE_PAGE_SIZE) {
                    return SuperSpawnerBlockEntity.this.getItem(pageStart + slot);
                }
                if (slot == 45) return prevItem;
                if (slot == 46) return backItem;
                if (slot == 49) return pageItem;
                if (slot == 52) return claimItem;
                if (slot == 53) return nextItem;
                return ItemStack.EMPTY;
            }

            @Override
            public ItemStack removeItem(int slot, int amount) {
                if (slot >= 0 && slot < STORAGE_PAGE_SIZE) {
                    return SuperSpawnerBlockEntity.this.removeItem(pageStart + slot, amount);
                }
                return ItemStack.EMPTY;
            }

            @Override
            public ItemStack removeItemNoUpdate(int slot) {
                if (slot >= 0 && slot < STORAGE_PAGE_SIZE) {
                    return SuperSpawnerBlockEntity.this.removeItemNoUpdate(pageStart + slot);
                }
                return ItemStack.EMPTY;
            }

            @Override
            public void setItem(int slot, ItemStack stack) {
                if (slot >= 0 && slot < STORAGE_PAGE_SIZE) {
                    SuperSpawnerBlockEntity.this.setItem(pageStart + slot, stack);
                }
            }

            @Override
            public void setChanged() {
                SuperSpawnerBlockEntity.this.setChanged();
            }

            @Override
            public boolean stillValid(Player player) {
                return SuperSpawnerBlockEntity.this.stillValid(player);
            }

            @Override
            public void clearContent() {
                for (int i = 0; i < STORAGE_PAGE_SIZE; i++) {
                    SuperSpawnerBlockEntity.this.setItem(pageStart + i, ItemStack.EMPTY);
                }
            }

            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return slot >= 0 && slot < STORAGE_PAGE_SIZE;
            }

            @Override
            public boolean canTakeItem(Container target, int slot, ItemStack stack) {
                return slot >= 0 && slot < STORAGE_PAGE_SIZE;
            }
        };

        return new ChestMenu(MenuType.GENERIC_9x6, containerId, inventory, storageContainer, rows) {
            private boolean keepPageOnClose;

            @Override
            public void removed(Player player) {
                if (!this.keepPageOnClose) {
                    SuperSpawnerBlockEntity.this.storagePageByPlayer.remove(player.getUUID());
                }
                super.removed(player);
            }

            @Override
            public void clicked(int slotId, int button, net.minecraft.world.inventory.ContainerInput input, Player menuPlayer) {
                if (slotId == 45 && page > 0) {
                    this.keepPageOnClose = true;
                    SuperSpawnerBlockEntity.this.storagePageByPlayer.put(menuPlayer.getUUID(), page - 1);
                    menuPlayer.openMenu(new SimpleMenuProvider((id, inv, p) -> SuperSpawnerBlockEntity.this.createStorageMenu(id, inv, p), Component.literal("Super Spawner - Storage")));
                    return;
                }
                if (slotId == 53 && page < maxPage) {
                    this.keepPageOnClose = true;
                    SuperSpawnerBlockEntity.this.storagePageByPlayer.put(menuPlayer.getUUID(), page + 1);
                    menuPlayer.openMenu(new SimpleMenuProvider((id, inv, p) -> SuperSpawnerBlockEntity.this.createStorageMenu(id, inv, p), Component.literal("Super Spawner - Storage")));
                    return;
                }
                if (slotId == 46) {
                    this.keepPageOnClose = true;
                    menuPlayer.openMenu(SuperSpawnerBlockEntity.this);
                    return;
                }
                if (slotId == 52) {
                    SuperSpawnerBlockEntity.this.claimAllItems(menuPlayer);
                    this.keepPageOnClose = true;
                    menuPlayer.openMenu(new SimpleMenuProvider((id, inv, p) -> SuperSpawnerBlockEntity.this.createStorageMenu(id, inv, p), Component.literal("Super Spawner - Storage")));
                    return;
                }
                if (slotId >= STORAGE_PAGE_SIZE && slotId < rows * 9) {
                    return;
                }
                super.clicked(slotId, button, input, menuPlayer);
            }

            @Override
            public ItemStack quickMoveStack(Player player, int slotIndex) {
                if (slotIndex >= STORAGE_PAGE_SIZE && slotIndex < rows * 9) {
                    return ItemStack.EMPTY;
                }
                Slot slot = this.slots.get(slotIndex);
                if (slot != null && slot.hasItem()) {
                    ItemStack itemStack = slot.getItem();
                    ItemStack copy = itemStack.copy();
                    if (slotIndex < rows * 9) {
                        if (!this.moveItemStackTo(itemStack, rows * 9, this.slots.size(), true)) {
                            return ItemStack.EMPTY;
                        }
                    } else {
                        if (!this.moveItemStackTo(itemStack, 0, STORAGE_PAGE_SIZE, false)) {
                            return ItemStack.EMPTY;
                        }
                    }
                    if (itemStack.isEmpty()) {
                        slot.setByPlayer(ItemStack.EMPTY);
                    } else {
                        slot.setChanged();
                    }
                    return copy;
                }
                return ItemStack.EMPTY;
            }
        };
    }

    private enum UpgradeType {
        SUGAR,
        SPAWNER,
        NETHER_STAR,
        TOTEM
    }

    private void populateUpgradesMenu(SimpleContainer upgrades) {
        upgrades.setItem(11, this.namedItem(Items.SUGAR, "Sugar (current: " + this.sugar + "/" + MAX_SUGAR + ")", List.of(
                Component.literal("Left-click: +1").withStyle(ChatFormatting.GRAY),
                Component.literal("Right-click: -1").withStyle(ChatFormatting.GRAY),
                Component.literal("Shift-click: Add/Remove multiple via Anvil").withStyle(ChatFormatting.YELLOW)
        )));
        upgrades.setItem(12, this.namedItem(me.frost.superspawners.Superspawners.SUPER_SPAWNER_ITEM, "Matching Super Spawners (current: " + this.bonusSpawners + ")", List.of(
                Component.literal("Left-click: +1").withStyle(ChatFormatting.GRAY),
                Component.literal("Right-click: -1").withStyle(ChatFormatting.GRAY),
                Component.literal("Shift-click: Add/Remove multiple via Anvil").withStyle(ChatFormatting.YELLOW)
        )));
        upgrades.setItem(13, this.namedItem(Items.NETHER_STAR, this.hasNetherStar ? "Nether Star: Applied" : "Apply Nether Star", List.of(
                Component.literal("Left-click: Apply").withStyle(ChatFormatting.GRAY),
                Component.literal("Right-click: Remove").withStyle(ChatFormatting.GRAY),
                Component.literal("Shift-click: Add/Remove via Anvil").withStyle(ChatFormatting.YELLOW)
        )));
        upgrades.setItem(14, this.namedItem(Items.TOTEM_OF_UNDYING, this.hasTotem ? "Totem: Applied" : "Apply Totem", List.of(
                Component.literal("Left-click: Apply").withStyle(ChatFormatting.GRAY),
                Component.literal("Right-click: Remove").withStyle(ChatFormatting.GRAY),
                Component.literal("Shift-click: Add/Remove via Anvil").withStyle(ChatFormatting.YELLOW)
        )));
        upgrades.setItem(26, this.namedItem(Items.BARRIER, "Back"));
    }

    @FunctionalInterface
    private interface LockedMenuClickHandler {
        void handle(int slot, int button, net.minecraft.world.inventory.ContainerInput input, Player player);
    }

    private String getUpgradeDisplayName(UpgradeType type) {
        return switch (type) {
            case SUGAR -> "Sugar";
            case SPAWNER -> "Matching Spawners";
            case NETHER_STAR -> "Nether Star";
            case TOTEM -> "Totem of Undying";
        };
    }

    private void openUpgradeAnvilMenu(Player player, UpgradeType type, boolean isAdd) {
        String title = (isAdd ? "Add " : "Remove ") + this.getUpgradeDisplayName(type);
        player.openMenu(new SimpleMenuProvider((containerId, inventory, p) -> {
            return new UpgradeAmountAnvilMenu(containerId, inventory, type, isAdd);
        }, Component.literal(title)));
    }

    private class UpgradeAmountAnvilMenu extends net.minecraft.world.inventory.AnvilMenu {
        private final UpgradeType type;
        private final boolean isAdd;
        private String typedText = "1";
        private boolean confirmed = false;

        public UpgradeAmountAnvilMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory, UpgradeType type, boolean isAdd) {
            super(containerId, inventory, net.minecraft.world.inventory.ContainerLevelAccess.NULL);
            this.type = type;
            this.isAdd = isAdd;

            ItemStack initialInput = this.getInitialInputStack();
            this.inputSlots.setItem(0, initialInput);
            this.createResult();
        }

        private ItemStack getInitialInputStack() {
            ItemStack stack;
            switch (this.type) {
                case SUGAR -> stack = new ItemStack(Items.SUGAR);
                case SPAWNER -> stack = SuperSpawnerBlockEntity.this.createDroppedSpawnerStack();
                case NETHER_STAR -> stack = new ItemStack(Items.NETHER_STAR);
                case TOTEM -> stack = new ItemStack(Items.TOTEM_OF_UNDYING);
                default -> stack = new ItemStack(Items.PAPER);
            }
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("1"));
            return stack;
        }

        @Override
        public boolean stillValid(Player player) {
            return SuperSpawnerBlockEntity.this.stillValid(player);
        }

        @Override
        public int getCost() {
            return 0;
        }

        @Override
        protected boolean mayPickup(Player player, boolean hasStack) {
            return true;
        }

        @Override
        public boolean setItemName(String name) {
            this.typedText = (name == null) ? "" : name.trim();
            this.createResult();
            return true;
        }

        @Override
        public void createResult() {
            int amount = -1;
            try {
                if (this.typedText != null && !this.typedText.isEmpty()) {
                    amount = Integer.parseInt(this.typedText);
                }
            } catch (NumberFormatException ignored) {
            }

            if (amount <= 0) {
                ItemStack error = namedItem(Items.BARRIER, "Invalid Number", List.of(
                        Component.literal("Please enter a positive integer").withStyle(ChatFormatting.RED)
                ));
                this.resultSlots.setItem(0, error);
                this.broadcastChanges();
                return;
            }

            if (this.isAdd) {
                switch (this.type) {
                    case SUGAR -> {
                        int capacityRemaining = Math.max(0, MAX_SUGAR - SuperSpawnerBlockEntity.this.sugar);
                        int inInv = SuperSpawnerBlockEntity.this.countItem(this.player, Items.SUGAR);
                        int maxPossible = this.player.getAbilities().instabuild ? capacityRemaining : Math.min(capacityRemaining, inInv);
                        int actual = Math.min(amount, maxPossible);
                        if (actual > 0) {
                            ItemStack res = namedItem(Items.SUGAR, "Click to Add " + actual + " Sugar", List.of(
                                    Component.literal("Requested: " + amount).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Current: " + SuperSpawnerBlockEntity.this.sugar + "/" + MAX_SUGAR).withStyle(ChatFormatting.GRAY),
                                    Component.literal("In Inventory: " + inInv).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Will add: " + actual).withStyle(ChatFormatting.GREEN)
                            ));
                            this.resultSlots.setItem(0, res);
                        } else {
                            String reason = (capacityRemaining <= 0) ? "Sugar already at max (" + MAX_SUGAR + ")" : "You do not have any Sugar in your inventory";
                            ItemStack error = namedItem(Items.BARRIER, "Cannot Add Sugar", List.of(Component.literal(reason).withStyle(ChatFormatting.RED)));
                            this.resultSlots.setItem(0, error);
                        }
                    }
                    case SPAWNER -> {
                        int inInv = SuperSpawnerBlockEntity.this.countMatchingSpawners(this.player);
                        int maxPossible = this.player.getAbilities().instabuild ? amount : inInv;
                        int actual = Math.min(amount, maxPossible);
                        if (actual > 0) {
                            ItemStack res = SuperSpawnerBlockEntity.this.createDroppedSpawnerStack();
                            res.set(DataComponents.CUSTOM_NAME, Component.literal("Click to Add " + actual + " Super Spawner" + (actual > 1 ? "s" : "")));
                            res.set(DataComponents.LORE, new ItemLore(List.of(
                                    Component.literal("Requested: " + amount).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Current bonus: " + SuperSpawnerBlockEntity.this.bonusSpawners).withStyle(ChatFormatting.GRAY),
                                    Component.literal("In Inventory: " + inInv).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Will add: " + actual).withStyle(ChatFormatting.GREEN)
                            )));
                            this.resultSlots.setItem(0, res);
                        } else {
                            ItemStack error = namedItem(Items.BARRIER, "Cannot Add Super Spawners", List.of(
                                    Component.literal("You do not have any matching Super Spawners in your inventory").withStyle(ChatFormatting.RED)
                            ));
                            this.resultSlots.setItem(0, error);
                        }
                    }
                    case NETHER_STAR -> {
                        if (SuperSpawnerBlockEntity.this.hasNetherStar) {
                            this.resultSlots.setItem(0, namedItem(Items.BARRIER, "Nether Star Already Applied", List.of(
                                    Component.literal("Cannot apply more than 1 Nether Star").withStyle(ChatFormatting.RED)
                            )));
                        } else {
                            int inInv = SuperSpawnerBlockEntity.this.countItem(this.player, Items.NETHER_STAR);
                            if (inInv > 0 || this.player.getAbilities().instabuild) {
                                this.resultSlots.setItem(0, namedItem(Items.NETHER_STAR, "Click to Apply Nether Star", List.of(
                                        Component.literal("Will apply 1 Nether Star").withStyle(ChatFormatting.GREEN)
                                )));
                            } else {
                                this.resultSlots.setItem(0, namedItem(Items.BARRIER, "No Nether Star", List.of(
                                        Component.literal("You do not have a Nether Star in your inventory").withStyle(ChatFormatting.RED)
                                )));
                            }
                        }
                    }
                    case TOTEM -> {
                        if (SuperSpawnerBlockEntity.this.hasTotem) {
                            this.resultSlots.setItem(0, namedItem(Items.BARRIER, "Totem Already Applied", List.of(
                                    Component.literal("Cannot apply more than 1 Totem").withStyle(ChatFormatting.RED)
                            )));
                        } else {
                            int inInv = SuperSpawnerBlockEntity.this.countItem(this.player, Items.TOTEM_OF_UNDYING);
                            if (inInv > 0 || this.player.getAbilities().instabuild) {
                                this.resultSlots.setItem(0, namedItem(Items.TOTEM_OF_UNDYING, "Click to Apply Totem of Undying", List.of(
                                        Component.literal("Will apply 1 Totem").withStyle(ChatFormatting.GREEN)
                                )));
                            } else {
                                this.resultSlots.setItem(0, namedItem(Items.BARRIER, "No Totem of Undying", List.of(
                                        Component.literal("You do not have a Totem of Undying in your inventory").withStyle(ChatFormatting.RED)
                                )));
                            }
                        }
                    }
                }
            } else {
                switch (this.type) {
                    case SUGAR -> {
                        int current = SuperSpawnerBlockEntity.this.sugar;
                        int space = SuperSpawnerBlockEntity.this.getInventorySpace(this.player, new ItemStack(Items.SUGAR));
                        int maxPossible = Math.min(current, space);
                        int actual = Math.min(amount, maxPossible);
                        if (actual > 0) {
                            ItemStack res = namedItem(Items.SUGAR, "Click to Remove " + actual + " Sugar", List.of(
                                    Component.literal("Requested: " + amount).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Current in spawner: " + current).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Inventory Space: " + space).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Will remove: " + actual).withStyle(ChatFormatting.GREEN)
                            ));
                            this.resultSlots.setItem(0, res);
                        } else {
                            String reason = (current <= 0) ? "Spawner has no Sugar installed" : "Your inventory is full";
                            ItemStack error = namedItem(Items.BARRIER, "Cannot Remove Sugar", List.of(Component.literal(reason).withStyle(ChatFormatting.RED)));
                            this.resultSlots.setItem(0, error);
                        }
                    }
                    case SPAWNER -> {
                        int current = SuperSpawnerBlockEntity.this.bonusSpawners;
                        ItemStack spawnerStack = SuperSpawnerBlockEntity.this.createDroppedSpawnerStack();
                        int space = SuperSpawnerBlockEntity.this.getInventorySpace(this.player, spawnerStack);
                        int maxPossible = Math.min(current, space);
                        int actual = Math.min(amount, maxPossible);
                        if (actual > 0) {
                            ItemStack res = spawnerStack.copy();
                            res.set(DataComponents.CUSTOM_NAME, Component.literal("Click to Remove " + actual + " Super Spawner" + (actual > 1 ? "s" : "")));
                            res.set(DataComponents.LORE, new ItemLore(List.of(
                                    Component.literal("Requested: " + amount).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Current bonus: " + current).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Inventory Empty Slots: " + space).withStyle(ChatFormatting.GRAY),
                                    Component.literal("Will remove: " + actual).withStyle(ChatFormatting.GREEN)
                            )));
                            this.resultSlots.setItem(0, res);
                        } else {
                            String reason = (current <= 0) ? "Spawner has no bonus spawners installed" : "Your inventory has no empty slots";
                            ItemStack error = namedItem(Items.BARRIER, "Cannot Remove Super Spawners", List.of(Component.literal(reason).withStyle(ChatFormatting.RED)));
                            this.resultSlots.setItem(0, error);
                        }
                    }
                    case NETHER_STAR -> {
                        if (!SuperSpawnerBlockEntity.this.hasNetherStar) {
                            this.resultSlots.setItem(0, namedItem(Items.BARRIER, "No Nether Star Applied", List.of(
                                    Component.literal("Spawner does not have a Nether Star").withStyle(ChatFormatting.RED)
                            )));
                        } else {
                            int space = SuperSpawnerBlockEntity.this.getInventorySpace(this.player, new ItemStack(Items.NETHER_STAR));
                            if (space > 0) {
                                this.resultSlots.setItem(0, namedItem(Items.NETHER_STAR, "Click to Remove Nether Star", List.of(
                                        Component.literal("Will return 1 Nether Star").withStyle(ChatFormatting.GREEN)
                                )));
                            } else {
                                this.resultSlots.setItem(0, namedItem(Items.BARRIER, "Inventory Full", List.of(
                                        Component.literal("Your inventory is full").withStyle(ChatFormatting.RED)
                                )));
                            }
                        }
                    }
                    case TOTEM -> {
                        if (!SuperSpawnerBlockEntity.this.hasTotem) {
                            this.resultSlots.setItem(0, namedItem(Items.BARRIER, "No Totem Applied", List.of(
                                    Component.literal("Spawner does not have a Totem").withStyle(ChatFormatting.RED)
                            )));
                        } else {
                            int space = SuperSpawnerBlockEntity.this.getInventorySpace(this.player, new ItemStack(Items.TOTEM_OF_UNDYING));
                            if (space > 0) {
                                this.resultSlots.setItem(0, namedItem(Items.TOTEM_OF_UNDYING, "Click to Remove Totem of Undying", List.of(
                                        Component.literal("Will return 1 Totem").withStyle(ChatFormatting.GREEN)
                                )));
                            } else {
                                this.resultSlots.setItem(0, namedItem(Items.BARRIER, "Inventory Full", List.of(
                                        Component.literal("Your inventory is full").withStyle(ChatFormatting.RED)
                                )));
                            }
                        }
                    }
                }
            }
            this.broadcastChanges();
        }

        private void confirmAction(Player player) {
            if (this.confirmed) {
                return;
            }
            int amount = -1;
            try {
                if (this.typedText != null && !this.typedText.isEmpty()) {
                    amount = Integer.parseInt(this.typedText);
                }
            } catch (NumberFormatException ignored) {
            }
            if (amount <= 0) {
                return;
            }

            boolean modified = false;

            if (this.isAdd) {
                switch (this.type) {
                    case SUGAR -> {
                        int remainingCap = Math.max(0, MAX_SUGAR - SuperSpawnerBlockEntity.this.sugar);
                        int toTake = Math.min(amount, remainingCap);
                        if (toTake > 0) {
                            int consumed = SuperSpawnerBlockEntity.this.consumeItems(player, Items.SUGAR, toTake);
                            if (consumed > 0) {
                                SuperSpawnerBlockEntity.this.sugar += consumed;
                                modified = true;
                            }
                        }
                    }
                    case SPAWNER -> {
                        if (amount > 0) {
                            int consumed = SuperSpawnerBlockEntity.this.consumeMatchingSpawners(player, amount);
                            if (consumed > 0) {
                                SuperSpawnerBlockEntity.this.bonusSpawners += consumed;
                                SuperSpawnerBlockEntity.this.normalizeStoredItems();
                                modified = true;
                            }
                        }
                    }
                    case NETHER_STAR -> {
                        if (!SuperSpawnerBlockEntity.this.hasNetherStar && SuperSpawnerBlockEntity.this.consumeItem(player, Items.NETHER_STAR)) {
                            SuperSpawnerBlockEntity.this.hasNetherStar = true;
                            modified = true;
                        }
                    }
                    case TOTEM -> {
                        if (!SuperSpawnerBlockEntity.this.hasTotem && SuperSpawnerBlockEntity.this.consumeItem(player, Items.TOTEM_OF_UNDYING)) {
                            SuperSpawnerBlockEntity.this.hasTotem = true;
                            modified = true;
                        }
                    }
                }
            } else {
                switch (this.type) {
                    case SUGAR -> {
                        int current = SuperSpawnerBlockEntity.this.sugar;
                        int toRemove = Math.min(amount, current);
                        if (toRemove > 0) {
                            int returned = SuperSpawnerBlockEntity.this.returnUpgradeItems(player, new ItemStack(Items.SUGAR), toRemove);
                            if (returned > 0) {
                                SuperSpawnerBlockEntity.this.sugar -= returned;
                                modified = true;
                            }
                        }
                    }
                    case SPAWNER -> {
                        int current = SuperSpawnerBlockEntity.this.bonusSpawners;
                        int toRemove = Math.min(amount, current);
                        if (toRemove > 0) {
                            ItemStack spawnerStack = SuperSpawnerBlockEntity.this.createDroppedSpawnerStack();
                            int returned = SuperSpawnerBlockEntity.this.returnUpgradeItems(player, spawnerStack, toRemove);
                            if (returned > 0) {
                                SuperSpawnerBlockEntity.this.bonusSpawners -= returned;
                                SuperSpawnerBlockEntity.this.normalizeStoredItems();
                                modified = true;
                            }
                        }
                    }
                    case NETHER_STAR -> {
                        if (SuperSpawnerBlockEntity.this.hasNetherStar && SuperSpawnerBlockEntity.this.tryReturnUpgradeItem(player, new ItemStack(Items.NETHER_STAR))) {
                            SuperSpawnerBlockEntity.this.hasNetherStar = false;
                            modified = true;
                        }
                    }
                    case TOTEM -> {
                        if (SuperSpawnerBlockEntity.this.hasTotem && SuperSpawnerBlockEntity.this.tryReturnUpgradeItem(player, new ItemStack(Items.TOTEM_OF_UNDYING))) {
                            SuperSpawnerBlockEntity.this.hasTotem = false;
                            modified = true;
                        }
                    }
                }
            }

            if (modified) {
                SuperSpawnerBlockEntity.this.setChanged();
            }
            this.confirmed = true;
            this.inputSlots.clearContent();
            this.resultSlots.clearContent();
            player.openMenu(new SimpleMenuProvider((id, inv, p) -> SuperSpawnerBlockEntity.this.createUpgradesMenu(id, inv), Component.literal("Super Spawner - Upgrades")));
        }

        @Override
        public void clicked(int slotId, int button, net.minecraft.world.inventory.ContainerInput input, Player player) {
            if (slotId == 0 || slotId == 1) {
                return;
            }
            if (slotId == 2) {
                this.confirmAction(player);
                return;
            }
            if (slotId >= 3 && input == net.minecraft.world.inventory.ContainerInput.QUICK_MOVE) {
                return;
            }
            super.clicked(slotId, button, input, player);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slotIndex) {
            if (slotIndex == 2) {
                this.confirmAction(player);
                return ItemStack.EMPTY;
            }
            return ItemStack.EMPTY;
        }

        @Override
        public void removed(Player player) {
            this.inputSlots.clearContent();
            this.resultSlots.clearContent();
            super.removed(player);
        }
    }

    private AbstractContainerMenu createUpgradesMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory) {
        int rows = 3;
        SimpleContainer upgrades = new SimpleContainer(rows * 9);
        this.populateUpgradesMenu(upgrades);

        return this.createLockedMenu(containerId, inventory, upgrades, rows, "Super Spawner - Upgrades", (slot, button, input, menuPlayer) -> {
            if (slot == 26) {
                menuPlayer.openMenu(this);
                return;
            }

            if (input == net.minecraft.world.inventory.ContainerInput.QUICK_MOVE) {
                boolean isAdd = (button == 0);
                if (slot == 11) {
                    this.openUpgradeAnvilMenu(menuPlayer, UpgradeType.SUGAR, isAdd);
                    return;
                }
                if (slot == 12) {
                    this.openUpgradeAnvilMenu(menuPlayer, UpgradeType.SPAWNER, isAdd);
                    return;
                }
                if (slot == 13) {
                    this.openUpgradeAnvilMenu(menuPlayer, UpgradeType.NETHER_STAR, isAdd);
                    return;
                }
                if (slot == 14) {
                    this.openUpgradeAnvilMenu(menuPlayer, UpgradeType.TOTEM, isAdd);
                    return;
                }
                return;
            }

            if (button == 1) {
                if (slot == 11) {
                    if (this.sugar > 0 && this.tryReturnUpgradeItem(menuPlayer, new ItemStack(Items.SUGAR))) {
                        this.sugar--;
                        this.setChanged();
                        this.populateUpgradesMenu(upgrades);
                    }
                    return;
                }
                if (slot == 12) {
                    if (this.bonusSpawners > 0 && this.tryReturnUpgradeItem(menuPlayer, this.createDroppedSpawnerStack())) {
                        this.bonusSpawners--;
                        this.normalizeStoredItems();
                        this.setChanged();
                        this.populateUpgradesMenu(upgrades);
                    }
                    return;
                }
                if (slot == 13) {
                    if (this.hasNetherStar && this.tryReturnUpgradeItem(menuPlayer, new ItemStack(Items.NETHER_STAR))) {
                        this.hasNetherStar = false;
                        this.setChanged();
                        this.populateUpgradesMenu(upgrades);
                    }
                    return;
                }
                if (slot == 14) {
                    if (this.hasTotem && this.tryReturnUpgradeItem(menuPlayer, new ItemStack(Items.TOTEM_OF_UNDYING))) {
                        this.hasTotem = false;
                        this.setChanged();
                        this.populateUpgradesMenu(upgrades);
                    }
                    return;
                }
                return;
            }

            if (slot == 11 && this.sugar < MAX_SUGAR && this.consumeItem(menuPlayer, Items.SUGAR)) {
                this.sugar++;
                this.setChanged();
                this.populateUpgradesMenu(upgrades);
                return;
            }
            if (slot == 12 && this.consumeMatchingSpawner(menuPlayer)) {
                this.bonusSpawners++;
                this.normalizeStoredItems();
                this.setChanged();
                this.populateUpgradesMenu(upgrades);
                return;
            }
            if (slot == 13 && !this.hasNetherStar && this.consumeItem(menuPlayer, Items.NETHER_STAR)) {
                this.hasNetherStar = true;
                this.setChanged();
                this.populateUpgradesMenu(upgrades);
                return;
            }
            if (slot == 14 && !this.hasTotem && this.consumeItem(menuPlayer, Items.TOTEM_OF_UNDYING)) {
                this.hasTotem = true;
                this.setChanged();
                this.populateUpgradesMenu(upgrades);
            }
        });
    }

    private AbstractContainerMenu createStatsMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory) {
        int rows = 3;
        SimpleContainer stats = new SimpleContainer(rows * 9) {
            @Override
            public ItemStack getItem(int slot) {
                if (slot == 13) {
                    return SuperSpawnerBlockEntity.this.namedItem(Items.EXPERIENCE_BOTTLE, "Stored XP: " + SuperSpawnerBlockEntity.this.storedXp);
                }
                return super.getItem(slot);
            }
        };
        stats.setItem(10, this.createInventoryInteractivityToggleItem());
        stats.setItem(11, this.namedItem(Items.SPAWNER, "Mob: " + this.getMobType()));
        int intervalTicks = this.getIntervalTicks();
        double intervalSeconds = intervalTicks / 20.0;
        stats.setItem(12, this.namedItem(
                Items.SUGAR,
                "Sugar: " + this.sugar + "/" + MAX_SUGAR,
                List.of(Component.literal(String.format(java.util.Locale.ROOT, "Spawn Interval: %d ticks / %.2fs", intervalTicks, intervalSeconds)).withStyle(ChatFormatting.GRAY))
        ));
        stats.setItem(13, this.namedItem(Items.EXPERIENCE_BOTTLE, "Stored XP: " + this.storedXp));
        stats.setItem(14, this.namedItem(Items.CHEST, "Storage Slots: " + this.getStorageSlots()));
        stats.setItem(15, this.namedItem(Items.NETHER_STAR, "Nether Star: " + (this.hasNetherStar ? "Yes" : "No")));
        stats.setItem(16, this.namedItem(Items.TOTEM_OF_UNDYING, "Totem: " + (this.hasTotem ? "Yes" : "No")));
        stats.setItem(26, this.namedItem(Items.BARRIER, "Back"));

        return this.createLockedMenu(containerId, inventory, stats, rows, "Super Spawner - Stats", (slot, button, input, menuPlayer) -> {
            if (slot == 26) {
                menuPlayer.openMenu(this);
                return;
            }
            if (slot == 10) {
                this.inventoryInteractivityEnabled = !this.inventoryInteractivityEnabled;
                this.setChanged();
                stats.setItem(10, this.createInventoryInteractivityToggleItem());
            }
        });
    }

    private ItemStack createInventoryInteractivityToggleItem() {
        String state = this.inventoryInteractivityEnabled ? "Enabled" : "Disabled";
        return this.namedItem(Items.HOPPER, "Inventory Interactivity: " + state, List.of(
                Component.literal("Allows hoppers and other inventories to extract stored drops").withStyle(ChatFormatting.GRAY),
                Component.literal("Click to toggle").withStyle(ChatFormatting.YELLOW)
        ));
    }

    private AbstractContainerMenu createLockedMenu(
            int containerId,
            net.minecraft.world.entity.player.Inventory inventory,
            SimpleContainer container,
            int rows,
            String title,
            LockedMenuClickHandler slotClick
    ) {
        return new ChestMenu(MenuType.GENERIC_9x3, containerId, inventory, container, rows) {
            @Override
            public void clicked(int slotId, int button, net.minecraft.world.inventory.ContainerInput input, Player player) {
                if (slotId >= 0 && slotId < rows * 9) {
                    slotClick.handle(slotId, button, input, player);
                    return;
                }
                super.clicked(slotId, button, input, player);
            }
        };
    }

    private boolean tryReturnUpgradeItem(Player player, ItemStack stack) {
        return this.returnUpgradeItems(player, stack, 1) > 0;
    }

    private int getInventorySpace(Player player, ItemStack stack) {
        if (stack.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int maxStackSize = stack.getMaxStackSize();
        int space = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack existing = player.getInventory().getItem(i);
            if (existing.isEmpty()) {
                space += maxStackSize;
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                space += Math.max(0, maxStackSize - existing.getCount());
            }
        }
        return space;
    }

    private int returnUpgradeItems(Player player, ItemStack stack, int count) {
        if (count <= 0 || stack.isEmpty()) {
            return 0;
        }
        int space = this.getInventorySpace(player, stack);
        int toReturn = Math.min(count, space);
        if (toReturn <= 0) {
            player.sendSystemMessage(Component.literal("Your inventory is full!").withStyle(ChatFormatting.RED));
            return 0;
        }
        int remaining = toReturn;
        int maxStack = stack.getMaxStackSize();
        while (remaining > 0) {
            int batch = Math.min(remaining, maxStack);
            ItemStack copy = stack.copy();
            copy.setCount(batch);
            player.getInventory().add(copy);
            remaining -= batch;
        }
        return toReturn;
    }

    private int countItem(Player player, Item item) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countMatchingSpawners(Player player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(me.frost.superspawners.Superspawners.SUPER_SPAWNER_ITEM)) {
                continue;
            }
            Optional<Identifier> incoming = readMobTypeFromItem(stack);
            if (incoming.isPresent() && incoming.get().equals(this.getMobType())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int consumeItems(Player player, Item item, int amount) {
        if (amount <= 0) {
            return 0;
        }
        if (player.getAbilities().instabuild) {
            return amount;
        }
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                int toTake = Math.min(remaining, stack.getCount());
                stack.shrink(toTake);
                remaining -= toTake;
            }
        }
        return amount - remaining;
    }

    private int consumeMatchingSpawners(Player player, int amount) {
        if (amount <= 0) {
            return 0;
        }
        if (player.getAbilities().instabuild) {
            return amount;
        }
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(me.frost.superspawners.Superspawners.SUPER_SPAWNER_ITEM)) {
                continue;
            }
            Optional<Identifier> incoming = readMobTypeFromItem(stack);
            if (incoming.isPresent() && incoming.get().equals(this.getMobType())) {
                int toTake = Math.min(remaining, stack.getCount());
                stack.shrink(toTake);
                remaining -= toTake;
            }
        }
        return amount - remaining;
    }

    private ItemStack namedItem(Item item, String name) {
        return this.namedItem(item, name, List.of());
    }

    private ItemStack namedItem(Item item, String name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (lore != null && !lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }
        return stack;
    }

    private Item getMobTypeSpawnEggItem() {
        Optional<Holder.Reference<EntityType<?>>> typeRef = BuiltInRegistries.ENTITY_TYPE.get(this.getMobType());
        if (typeRef.isEmpty()) {
            return Items.CREEPER_SPAWN_EGG;
        }
        return net.minecraft.world.item.SpawnEggItem.byId(typeRef.get().value())
                .map(Holder::value)
                .orElse(Items.CREEPER_SPAWN_EGG);
    }

    private ItemStack namedPlayerHead(Player player, String name) {
        ItemStack stack = this.namedItem(Items.PLAYER_HEAD, name);
        CompoundTag tag = new CompoundTag();
        tag.putString("SkullOwner", player.getGameProfile().name());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private boolean consumeItem(Player player, Item item) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                return true;
            }
        }
        return false;
    }

    private boolean consumeMatchingSpawner(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(me.frost.superspawners.Superspawners.SUPER_SPAWNER_ITEM)) {
                continue;
            }
            Optional<Identifier> incoming = readMobTypeFromItem(stack);
            if (incoming.isPresent() && incoming.get().equals(this.getMobType())) {
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public int getContainerSize() {
        return this.getStorageSlots();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.storedItems) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        this.normalizeStoredItems();
        if (slot < 0 || slot >= this.storedItems.size()) {
            return ItemStack.EMPTY;
        }
        return this.storedItems.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack existing = this.getItem(slot);
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack split = existing.split(amount);
        if (!split.isEmpty()) {
            this.setChanged();
        }
        return split;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack existing = this.getItem(slot);
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }
        this.storedItems.set(slot, ItemStack.EMPTY);
        this.setChanged();
        return existing;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.normalizeStoredItems();
        if (slot < 0 || slot >= this.storedItems.size()) {
            return;
        }
        this.storedItems.set(slot, stack);
        this.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.level == null) {
            return false;
        }
        return player.distanceToSqr(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 0.5, this.worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        this.storedItems.clear();
        this.normalizeStoredItems();
        this.setChanged();
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        if (!this.inventoryInteractivityEnabled) {
            return new int[0];
        }
        this.normalizeStoredItems();
        int[] slots = new int[this.storedItems.size()];
        for (int index = 0; index < slots.length; index++) {
            slots[index] = index;
        }
        return slots;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @org.jetbrains.annotations.Nullable Direction direction) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return this.inventoryInteractivityEnabled;
    }

    private int getStorageSlots() {
        return BASE_STORAGE * (1 + this.bonusSpawners);
    }

    private int getIntervalTicks() {
        double speedMultiplier = 1.0 + (this.sugar * 0.01);
        int interval = (int) Math.round(BASE_INTERVAL_TICKS / speedMultiplier);
        return Math.max(MIN_INTERVAL_TICKS, interval);
    }

    private boolean canWork(ServerLevel level) {
        if (this.hasNetherStar) {
            return true;
        }
        return !level.getEntitiesOfClass(Player.class, new net.minecraft.world.phys.AABB(this.worldPosition).inflate(16.0)).isEmpty();
    }

    private void normalizeStoredItems() {
        int maxSlots = this.getStorageSlots();
        while (this.storedItems.size() < maxSlots) {
            this.storedItems.add(ItemStack.EMPTY);
        }
        if (this.storedItems.size() > maxSlots) {
            this.storedItems = new ArrayList<>(this.storedItems.subList(0, maxSlots));
        }
    }

    private void addDrop(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        this.normalizeStoredItems();
        for (int i = 0; i < this.storedItems.size(); i++) {
            ItemStack existing = this.storedItems.get(i);
            if (existing.isEmpty()) {
                this.storedItems.set(i, stack.copy());
                return;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack) && existing.getCount() < existing.getMaxStackSize()) {
                int move = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(move);
                stack.shrink(move);
                if (stack.isEmpty()) {
                    return;
                }
            }
        }
    }

    private void runSimulation(ServerLevel level) {
        Identifier mobId = this.getMobType();
        Optional<Holder.Reference<EntityType<?>>> typeRef = BuiltInRegistries.ENTITY_TYPE.get(mobId);
        if (typeRef.isEmpty()) {
            return;
        }

        EntityType<?> type = typeRef.get().value();
        Entity entity = type.create(level, EntitySpawnReason.SPAWNER);
        if (entity == null) {
            return;
        }
        entity.setPos(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 0.5, this.worldPosition.getZ() + 0.5);

        RandomSource random = level.getRandom();
        int runs = 1 + this.bonusSpawners;
        for (int i = 0; i < runs; i++) {
            Player sourcePlayer = this.hasTotem ? level.getRandomPlayer() : null;
            DamageSource damageSource = sourcePlayer != null
                    ? level.damageSources().playerAttack(sourcePlayer)
                    : level.damageSources().generic();

            LootParams.Builder paramsBuilder = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, entity.position())
                    .withParameter(LootContextParams.THIS_ENTITY, entity)
                    .withParameter(LootContextParams.DAMAGE_SOURCE, damageSource);
            if (sourcePlayer != null) {
                paramsBuilder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, sourcePlayer);
            }
            LootParams params = paramsBuilder.create(LootContextParamSets.ENTITY);
            if (!(entity instanceof LivingEntity livingEntity)) {
                continue;
            }
            Optional<net.minecraft.resources.ResourceKey<LootTable>> lootTableKey = livingEntity.getLootTable();
            if (lootTableKey.isEmpty()) {
                continue;
            }
            LootTable table = level.getServer().reloadableRegistries().getLootTable(lootTableKey.get());
            List<ItemStack> drops = table.getRandomItems(params, random.nextLong());
            for (ItemStack drop : drops) {
                this.addDrop(drop.copy());
            }
            this.storedXp += 1 + random.nextInt(5);
        }
        this.setChanged();
    }

    public static void writeMobTypeToItem(ItemStack stack, Identifier mobType) {
        CompoundTag tag = new CompoundTag();
        tag.putString(MOB_TYPE_KEY, mobType.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.LORE, new ItemLore(List.of(buildMobLoreLine(mobType))));
    }

    private static Component buildMobLoreLine(Identifier mobType) {
        Optional<Holder.Reference<EntityType<?>>> entityType = BuiltInRegistries.ENTITY_TYPE.get(mobType);
        Component entityName = entityType
                .map(reference -> reference.value().getDescription())
                .orElse(Component.literal(mobType.toString()));
        return Component.literal("Entity: ").append(entityName).withStyle(ChatFormatting.GRAY);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, SuperSpawnerBlockEntity entity) {
        if (level.isClientSide()) {
            return;
        }
        entity.tickCounter++;
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!entity.canWork(serverLevel)) {
            return;
        }
        int interval = entity.getIntervalTicks();
        if (entity.tickCounter % interval == 0) {
            entity.runSimulation(serverLevel);
        }
    }
}
