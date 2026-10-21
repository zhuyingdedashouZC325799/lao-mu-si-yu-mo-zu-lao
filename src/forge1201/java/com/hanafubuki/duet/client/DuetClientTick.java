package com.hanafubuki.duet.client;

import com.hanafubuki.duet.DuetMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Drives the duet soundtrack once per client tick.
 *
 * <p>Kept apart from {@link DuetClientSetup} because that one listens on the
 * mod bus and this one on the game bus.
 */
@Mod.EventBusSubscriber(modid = DuetMod.ID, value = Dist.CLIENT)
public final class DuetClientTick {

    private DuetClientTick() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DuetClient.tickClient();
        }
    }
}
