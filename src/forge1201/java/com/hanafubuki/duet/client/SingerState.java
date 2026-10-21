package com.hanafubuki.duet.client;

/**
 * Everything the renderer needs, resolved once per rendered frame.
 *
 * <p>1.20.1 has no render-state pipeline, so this is a plain mutable scratch
 * object owned by {@link SingerRenderer} and refilled on the render thread for
 * each entity. It deliberately points at no live entity: the rig reads these
 * numbers only.
 */
public final class SingerState {
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
    /** Ticks lived, interpolated; drives the blink clock. */
    public float ageInTicks;
    /** Body yaw in degrees, interpolated. */
    public float bodyRot;
    /** Head yaw relative to the body, in degrees. */
    public float yRot;
    /** Head pitch in degrees. */
    public float xRot;
    /**
     * Progress through a kick, {@code 0..1}, or {@code -1} when not kicking.
     *
     * <p>Resolved from the entity's client-side kick clock, which is driven by
     * a synced sequence number rather than by synced ticks.
     */
    public float kickProgress = -1.0F;
    /** Lip sync weights, already resolved from the audio envelope. */
    public float mouthOpen;
    public float mouthWide;
    public float mouthRound;
    public float blink;
}
