package com.hanafubuki.duet;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Picks a fight: nearby hostile mobs by sight, and whoever hit us.
 *
 * <p>These singers are neutral, not hostile. They never acquire a player out of
 * the blue - a player only becomes a target by attacking one of them, and that
 * is handled by {@link SingerEntity#provoke}, which also wakes the partner.
 * Hostile mobs are fair game on sight, which is what makes the pair useful to
 * keep around instead of a liability that dies to the first zombie.
 *
 * <p>Written by hand rather than assembled from {@code NearestAttackableTargetGoal}
 * so the two candidate kinds can be ranked in one pass and so a provoker always
 * outranks whatever else is standing nearby.
 */
final class RageTargetGoal extends Goal {

    /** Ticks between scans. A scan walks every entity in the box. */
    private static final int SCAN_INTERVAL = 10;

    private final SingerEntity singer;
    private int cooldown;
    private LivingEntity chosen;

    RageTargetGoal(SingerEntity singer) {
        this.singer = singer;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.singer.isRemoved() || !this.singer.isAlive()) {
            return false;
        }
        if (this.singer.wantsToKeepTarget()) {
            return false;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        this.cooldown = SCAN_INTERVAL;

        // A provoker is remembered for a while after the hit, and is honoured
        // even at long range: the pair chases a player who shot them from a
        // hill. Nothing else is allowed to displace it.
        LivingEntity provoker = this.singer.getProvoker();
        if (this.acceptable(provoker, SingerEntity.AGGRO_LEASH)) {
            this.chosen = provoker;
            return true;
        }

        List<LivingEntity> near = this.singer.level().getEntitiesOfClass(
                LivingEntity.class,
                this.singer.getBoundingBox().inflate(
                        SingerEntity.AGGRO_RANGE, 6.0, SingerEntity.AGGRO_RANGE),
                this::hostile);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity candidate : near) {
            double d = this.singer.distanceToSqr(candidate);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        if (best == null) {
            return false;
        }
        this.chosen = best;
        return true;
    }

    @Override
    public void start() {
        if (this.chosen != null) {
            this.singer.setTarget(this.chosen);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    /** Enemy is the marker interface every hostile mob implements. */
    private boolean hostile(LivingEntity candidate) {
        return candidate instanceof Enemy && this.acceptable(candidate, SingerEntity.AGGRO_RANGE);
    }

    private boolean acceptable(LivingEntity candidate, double range) {
        if (candidate == null || candidate == this.singer) {
            return false;
        }
        if (!candidate.isAlive() || candidate.isRemoved()) {
            return false;
        }
        if (candidate instanceof SingerEntity) {
            return false;
        }
        if (candidate instanceof Player player
                && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        double max = range * range;
        return this.singer.distanceToSqr(candidate) <= max;
    }
}
