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
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(NetworkRegistration::registerPayloads);
        // 游戏事件（Close / 登出）发在游戏总线 NeoForge.EVENT_BUS，需要手动注册
        NeoForge.EVENT_BUS.addListener(NetworkRegistration::onPlayerContainerClose);
        NeoForge.EVENT_BUS.addListener(NetworkRegistration::onPlayerLoggedOut);
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
        // 栈为空说明不是我们的潜影盒菜单，忽略
        if (ShulkerBoxStackManager.peek(serverPlayer) == null) {
            return;
        }
        // 弹栈：返回上一层；若弹栈后仍有栈顶容器，则重开它
        ShulkerBoxItemContainer parent = ShulkerBoxStackManager.pop(serverPlayer);
        if (parent != null) {
            ShulkerBoxStackManager.setSwitching(serverPlayer, true);
            try {
                openMenu(serverPlayer, parent);
            } finally {
                ShulkerBoxStackManager.setSwitching(serverPlayer, false);
            }
        }
    }

    /** 玩家登出时清理其打开栈，避免内存泄漏 */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (player instanceof ServerPlayer serverPlayer) {
            ShulkerBoxStackManager.clear(serverPlayer);
        }
    }
}
