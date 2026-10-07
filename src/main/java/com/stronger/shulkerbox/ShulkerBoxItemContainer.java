package com.stronger.shulkerbox;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 将一个「潜影盒物品」的 CONTAINER 组件暴露为 27 格的 Container，
 * 并把所有修改写回「父容器」的指定槽位。
 *
 * v1.2.0 重构（修复吞物品）：
 *  - 内部改用一份「权威、可变」的 NonNullList 作为后备存储；
 *  - getItem() 返回后备列表里的真实对象（与原版容器语义一致）。
 *
 * 旧实现 getItem() 每次从不可变组件新建副本，而原版 moveItemStackTo 的
 * 「合并堆叠」分支会直接对 getItem() 返回的对象 setCount(...) 再 setChanged()，
 * 那些原地修改全部丢失 —— 表现为「Shift 连续放入只有第一组留下」，
 * 一键整理类模组更是整批丢物品。
 *
 * 支持两种挂载方式：
 *  - 顶层（从背包打开）：parent = 背包 Inventory，slotIndex = 背包槽位号
 *  - 嵌套（从已打开的潜影盒里打开）：parent = 父级 ShulkerBoxItemContainer，
 *    slotIndex = 该潜影盒在父容器中的槽位号
 */
public class ShulkerBoxItemContainer implements Container {
    private static final int SIZE = 27;

    /** 服务端：当前正在被打开的潜影盒物品（按对象同一性），用于阻止自包含/祖先包含。 */
    private static final Set<ItemStack> SERVER_OPEN =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /** 客户端：同上（客户端与服务端各自维护，互不干扰）。 */
    private static final Set<ItemStack> CLIENT_OPEN =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    public static void markOpenServer(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) SERVER_OPEN.add(stack);
    }

    public static void unmarkOpenServer(ItemStack stack) {
        if (stack != null) SERVER_OPEN.remove(stack);
    }

    public static void clearOpenServer() {
        SERVER_OPEN.clear();
    }

    public static void markOpenClient(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) CLIENT_OPEN.add(stack);
    }

    public static void clearOpenClient() {
        CLIENT_OPEN.clear();
    }

    /** 该物品是否正作为某个（本层/祖先层）已打开潜影盒的宿主物品。 */
    public static boolean isOpenBacking(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && (SERVER_OPEN.contains(stack) || CLIENT_OPEN.contains(stack));
    }

    /** 权威后备存储（可变） */
    private final NonNullList<ItemStack> items;
    /** 写回目标的潜影盒物品（就是背包/父容器里的那个真实对象） */
    private final ItemStack shulkerBoxStack;
    /** 父容器（背包 Inventory 或父级 ShulkerBoxItemContainer） */
    private final Container parent;
    /** 本物品在父容器中的槽位；可能因玩家在 GUI 内挪动而重定位 */
    private int slotIndex;
    /** 玩家背包（用于同步） */
    private final Inventory playerInventory;

    public ShulkerBoxItemContainer(ItemStack shulkerBoxStack, Container parent, int slotIndex, Inventory playerInventory) {
        this.shulkerBoxStack = shulkerBoxStack;
        this.parent = parent;
        this.slotIndex = slotIndex;
        this.playerInventory = playerInventory;
        this.items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ItemContainerContents c = shulkerBoxStack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        c.copyInto(items);
    }

    public ItemStack getShulkerBoxStack() {
        return shulkerBoxStack;
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
        for (int i = 0; i < SIZE; i++) {
            if (!items.get(i).isEmpty()) return false;
        }
        return true;
    }

    /** 返回后备列表里的真实对象（可变），与原版容器语义一致。 */
    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= SIZE) {
            return ItemStack.EMPTY;
        }
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= SIZE || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack current = items.get(slot);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed;
        if (amount >= current.getCount()) {
            removed = current;
            items.set(slot, ItemStack.EMPTY);
        } else {
            removed = current.split(amount);
        }
        writeBack();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= SIZE) {
            return ItemStack.EMPTY;
        }
        ItemStack current = items.get(slot);
        items.set(slot, ItemStack.EMPTY);
        writeBack();
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        items.set(slot, stack);
        writeBack();
    }

    /**
     * 原版合并逻辑会「原地改 getItem() 再 setChanged()」，这里必须落盘，
     * 否则计数变化（合并堆叠）会丢失。
     */
    @Override
    public void setChanged() {
        writeBack();
        if (playerInventory != null) {
            playerInventory.setChanged();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < SIZE; i++) {
            items.set(i, ItemStack.EMPTY);
        }
        writeBack();
    }

    /** 自动化（漏斗等）放入校验：嵌套深度 + 环检测。 */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return ShulkerBoxNesting.canPlaceIntoShulker(stack) && !wouldCreateCycle(stack);
    }

    /**
     * 判断把 incoming 放进本容器是否会形成自包含/祖先包含（会导致物品被吞）。
     * 沿「本容器 → 父级」链条比对对象同一性，以及 incoming 内部是否已包含祖先盒子。
     */
    public boolean wouldCreateCycle(ItemStack incoming) {
        if (incoming == null || incoming.isEmpty() || !ShulkerBoxNesting.isShulkerBoxItem(incoming)) {
            return false;
        }
        Container node = this;
        int guard = 0;
        while (node instanceof ShulkerBoxItemContainer sbc && guard++ < 64) {
            if (sbc.shulkerBoxStack == incoming) {
                return true;
            }
            if (containsIdentity(incoming, sbc.shulkerBoxStack, 0)) {
                return true;
            }
            node = sbc.parent;
        }
        return false;
    }

    private static boolean containsIdentity(ItemStack box, ItemStack target, int depth) {
        if (depth > 8 || box == null || box.isEmpty() || target == null || target.isEmpty()) {
            return false;
        }
        ItemContainerContents c = box.get(DataComponents.CONTAINER);
        if (c == null) {
            return false;
        }
        for (ItemStack child : c.nonEmptyItems()) {
            if (child == target) {
                return true;
            }
            if (containsIdentity(child, target, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把权威 items 序列化回潜影盒物品，并向上传播。
     *
     * v1.2.1 关键修复（物品复制漏洞）：
     *  顶层时，写回的潜影盒物品就是背包槽位里的「同一个对象」——修改它的组件
     *  已经就地生效。因此这里【绝不能】再调用 inv.setItem 把它写回槽位：
     *  一旦物品此刻不在原槽位（例如被玩家用手抓着、或挪到了别处），
     *  旧代码会把它重新塞回原槽，凭空多出一个潜影盒，形成复制。
     */
    private void writeBack() {
        shulkerBoxStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        if (parent instanceof Inventory inv) {
            // 同一个对象，改组件已就地生效；这里只做同步标记，绝不新建/复制物品
            inv.setChanged();
        } else if (parent != null) {
            // 嵌套：父容器已持有本物品对象，触发其重新序列化即可逐级向上传播
            parent.setChanged();
        }
    }
}