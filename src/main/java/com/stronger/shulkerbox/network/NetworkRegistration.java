package com.stronger.shulkerbox.network;

import com.stronger.shulkerbox.ShulkerBoxItemContainer;
import com.stronger.shulkerbox.ShulkerBoxNesting;
import com.stronger.shulkerbox.ShulkerBoxStackManager;
import com.stronger.shulkerbox.StrongerShulkerBoxMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络注册与服务端处理（第二阶段核心）：
 *
 * 客户端右键潜影盒 → 发送 {@link ShulkerBoxOpenPayload} → 本类服务端处理器
 * 打开对应的 ShulkerBoxMenu（复用原版 MenuType.SHULKER_BOX，无需自建菜单/屏幕）。
 *
 * 支持嵌套逐层打开与 ESC 逐层返回：
 *  - 服务端为每个玩家维护「打开栈」{@link ShulkerBoxStackManager}；
 *  - 顶层打开：从背包槽位取潜影盒物品，压栈后 openMenu；
 *  - 嵌套打开：从当前栈顶容器的槽位取子潜影盒物品，挂到父容器上，压栈后 openMenu；
 *  - ESC 关闭：监听 {@link PlayerContainerEvent.Close}，若栈非空则弹栈并重开上一层。
 *
 * 复用原版 MenuType 的依据：服务端 ShulkerBoxMenu 构造后 sendInitialData 会把
 * 真实容器内容逐槽同步给客户端，客户端仅用空 SimpleContainer(27) 做展示框架。
 */
public class NetworkRegistration {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("strongershulkerbox");

    /**
     * 待重开的上层容器（延迟到下一个服务端 tick 再打开）。
     *
     * 为什么不能在 Close 事件里同步重开：
     *   原版 {@code ServerPlayer.doCloseContainer()} 的执行顺序是
     *     containerMenu.removed(player) → inventoryMenu.transferState(containerMenu)
     *     → 广播 PlayerContainerEvent.Close → containerMenu = inventoryMenu
     *   而我们正是从这个 Close 事件里被调用的。事件返回之后，原版紧接着执行
     *   {@code containerMenu = inventoryMenu}，会把我们刚 openMenu 出来的新菜单
     *   直接覆盖掉，造成「服务端菜单 ≠ 客户端界面」的错位。
     *
     * 错位的致命后果（v1.2.4 根因）：
     *   客户端界面的菜单 id 与服务端当前菜单 id 不一致后，客户端发来的点击包
     *   里的「槽位号」会被服务端按错误的菜单解读（槽位号错位别名），
     *   于是物品被搬进完全不同的槽位；更糟的是服务端对 id 不匹配的点击是
     *   「静默忽略」（无日志），玩家看到的是客户端预测结果，两者越走越远。
     *   本次存档取证中「宿主 X 所在槽位被它自己的子盒顶替、X 及其余子盒消失」
     *   就是这条路径造成的。
     *
     * 因此这里只登记「待重开」，等关闭流程彻底结束后，由
     * {@link #onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post)} 再打开。
     */
    private static final java.util.Map<ServerPlayer, ShulkerBoxItemContainer> PENDING_REOPEN =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(NetworkRegistration::registerPayloads);
        // 游戏事件（Close / 登出 / 每 tick 巡检）发在游戏总线 NeoForge.EVENT_BUS，需要手动注册
        NeoForge.EVENT_BUS.addListener(NetworkRegistration::onPlayerContainerClose);
        NeoForge.EVENT_BUS.addListener(NetworkRegistration::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(NetworkRegistration::onServerTick);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                ShulkerBoxOpenPayload.TYPE,
                ShulkerBoxOpenPayload.STREAM_CODEC,
                new IPayloadHandler<ShulkerBoxOpenPayload>() {
                    @Override
                    public void handle(ShulkerBoxOpenPayload payload, IPayloadContext context) {
                        context.enqueueWork(() -> {
                            Player player = context.player();
                            if (player instanceof ServerPlayer serverPlayer) {
                                openShulkerBox(serverPlayer, payload.fromShulker(), payload.slotIndex());
                            }
                        });
                    }
                }
        );
    }

    /**
     * 服务端：根据来源与槽位号打开潜影盒菜单。
     *
     * @param fromShulker true  = 目标在已打开的潜影盒界面内部（嵌套打开），slotIndex 为潜影盒内部槽位 0-26；
     *                    false = 目标在背包/快捷栏（顶层打开），slotIndex 为 Inventory 槽位 0-35
     */
    private static void openShulkerBox(ServerPlayer player, boolean fromShulker, int slotIndex) {
        Inventory inventory = player.getInventory();
        ItemStack stack;
        ShulkerBoxItemContainer parentContainer = null;
        int parentSlot = slotIndex;

        if (fromShulker) {
            // 嵌套打开：目标在「当前已打开的潜影盒容器」内部
            ShulkerBoxItemContainer top = ShulkerBoxStackManager.peek(player);
            if (top == null || slotIndex < 0 || slotIndex >= top.getContainerSize()) {
                return; // 状态异常，忽略
            }
            ItemStack child = top.getItem(slotIndex);
            if (!ShulkerBoxNesting.isShulkerBoxItem(child)) {
                return; // 目标槽位不是潜影盒
            }
            stack = child;
            parentContainer = top;
        } else {
            // 顶层打开：目标在背包/快捷栏
            if (slotIndex < 0 || slotIndex >= inventory.getContainerSize()) {
                return;
            }
            ItemStack candidate = inventory.getItem(slotIndex);
            if (!ShulkerBoxNesting.isShulkerBoxItem(candidate)) {
                return; // 目标槽位不是潜影盒
            }
            stack = candidate;
            parentContainer = null;
            // 从背包打开：重置打开栈，本潜影盒成为新的栈底
            ShulkerBoxStackManager.clearStack(player);
        }

        final ItemStack finalStack = stack;
        final ShulkerBoxItemContainer adapter = new ShulkerBoxItemContainer(
                finalStack,
                parentContainer != null ? parentContainer : inventory,
                parentSlot,
                inventory
        );

        // 安全网：openMenu 在「当前菜单就是玩家自己的背包菜单」时会跳过关闭流程，
        // 导致光标上的物品被永久滞留（既不在背包、也不在新菜单里，退出存档即丢失）。
        // 因此在切换前，先把背包菜单光标上的物品放回背包。
        returnStrandedCursorItem(player);

        LOGGER.info("[SB-LIFE] open {} host={} chain={} at {}",
                fromShulker ? "nested" : "top",
                stack.getItem(),
                ShulkerBoxStackManager.stackOf(player).size(),
                (parentContainer != null ? "parent#" : "inv#") + parentSlot);

        // 标记「正在切换菜单」，避免 openMenu 隐式关闭旧菜单时被 Close 事件误判为 ESC
        ShulkerBoxStackManager.setSwitching(player, true);
        try {
            openMenu(player, adapter);
            // 压栈记录：该容器成为新的栈顶（正在展示的最深层）
            ShulkerBoxStackManager.push(player, adapter);
        } finally {
            ShulkerBoxStackManager.setSwitching(player, false);
        }
    }

    /**
     * 把玩家「背包菜单」光标上的物品放回背包，避免它在菜单切换时被滞留丢失。
     *
     * 背景：原版 ServerPlayer.openMenu 只有在 containerMenu != inventoryMenu 时才会
     * 关闭旧菜单（进而通过 removed() 把光标物品交还背包）。若当前菜单恰好就是背包菜单，
     * 则该关闭被跳过，光标物品会一直留在 inventoryMenu 里——新菜单的光标是空的，
     * 于是这件物品既看不见也拿不回，退出存档后彻底丢失。
     */
    private static void returnStrandedCursorItem(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != player.inventoryMenu) {
            return;
        }
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().placeItemBackInInventory(carried);
        player.getInventory().setChanged();
        LOGGER.warn("[SB-FIX] returned stranded cursor item {} to inventory", carried.getItem());
    }

    /** 打开一个潜影盒菜单（复用原版 ShulkerBoxMenu） */
    private static void openMenu(ServerPlayer player, Container container) {
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                // 使用潜影盒物品自身的显示名（如「白色潜影盒」）
                return container instanceof ShulkerBoxItemContainer sc
                        ? sc.getHoverNameForMenu()
                        : Component.literal("Shulker Box");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inv, Player p) {
                return new ShulkerBoxMenu(containerId, inv, container);
            }
        });
    }

    /**
     * ESC / 关闭菜单钩子：玩家关闭当前容器菜单时触发。
     *
     * 若当前关闭的是我们的潜影盒菜单（栈顶），且处于「用户主动 ESC」而非
     * 「嵌套切换的隐式关闭」，则弹栈并重开上一层；栈底则回到背包（不重开）。
     */
    public static void onPlayerContainerClose(PlayerContainerEvent.Close event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (ShulkerBoxStackManager.isSwitching(serverPlayer)) {
            return; // 正在切换菜单（嵌套打开/回退重开），不弹栈
        }
        // 已经有一次「待重开」挂起（ESC 关闭 → 下一个 tick 重开之间的空档），
        // 期间玩家若又关掉了中间态的背包菜单，这里必须忽略，否则会把栈顶也弹掉。
        if (PENDING_REOPEN.containsKey(serverPlayer)) {
            return;
        }
        // 栈为空说明不是我们的潜影盒菜单，忽略
        if (ShulkerBoxStackManager.peek(serverPlayer) == null) {
            return;
        }
        // 弹栈：返回上一层；若弹栈后仍有栈顶容器，则「延迟」重开它。
        // 绝不在此处同步 openMenu——原因见 PENDING_REOPEN 字段注释。
        ShulkerBoxItemContainer parent = ShulkerBoxStackManager.pop(serverPlayer);
        if (parent != null) {
            PENDING_REOPEN.put(serverPlayer, parent);
        }
    }

    /**
     * 每个服务端 tick 收尾时做两件事：
     *
     * 1) 执行「延迟重开」：把上一次 ESC 弹栈后应返回的上层菜单打开。
     *    此时原版 doCloseContainer() 的最后一行 `containerMenu = inventoryMenu`
     *    已经执行完毕，我们打开的菜单不会再被覆盖，端-服菜单始终一致。
     *
     * 2) 一致性巡检：遍历每个玩家打开链，检查链中每个宿主的潜影盒物品是否
     *    仍留在它的父容器里（按对象同一性）。一旦发现「宿主被顶替/移走」，
     *    立即告警——这正是本次「吞物品」在存档层面留下的形态。
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // 1) 延迟重开上层菜单
            ShulkerBoxItemContainer pending = PENDING_REOPEN.remove(player);
            if (pending != null) {
                if (player.hasDisconnected() || player.isRemoved()) {
                    ShulkerBoxStackManager.clear(player);
                    continue;
                }
                // openMenu 在「当前菜单就是背包菜单」时会跳过关闭流程，
                // 光标上的物品同样需要先交还背包（双重保险，避免滞留）。
                returnStrandedCursorItem(player);
                ShulkerBoxStackManager.setSwitching(player, true);
                try {
                    openMenu(player, pending);
                    LOGGER.info("[SB-LIFE] reopen parent host={} chain={}",
                            pending.getShulkerBoxStack().getItem(),
                            ShulkerBoxStackManager.stackOf(player).size());
                } catch (RuntimeException ex) {
                    LOGGER.error("[SB-ALERT] failed to reopen parent shulker box {}",
                            pending.getShulkerBoxStack().getItem(), ex);
                } finally {
                    ShulkerBoxStackManager.setSwitching(player, false);
                }
            }

            // 2) 一致性巡检：打开链中的宿主必须仍在父容器里。
            //    v1.2.6：先尝试重定位宿主（v1.2.5 机制），若失败（宿主彻底丢失/有歧义，
            //    例如背包里有多个同类型盒子无法唯一判定）→ 强制关闭该玩家的潜影盒菜单，
            //    堵死「继续写孤儿对象」的复制窗口。玩家重开后从真实对象重新构造。
            for (ShulkerBoxItemContainer container : ShulkerBoxStackManager.chainOf(player)) {
                if (!container.tryRelocateForCheck()) {
                    LOGGER.warn("[SB-ALERT] host {} (recorded slot {}) lost beyond repair - force closing shulker menu",
                            container.getShulkerBoxStack().getItem(), container.getSlotIndex());
                    PENDING_REOPEN.remove(player);
                    ShulkerBoxStackManager.setSwitching(player, false);
                    ShulkerBoxStackManager.clearStack(player);
                    player.closeContainer();
                    break; // 已强制关闭，跳过其余链项
                }
            }
        }
    }

    /** 玩家登出时清理其打开栈，避免内存泄漏 */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player instanceof ServerPlayer serverPlayer) {
            PENDING_REOPEN.remove(serverPlayer);
            ShulkerBoxStackManager.clear(serverPlayer);
        }
    }
}
