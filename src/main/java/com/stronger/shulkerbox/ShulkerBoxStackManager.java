package com.stronger.shulkerbox;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 服务端「打开栈」管理器：记录每个玩家当前打开的潜影盒链路。
 *
 * 栈底 = 顶层潜影盒（从背包打开），栈顶 = 当前正在展示的最深层潜影盒。
 * 嵌套打开时压栈；按 ESC 关闭时弹栈并重开上一层，实现「逐层返回」。
 *
 * v1.2.3 重构（修复「打开 X 时把里面的 Y 也锁住」）：
 *  - 锁定判据不再依赖「全局标记集合」，而是实时查询「打开链」本身。
 *    * 只有链中真正被打开的宿主盒子才会被锁；
 *    * 打开 X 时只锁 X，X 内部的 Y 不会被锁，可以正常取出；
 *    * 嵌套打开 Y 才锁 Y；ESC 返回 X 后 Y 自动解锁；
 *    * 不存在残留标记，天然自愈，不会「永久锁死」。
 *  - 打开链改用线程安全容器（ConcurrentHashMap + ConcurrentLinkedDeque），
 *    使客户端线程可安全只读查询（单机同 JVM 场景），不会抛 CME。
 */
public class ShulkerBoxStackManager {
    private static final Map<ServerPlayer, Deque<ShulkerBoxItemContainer>> STACKS = new ConcurrentHashMap<>();
    private static final Map<ServerPlayer, Boolean> SWITCHING = new ConcurrentHashMap<>();

    private ShulkerBoxStackManager() {
    }

    public static Deque<ShulkerBoxItemContainer> stackOf(ServerPlayer player) {
        return STACKS.computeIfAbsent(player, p -> new ConcurrentLinkedDeque<>());
    }

    /** 标记「正在切换菜单」（嵌套打开 / 回退），使 Close 事件不误弹栈 */
    public static void setSwitching(ServerPlayer player, boolean switching) {
        SWITCHING.put(player, switching);
    }

    public static boolean isSwitching(ServerPlayer player) {
        return Boolean.TRUE.equals(SWITCHING.get(player));
    }

    /** 压栈：打开更深一层 */
    public static void push(ServerPlayer player, ShulkerBoxItemContainer container) {
        stackOf(player).push(container);
    }

    /** 弹栈：返回上一层，返回 null 表示已到栈底（应回背包） */
    public static ShulkerBoxItemContainer pop(ServerPlayer player) {
        Deque<ShulkerBoxItemContainer> stack = stackOf(player);
        if (stack.isEmpty()) {
            return null;
        }
        stack.pop();
        return stack.peek();
    }

    /** 当前栈顶（正在展示的容器），无则 null */
    public static ShulkerBoxItemContainer peek(ServerPlayer player) {
        return stackOf(player).peek();
    }

    /** 玩家退出/断线时清理 */
    public static void clear(ServerPlayer player) {
        STACKS.remove(player);
        SWITCHING.remove(player);
    }

    /** 仅清空打开栈（不清 switching 标志）：用于「从背包重新打开」时重置链路 */
    public static void clearStack(ServerPlayer player) {
        Deque<ShulkerBoxItemContainer> stack = STACKS.get(player);
        if (stack != null) {
            stack.clear();
        }
    }

    /**
     * 该玩家当前打开链中，是否存在「宿主物品恰为 stack（按对象同一性）」的容器。
     * 用于 mayPickup(Player) 这类「精确到具体玩家」的校验。
     */
    public static boolean isOpenHost(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Deque<ShulkerBoxItemContainer> chain = STACKS.get(player);
        if (chain == null) {
            return false;
        }
        for (ShulkerBoxItemContainer c : chain) {
            if (c.getShulkerBoxStack() == stack) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在任意玩家的打开链中，宿主物品恰为 stack。
     * 用于 mayPlace(ItemStack) 这类「拿不到 Player」的校验。
     */
    public static boolean isAnyOpenHost(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        for (Deque<ShulkerBoxItemContainer> chain : STACKS.values()) {
            for (ShulkerBoxItemContainer c : chain) {
                if (c.getShulkerBoxStack() == stack) {
                    return true;
                }
            }
        }
        return false;
    }
}
