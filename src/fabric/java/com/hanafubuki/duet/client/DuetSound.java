package com.hanafubuki.duet.client;

import com.hanafubuki.duet.DuetMod;
import com.hanafubuki.duet.SingerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/**
 * The looping duet track, anchored between the two singers.
 *
 * <p>Its elapsed tick counter is the master clock for lip sync, so picture and
 * audio stay locked even if the frame rate moves.
 */
public final class DuetSound extends AbstractTickableSoundInstance {

    private static final boolean DEBUG = System.getProperty("mmdduet.probe") != null;

    private final SingerEntity first;
    private final SingerEntity second;
    private int elapsedTicks;

    /**
     * Last validity verdict, so the probe prints a verdict only when it
     * changes.
     *
     * <p>Diagnostic only. A sound that restarts every tick is silent evidence -
     * the track rewinds to the first fraction of a second and buzzes - but the
     * log said nothing about which condition was failing, so this records the
     * first failing test as a number.
     */
    private int lastVerdict = -1;

    public DuetSound(SingerEntity first, SingerEntity second) {
        super(DuetMod.DUET, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.first = first;
        this.second = second;
        this.looping = true;
        this.delay = 0;
        this.volume = 1.0F;
        this.pitch = 1.0F;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.updatePosition();
    }

    public boolean valid(Minecraft minecraft) {
        // Ordered so the first failing test is the one reported.
        int verdict;
        if (minecraft.level != this.first.level()) {
            verdict = 1;
        } else if (minecraft.player == null) {
            verdict = 2;
        } else if (!this.first.isSinging()) {
            verdict = 3;
        } else if (!this.second.isSinging()) {
            verdict = 4;
        } else if (!this.first.canDuetWith(this.second)) {
            verdict = 5;
        } else if (!this.second.canDuetWith(this.first)) {
            verdict = 6;
        } else if (this.first.distanceToSqr(minecraft.player) > 1600.0) {
            verdict = 7;
        } else {
            verdict = 0;
        }
        if (DEBUG && verdict != this.lastVerdict) {
            System.out.println("[mmd] duet valid " + this.first.variant()
                    + "+" + this.second.variant() + " verdict=" + verdict
                    + " gap=" + String.format("%.2f", Math.sqrt(this.first.distanceToSqr(this.second)))
                    + " toPlayer=" + String.format("%.2f", Math.sqrt(this.first.distanceToSqr(minecraft.player))));
        }
        this.lastVerdict = verdict;
        return verdict == 0;
    }

    private void updatePosition() {
        this.x = (this.first.getX() + this.second.getX()) * 0.5;
        this.y = (this.first.getY() + this.second.getY()) * 0.5 + 1.15;
        this.z = (this.first.getZ() + this.second.getZ()) * 0.5;
    }

    @Override
    public void tick() {
        if (!this.valid(Minecraft.getInstance())) {
            stop();
        } else {
            this.elapsedTicks++;
            this.updatePosition();
        }
    }

    public boolean includes(SingerEntity entity) {
        return entity == this.first || entity == this.second;
    }

    /** Seconds into the track, with sub-tick interpolation. */
    public float seconds(float partialTick) {
        return (this.elapsedTicks + partialTick) / 20.0F;
    }

    public void finish() {
        stop();
    }
}
