package com.hanafubuki.duet;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Closes the distance to the current target and kicks it.
 *
 * <p>Deliberately not a {@code MeleeAttackGoal}: that goal swings the vanilla
 * mob arm and drives the vanilla attack cooldown, neither of which exists on a
 * skinned MMD mesh. The actual hit is resolved by the entity itself, because
 * the damage has to land on the animation frame where the boot is extended, not
 * the frame where the decision to attack was made.
 */
final class KickAttackGoal extends Goal {

    private final SingerEntity singer;

    KickAttackGoal(SingerEntity singer) {
        this.singer = singer;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.valid(this.singer.getTarget());
    }

    @Override
    public boolean canContinueToUse() {
        return this.valid(this.singer.getTarget());
    }

    private boolean valid(LivingEntity target) {
        return target != null
                && target.isAlive()
                && this.singer.isAlive()
                && !this.singer.isRemoved();
    }

    @Override
    public void start() {
        this.singer.setAggressive(true);
    }

    @Override
    public void stop() {
        this.singer.setAggressive(false);
        this.singer.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = this.singer.getTarget();
        if (target == null) {
            return;
        }
        // The gaze is deliberately NOT driven from here. The entity owns the
        // turn: it eases body and head onto the target from its own tick, and
        // writes both after the goals have run so its value is the one that
        // renders. Running the look controller as well gave the body two
        // masters - one snapping 30 degrees a tick, one easing - and whichever
        // wrote last won, so the shoulders were mid-turn when the boot went out
        // and the kick read as being thrown sideways.

        // A kick in progress owns the body: no re-pathing, or the character
        // would slide backwards out of its own animation.
        if (this.singer.isKickRunning()) {
            this.singer.getNavigation().stop();
            return;
        }

        if (this.singer.distanceToSqr(target) <= SingerEntity.KICK_REACH_SQ) {
            this.singer.getNavigation().stop();
            if (this.singer.canStartKick()) {
                this.singer.beginKick(target);
            }
            return;
        }

        if (this.singer.tickCount % 6 == 0 || this.singer.getNavigation().isDone()) {
            this.singer.getNavigation().moveTo(target, SingerEntity.CHASE_SPEED);
        }
    }
}
