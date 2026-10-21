package com.hanafubuki.duet.client;

import com.hanafubuki.duet.DuetMod;
import com.hanafubuki.duet.SingerEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client glue: registers renderers and keeps exactly one duet track alive for
 * the nearest singing pair.
 *
 * <p>Two different buses are used here, and getting them the wrong way round
 * fails silently. {@code EntityRenderersEvent} is a mod-bus event, so the
 * {@link EventBusSubscriber} annotation routes it for us.
 * {@code ClientTickEvent} is a game-bus event, which the annotation never
 * subscribes to; it is attached to {@link NeoForge#EVENT_BUS} explicitly in
 * {@link #hook()}.
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = DuetMod.ID)
public final class DuetClient {

    private static final boolean DEBUG = System.getProperty("mmdduet.probe") != null;

    private static DuetClient instance;
    private static boolean hooked;

    private DuetSound active;
    private ClientLevel lastLevel;
    private int retryDelay;

    /**
     * Ticks to wait before a stopped track may be rebuilt.
     *
     * <p>Without it a pair whose synced flags disagree for a moment rebuilt the
     * sound on the very next client tick, so the track restarted twenty times a
     * second. The audible result was not a gap but a buzz, and it was the last
     * thing standing between the duet and sounding continuous.
     */
    private int restartHold;

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        instance = new DuetClient();
        hook();
        event.registerEntityRenderer(DuetMod.SI.get(), context -> new SingerRenderer(context, "si"));
        event.registerEntityRenderer(DuetMod.LIINO.get(), context -> new SingerRenderer(context, "liino"));
    }

    /**
     * Attaches the game-bus tick listener exactly once.
     *
     * <p>Called from the renderer registration event, which runs after the
     * registries are populated - so {@code get()} on the deferred entity types
     * is safe at that point, while it would not be in the mod constructor.
     */
    private static void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.addListener(DuetClient::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        DuetClient self = instance;
        if (self != null) {
            self.tick(Minecraft.getInstance());
        }
    }

    /** Seconds into the duet for this entity, or -1 when it is not singing. */
    public static float performanceSeconds(SingerEntity entity, float partialTick) {
        DuetClient self = instance;
        if (self == null || self.active == null || !self.active.includes(entity)) {
            return -1.0F;
        }
        return self.active.seconds(partialTick);
    }

    private void stop(Minecraft minecraft) {
        if (this.active != null) {
            this.active.finish();
            minecraft.getSoundManager().stop(this.active);
            this.active = null;
        }
        this.retryDelay = 0;
        this.restartHold = 10;
    }

    private void tick(Minecraft minecraft) {
        if (this.lastLevel != minecraft.level) {
            stop(minecraft);
            this.lastLevel = minecraft.level;
        }
        if (minecraft.level == null || minecraft.player == null) {
            stop(minecraft);
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }

        if (this.active != null) {
            if (!this.active.valid(minecraft)) {
                stop(minecraft);
            } else if (minecraft.getSoundManager().isActive(this.active)) {
                this.retryDelay = 0;
                return;
            } else if (++this.retryDelay < 40) {
                return;
            } else {
                stop(minecraft);
            }
        }

        if (this.restartHold > 0) {
            this.restartHold--;
            return;
        }

        if (minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F
                || minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F) {
            return;
        }

        List<SingerEntity> near = minecraft.level.getEntitiesOfClass(
                SingerEntity.class,
                minecraft.player.getBoundingBox().inflate(40.0),
                entity -> entity.isAlive()
                        // Only a pair that is actually performing starts a
                        // track. Without this the stopped song was restarted on
                        // the very next tick of a fight: the search found the
                        // same two entities standing side by side, saw a valid
                        // pair and began the vocals again, so combat only ever
                        // produced a one tick gap in the audio.
                        && entity.isSinging()
                        && entity.distanceToSqr(minecraft.player) <= 1600.0);
        near.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(minecraft.player)));

        for (SingerEntity entity : near) {
            SingerEntity partner = entity.findPartner();
            // Both halves have to agree. The flags are synced per entity, and a
            // single tick of disagreement is enough to build a track that the
            // very next validation throws away again, which is what produced
            // the restart buzz rather than a song.
            if (partner != null && partner.isSinging()) {
                this.active = new DuetSound(entity, partner);
                minecraft.getSoundManager().play(this.active);
                if (DEBUG) {
                    System.out.println("[mmd] duet start " + entity.variant() + " + "
                            + partner.variant() + " at " + entity.blockPosition()
                            + " dist=" + String.format("%.2f", Math.sqrt(entity.distanceToSqr(partner))));
                }
                return;
            }
        }
    }
}
