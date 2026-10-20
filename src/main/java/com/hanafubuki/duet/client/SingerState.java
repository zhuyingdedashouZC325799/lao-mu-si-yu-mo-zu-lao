package com.hanafubuki.duet.client;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * Everything the renderer needs, extracted once per frame on the render
 * thread. Points at no live entity, as required by the 26.x render state
 * pipeline.
 */
public final class SingerState extends LivingEntityRenderState {
    /** Per-entity animation buffers, resolved during extraction. */
    MmdRig rig;
    public boolean singing;
    /**
     * True for the singer that leads the duet, false for the one answering it.
     * The two have different stage business, so this has to reach the animator.
     */
    public boolean leadSinger;
    /** Seconds into the duet, or -1 when this entity is not part of one. */
    public float performanceSeconds = -1.0F;
    public float gaitPhase;
    public float walkWeight;
    public float idleSeconds;
    /**
     * Progress through a kick, {@code 0..1}, or {@code -1} when not kicking.
     *
     * <p>Resolved during extraction from the entity's client-side kick clock,
     * which is driven by a synced sequence number rather than by synced ticks.
     */
    public float kickProgress = -1.0F;
    /** Lip sync weights, already resolved from the audio envelope. */
    public float mouthOpen;
    public float mouthWide;
    public float mouthRound;
    public float blink;
}
