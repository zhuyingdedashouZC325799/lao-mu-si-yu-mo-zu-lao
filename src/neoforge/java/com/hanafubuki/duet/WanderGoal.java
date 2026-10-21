package com.hanafubuki.duet;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Slow roaming for a singer that has no partner in range.
 *
 * <p>The vanilla stroll goal only fires on a one-in-120 dice roll that is
 * further throttled by a movement-speed factor, so a lone singer can stand
 * rooted for tens of seconds and looks frozen. This goal instead keeps a
 * simple rhythm of its own:
 *
 * <ol>
 *   <li>pick a random reachable spot three to nine blocks away,</li>
 *   <li>walk there at a deliberately unhurried pace,</li>
 *   <li>stand and look around for a moment,</li>
 *   <li>repeat.</li>
 * </ol>
 *
 * <p>Candidates are validated against the world before they are used: the
 * target must stand on solid, dry ground with room for the whole body. That
 * keeps the walk from ending in a lake, inside a wall or over a cliff, and a
 * short stuck timer recovers the few cases where the pathfinder still refuses.
 *
 * <p>The goal yields the movement flag the instant a partner appears, so the
 * duet approach in {@link SingerEntity} always takes over cleanly.
 */
final class WanderGoal extends Goal {

    /**
     * Walk speed as a fraction of the entity's own movement speed.
     *
     * <p>The step cadence is locked to real displacement, so this number sets
     * how quickly the arms and legs swing. At the previous value the cycle took
     * about four seconds, which read as slow motion; this is the same walk
     * roughly one and a half times faster. The unhurried feel comes from the
     * frequent pauses, not from crawling.
     */
    private static final double SPEED = 0.80;

    private static final int NEAR_RADIUS = 4;
    private static final int FAR_RADIUS = 9;

    /**
     * Length of the standing beat between two walks, in ticks.
     *
     * <p>The first version paused for two to five seconds, and a continuous
     * recording of the singer showed it moving only a quarter of the time,
     * which read as a statue that occasionally twitches. A short breath of
     * roughly one to two seconds is enough to keep the wandering look unhurried
     * while the singer is visibly going somewhere.
     */
    private static final int MIN_PAUSE = 16;
    private static final int MAX_PAUSE = 40;
    private static final int STUCK_LIMIT = 80;
    private static final int TRIES = 16;

    private final SingerEntity singer;

    /** Ticks left before the next walk is chosen. */
    private int pause;

    /** Ticks spent walking without making progress. */
    private int stuck;

    private boolean walking;
    private double progressX;
    private double progressZ;

    WanderGoal(SingerEntity singer) {
        this.singer = singer;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
        this.pause = MIN_PAUSE;
    }

    @Override
    public boolean canUse() {
        return this.available();
    }

    @Override
    public boolean canContinueToUse() {
        return this.available();
    }

    private boolean available() {
        return !this.singer.isSinging()
                // Reuniting after a fight takes the navigator too. Without
                // this the two goals overwrite each other's path every few
                // ticks and the pair never closes back up to sing again.
                && !this.singer.wantsReunion()
                && !this.singer.isPassenger()
                && this.singer.isAlive();
    }

    @Override
    public void start() {
        this.walking = false;
        this.stuck = 0;
        this.pause = this.nextPause();
    }

    @Override
    public void stop() {
        this.walking = false;
        // Handing over to the duet approach must leave its freshly built path
        // alone, so the stop is skipped once a partner owns the movement.
        if (!this.singer.isSinging()) {
            this.singer.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        if (this.walking) {
            this.tickWalk();
            return;
        }
        if (this.pause > 0) {
            this.pause--;
            return;
        }
        if (!this.chooseTarget()) {
            this.pause = MIN_PAUSE;
        }
    }

    private void tickWalk() {
        double dx = this.singer.getX() - this.progressX;
        double dz = this.singer.getZ() - this.progressZ;
        if (dx * dx + dz * dz > 0.0025) {
            this.progressX = this.singer.getX();
            this.progressZ = this.singer.getZ();
            this.stuck = 0;
        } else if (++this.stuck > STUCK_LIMIT) {
            this.finish();
            return;
        }
        if (this.singer.getNavigation().isDone()) {
            this.finish();
        }
    }

    private void finish() {
        this.walking = false;
        this.stuck = 0;
        this.pause = this.nextPause();
        this.singer.getNavigation().stop();
    }

    /**
     * Chooses one standable spot and starts walking to it.
     *
     * <p>The first pass looks over the full roam radius; if the singer is
     * boxed in, a second pass falls back to a tight radius so it still shuffles
     * around instead of locking up.
     */
    private boolean chooseTarget() {
        RandomSource random = this.singer.getRandom();
        Level level = this.singer.level();
        for (int pass = 0; pass < 2; pass++) {
            int min = pass == 0 ? 3 : 1;
            int max = pass == 0 ? FAR_RADIUS : NEAR_RADIUS;
            for (int attempt = 0; attempt < TRIES; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radius = min + random.nextDouble() * (max - min);
                double x = this.singer.getX() + Math.cos(angle) * radius;
                double z = this.singer.getZ() + Math.sin(angle) * radius;
                BlockPos seed = BlockPos.containing(x, this.singer.getY(), z);
                for (int dy = 2; dy >= -4; dy--) {
                    BlockPos feet = seed.offset(0, dy, 0);
                    if (!this.standable(level, feet)) {
                        continue;
                    }
                    this.progressX = this.singer.getX();
                    this.progressZ = this.singer.getZ();
                    this.stuck = 0;
                    this.walking = true;
                    this.singer.getNavigation().moveTo(
                            feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, SPEED);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean standable(Level level, BlockPos feet) {
        BlockState ground = level.getBlockState(feet.below());
        if (!ground.blocksMotion() || !ground.getFluidState().isEmpty()) {
            return false;
        }
        BlockState body = level.getBlockState(feet);
        if (body.blocksMotion() || !body.getFluidState().isEmpty()) {
            return false;
        }
        BlockState head = level.getBlockState(feet.above());
        return !head.blocksMotion() && head.getFluidState().isEmpty();
    }

    private int nextPause() {
        RandomSource random = this.singer.getRandom();
        return MIN_PAUSE + random.nextInt(MAX_PAUSE - MIN_PAUSE + 1);
    }
}
