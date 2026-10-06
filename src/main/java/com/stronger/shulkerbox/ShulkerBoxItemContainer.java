package com.stronger.shulkerbox;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * 将一个「潜影盒物品」（ItemStack）的 CONTAINER 组件暴露为一个 27 格的
 * Container，并把所有修改写回「父容器」的指定槽位。
 *
 * 这是第二阶段「背包内直接打开潜影盒」的核心：原版 ShulkerBoxMenu
 * 接受任意 Container，我们把 ItemStack 适配成 Container 即可复用整个
 * 原版 GUI 逻辑（包括 ShulkerBoxSlot 的防嵌套限制）。
 *
 * 支持两种挂载方式：
 *  - 顶层（从背包打开）：parent = 背包 Inventory，slotIndex = 背包槽位号
 *  - 嵌套（从已打开的潜影盒里打开）：parent = 父级 ShulkerBoxItemContainer，
 *    slotIndex = 该潜影盒在父容器中的槽位号
 *
 * 每次修改后都会通过 parent.setItem / setChanged 逐级向上传播，
 * 最终写回背包槽位里的 ItemStack 组件，保证「关掉 GUI 后数据仍在」。
 */
public class ShulkerBoxItemContainer implements Container {
    private static final int SIZE = 27;

    /** 本容器持有的潜影盒物品 */
    private final ItemStack shulkerBoxStack;
    /** 父容器（背包 Inventory 或父级 ShulkerBoxItemContainer） */
    private final Container parent;
    /** 本物品在父容器中的槽位 */
    private final int slotIndex;
    /** 玩家背包（用于 setChanged 同步） */
    private final net.minecraft.world.entity.player.Inventory playerInventory;

    public ShulkerBoxItemContainer(ItemStack shulkerBoxStack, Container parent, int slotIndex, net.minecraft.world.entity.player.Inventory playerInventory) {
        this.shulkerBoxStack = shulkerBoxStack;
        this.parent = parent;
        this.slotIndex = slotIndex;
        this.playerInventory = playerInventory;
    }

    /** 读取当前 CONTAINER 组件内容 */
    private ItemContainerContents contents() {
        return shulkerBoxStack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }
    /** 菜单显示名：潜影盒物品自身的名称（如「白色潜影盒」） */
    public Component getHoverNameForMenu() {
        return shulkerBoxStack.getHoverName();
    }

    @Override
    public boolean isEmpty() {
        ItemContainerContents c = contents();
        return c == null || c.getSlots() == 0 || c.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= SIZE) {
            return ItemStack.EMPTY;
        }
        ItemContainerContents c = contents();
        if (c == null || c.getSlots() <= slot) {
            return ItemStack.EMPTY;
        }
        return c.getStackInSlot(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack current = getItem(slot);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (amount >= current.getCount()) {
            setItem(slot, ItemStack.EMPTY);
            return current;
        }
        ItemStack removed = current.split(amount);
        setItem(slot, current);
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = getItem(slot);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        setItem(slot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        ItemContainerContents old = contents();
        NonNullList<ItemStack> list = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        if (old != null) {
            old.copyInto(list);
        }
        list.set(slot, stack.copy());
        // 写入组件
        shulkerBoxStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(list));
        // 写回父容器（背包槽位或父级潜影盒容器），触发逐级传播
        parent.setItem(slotIndex, shulkerBoxStack);
        parent.setChanged();
        playerInventory.setChanged();
    }

    @Override
    public void setChanged() {
        parent.setChanged();
        playerInventory.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < SIZE; i++) {
            setItem(i, ItemStack.EMPTY);
        }
    }
}
