package com.stronger.shulkerbox;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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

    /**
     * 客户端本地镜像：当前正在打开的宿主盒子（客户端与服务端各自维护集合）。
     *
     * v1.2.3 起，服务端不再使用「全局标记集合」，而是实时查询打开链
     *（{@link ShulkerBoxStackManager#isOpenHost}/{@link ShulkerBoxStackManager#isAnyOpenHost}），
     * 从根本上避免「残留标记把不该锁的盒子也锁住」。此集合仅用于客户端侧校验。
     */
    private static final Set<ItemStack> CLIENT_OPEN =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("strongershulkerbox");

    public static void markOpenClient(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) CLIENT_OPEN.add(stack);
    }

    public static void clearOpenClient() {
        CLIENT_OPEN.clear();
    }

    /**
     * 该物品是否为「某个玩家当前打开链中的宿主盒子」（拿不到 Player 时使用，如 mayPlace）。
     * 判据完全来自实时打开链 + 客户端镜像，不存在任何残留标记。
     */
    public static boolean isOpenBacking(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (CLIENT_OPEN.contains(stack)) {
            return true;
        }
        return ShulkerBoxStackManager.isAnyOpenHost(stack);
    }

    /**
     * 该物品是否为「该玩家当前打开链中的宿主盒子」（精确到具体玩家，如 mayPickup）。
     * 打开 X 时链中只有 X，故 X 内的 Y 不会命中，可正常取出。
     */
    public static boolean isOpenBackingFor(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        boolean clientHit = CLIENT_OPEN.contains(stack);
        boolean hostHit = player instanceof ServerPlayer sp && ShulkerBoxStackManager.isOpenHost(sp, stack);
        return clientHit || hostHit;
    }

    /** 权威后备存储（可变） */
    private final NonNullList<ItemStack> items;
    /**
     * 写回目标的潜影盒物品（就是背包/父容器里的那个真实对象）。
     *
     * v1.2.5 起不再 final：打开期间若有外部操作（一键整理等会重建堆叠对象）
     * 把槽位里的宿主替换成新实例，writeBack 会在重定位时把本引用指向新宿主，
     * 保证写回永远作用于「背包中真实存在的对象」，而不是被替换掉的孤儿对象。
     */
    private ItemStack shulkerBoxStack;
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
        // v1.2.5：先重定位宿主（若被外部操作替换成新对象，则接管真实对象），
        // 再写入内容，避免写回落在孤儿对象上造成「取出不消失」的复制。
        relocateHost();
        if (shulkerBoxStack != null && !shulkerBoxStack.isEmpty()) {
            shulkerBoxStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        }
        if (parent != null) {
            parent.setChanged();
        }
    }

    /** 在父容器里按对象同一性查找本宿主物品所在槽位，找不到返回 -1。 */
    private int findSlot(Container c) {
        int size = c.getContainerSize();
        for (int i = 0; i < size; i++) {
            if (c.getItem(i) == shulkerBoxStack) {
                return i;
            }
        }
        return -1;
    }

    /** 在父容器里按对象同一性查找指定物品所在槽位，找不到返回 -1。 */
    private static int findSlotOf(Container c, ItemStack target) {
        int size = c.getContainerSize();
        for (int i = 0; i < size; i++) {
            if (c.getItem(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /** 宿主的父容器（背包或父级潜影盒容器）。 */
    public Container getParent() {
        return parent;
    }

    /** 宿主在父容器中的槽位记录（可能随写回自动校正）。 */
    public int getSlotIndex() {
        return slotIndex;
    }

    /**
     * 重定位宿主引用：确保 writeBack 作用于「父容器中真实存在的对象」。
     *
     *  - 同一性优先：宿主对象仍在父容器中（可能被 GUI 挪到别的槽位），只校正 slotIndex；
     *  - 对象已被替换（一键整理等会重建堆叠对象）：接管父容器中「唯一同类型」的
     *    替换对象（优先记录槽位，其次全容器唯一匹配），让后续写回落在真实对象上；
     *  - 找不到 / 有歧义：保持原引用，仅告警（延续 v1.2.4 探针行为）。
     */
    private void relocateHost() {
        if (parent == null || shulkerBoxStack == null || shulkerBoxStack.isEmpty()) {
            return;
        }
        // 同一性优先：宿主对象仍在父容器中（可能被挪到别的槽位）
        int idSlot = findSlot(parent);
        if (idSlot >= 0) {
            if (idSlot != slotIndex) {
                slotIndex = idSlot;
            }
            return;
        }
        // 对象被替换：尝试接管真实对象
        ItemStack replacement = findReplacement(parent);
        if (replacement != null && !replacement.isEmpty()) {
            int repSlot = findSlotOf(parent, replacement);
            LOGGER.warn("[SB-FIX] host {} re-anchored (recorded slot {} -> slot {}) after external replacement",
                    replacement.getItem(), slotIndex, repSlot);
            shulkerBoxStack = replacement;
            slotIndex = repSlot;
            return;
        }
        // 彻底找不到：延续 v1.2.4 告警，用于定位「宿主被顶替/丢失」
        LOGGER.warn("[SB-ALERT] host {} (recorded slot {}) is NOT in player inventory any more",
                shulkerBoxStack.getItem(), slotIndex);
    }

    /**
     * 宿主对象被替换后，在父容器中找回「真实宿主」。
     * 规则：优先记录槽位（若仍是同类型潜影盒）；否则全容器唯一同类型。
     * 多个同类型导致无法唯一判定时返回 null（宁可告警，也不写错盒子）。
     */
    private ItemStack findReplacement(Container parent) {
        if (slotIndex >= 0 && slotIndex < parent.getContainerSize()) {
            ItemStack atRecorded = parent.getItem(slotIndex);
            if (atRecorded != null && !atRecorded.isEmpty()
                    && atRecorded.getItem() == shulkerBoxStack.getItem()) {
                return atRecorded;
            }
        }
        ItemStack only = null;
        int count = 0;
        int size = parent.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack s = parent.getItem(i);
            if (s != null && !s.isEmpty() && s.getItem() == shulkerBoxStack.getItem()) {
                count++;
                only = s;
            }
        }
        return count == 1 ? only : null;
    }

    /**
     * 宿主物品是否仍存在于其父容器中（按对象同一性）。
     * 用于每个 tick 的一致性检查：一旦宿主被移出/顶替，立即告警。
     */
    public boolean hostStillPresent() {
        Container p = parent;
        if (p == null || shulkerBoxStack == null || shulkerBoxStack.isEmpty()) {
            return true;
        }
        int size = p.getContainerSize();
        for (int i = 0; i < size; i++) {
            if (p.getItem(i) == shulkerBoxStack) {
                return true;
            }
        }
        return false;
    }

    private boolean lostAlertLogged = false;

    /** 仅在第一次发现宿主丢失时返回 true（避免每 tick 刷屏）。 */
    public boolean markLostAlertOnce() {
        if (lostAlertLogged) {
            return false;
        }
        lostAlertLogged = true;
        return true;
    }
}