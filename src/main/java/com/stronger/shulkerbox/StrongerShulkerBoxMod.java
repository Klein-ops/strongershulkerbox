package com.stronger.shulkerbox;
import com.stronger.shulkerbox.network.NetworkRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Mod(StrongerShulkerBoxMod.MODID)
public class StrongerShulkerBoxMod {
    public static final String MODID = "strongershulkerbox";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);
    public StrongerShulkerBoxMod(IEventBus modEventBus) {
        LOGGER.info("Stronger Shulker Box loaded. Max nesting depth = {}", ShulkerBoxNesting.MAX_NESTING_DEPTH);
        // 注册网络 payload（客户端右键 → 服务端打开潜影盒菜单）
        NetworkRegistration.register(modEventBus);
    }
}