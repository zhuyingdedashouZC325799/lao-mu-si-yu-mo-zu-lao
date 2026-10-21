package com.hanafubuki.duet;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One half of the duet pair.
 *
 * <p>The two variants share a single class; {@link #variant()} selects which
 * baked mesh is drawn. A pair is any two living singers of different variants
 * within {@link #PAIR_RANGE} blocks, at which point both stop walking, face
 * each other and the duet audio starts on the client.
 */
public class SingerEntity extends PathfinderMob {

    private static final EntityDataAccessor<Boolean> SINGING =
            SynchedEntityData.defineId(SingerEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * Counter bumped once per kick, synced to the client.
     *
     * <p>The animation clock deliberately lives on the client and is restarted
     * by watching this number change, instead of syncing the elapsed ticks
     * themselves. Entity data arrives in coarse steps, so a synced tick counter
     * would make a twelve tick kick visibly stutter; a change-triggered local
     * clock is perfectly smooth and needs one byte per kick on the wire.
     */
    private static final EntityDataAccessor<Integer> KICK_SEQ =
            SynchedEntityData.defineId(SingerEntity.class, EntityDataSerializers.INT);

    /**
     * True while this singer has a live target.
     *
     * <p>The client cannot work this out for itself: the attack target is not
     * part of vanilla's synced entity data, so {@code getTarget()} is always
     * null on the client. Without this flag the client ran its duet gaze while
     * the server ran the fight, and the two disagreed about which way the body
     * pointed - the drawn body turned toward the partner while the boot flew at
     * whoever was attacking, which reads as a kick thrown sideways.
     */
    private static final EntityDataAccessor<Boolean> COMBAT =
            SynchedEntityData.defineId(SingerEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * Bearing to the current target in degrees, synced so the client can turn
     * smoothly instead of waiting for a rotation packet.
     *
     * <p>Sent as an angle rather than as the target's position because the
     * client may not have the target entity loaded at all, and an angle is
     * meaningful on its own.
     */
    private static final EntityDataAccessor<Float> AIM_YAW =
            SynchedEntityData.defineId(SingerEntity.class, EntityDataSerializers.FLOAT);

    /** Blocks within which a partner counts. */
    public static final float PAIR_RANGE = 8.0F;
    private static final float PAIR_RANGE_SQ = PAIR_RANGE * PAIR_RANGE;

    /** Development-only state trace, enabled with {@code -Dmmdduet.probe}. */
    private static final boolean PROBE = System.getProperty("mmdduet.probe") != null;

    /**
     * Gap the pair settles into before the duet starts.
     *
     * <p>They walk toward each other until they are this far apart and then
     * hold still, so they always end up facing one another at a distance that
     * frames both of them instead of clipping together.
     */
    public static final float DUET_DISTANCE = 2.0F;

    /** Fraction of the remaining body yaw closed per tick while facing. */
    private static final float TURN_RATE = 0.22F;

    /** Fraction of the remaining head yaw closed per tick. */
    private static final float HEAD_RATE = 0.40F;

    /**
     * Body turn rate while fighting.
     *
     * <p>The duet rate is deliberately unhurried so two singers swing round to
     * each other like performers. In a fight the same rate means the boot has
     * already landed before the shoulders have finished coming round, which is
     * what made a straight kick look like it was thrown off to one side.
     */
    private static final float COMBAT_TURN_RATE = 0.60F;

    /** Head turn rate while fighting; the head leads the shoulders. */
    private static final float COMBAT_HEAD_RATE = 0.75F;

    /** Walk speed modifier used while closing the distance. */
    private static final double APPROACH_SPEED = 0.80;

    /**
     * Radians of walk cycle per block travelled.
     *
     * <p>Matched to the stride the animator draws, so the planted foot stays put
     * on the ground instead of skating: one cycle covers two steps, and at the
     * current swing amplitude those two steps add up to roughly 1.85 blocks.
     */
    /**
     * Gait phase radians per block travelled.
     *
     * <p>Derived from the stride, not guessed. The solved skeleton moves one
     * foot through 9.28 model units per step, which is 0.767 blocks, so one
     * half cycle has to cover that much ground and the phase per block is
     * {@code PI / 0.767 = 4.10}. Change {@code Animator.STRIDE} and this number
     * must follow, or the planted foot slides against the ground.
     */
    private static final float PHASE_PER_BLOCK = 4.10F;

    /**
     * Health pool of the shrine maiden.
     *
     * <p>Both numbers sit above the vanilla {@code max_health} range of 1024,
     * which would clamp them silently. {@link HealthCeiling} widens that range
     * at startup, so the attribute itself carries the real value: the health
     * bar, the damage maths and every mod that reads the attribute (Jade) all
     * agree, and the value survives a reload.
     */
    public static final double SI_MAX_HEALTH = 1325.0;

    /** Health pool of the smaller singer. */
    public static final double LIINO_MAX_HEALTH = 325.0;

    // ------------------------------------------------------------------
    // Combat
    // ------------------------------------------------------------------

    /**
     * Damage one kick deals to its victim, in health points.
     *
     * <p>Half a heart is two points, so this is slightly over one and a half
     * hearts per kick. It is dealt as true damage through
     * {@link DuetDamage#kick}: armour, Protection, Resistance, Absorption and a
     * raised shield are all bypassed, so the target loses exactly this much.
     */
    public static final float KICK_DAMAGE = 3.25F;

    /** Ticks one kick lasts, from chamber to recovery. */
    public static final int KICK_LENGTH = 16;

    /**
     * Ticks from the start of the kick to the boot connecting.
     *
     * <p>Placed on the frame where the leg has finished extending, so the hit
     * and the stretched boot are the same picture.
     */
    public static final int KICK_IMPACT = 9;

    /** Ticks between kicks. The user asked for one attack per second. */
    public static final int KICK_INTERVAL = 20;

    /**
     * Horizontal shove a landed kick applies, in blocks per tick.
     *
     * <p>Fixed rather than inherited from the vanilla hurt knockback: that one
     * varies with whatever else hit the victim in the same tick and stacked on
     * top of the shove, which is how a standing player kept ending up off the
     * edge of a platform. At 0.46 a hit pushes roughly two blocks, which reads
     * clearly as "I got kicked" without turning the fight into a shoving match.
     */
    private static final double KICK_KNOCKBACK = 0.46;

    /**
     * Upward component of the same shove.
     *
     * <p>Enough to lift the victim off its feet for a couple of ticks so the hit
     * has weight; deliberately well under the 0.42 a vanilla knockback uses for
     * its hop, because that hop is what clears a one block railing.
     */
    private static final double KICK_KNOCKBACK_LIFT = 0.16;

    /** Reach of a kick, in blocks, measured centre to centre. */
    public static final float KICK_REACH = 2.40F;
    public static final float KICK_REACH_SQ = KICK_REACH * KICK_REACH;

    /** Walk speed modifier while closing on a target. Deliberately quick. */
    public static final double CHASE_SPEED = 1.30;

    /** Blocks within which a hostile mob is noticed on sight. */
    public static final float AGGRO_RANGE = 16.0F;

    /**
     * Blocks a remembered provoker is still chased over.
     *
     * <p>Deliberately generous. A provoked singer is meant to finish the fight,
     * and one that gives up the moment the player backs off a few blocks turns
     * every hit into a hit and run: the player steps out of the leash, the
     * grudge lapses, the pair starts singing again, and the next arrow repeats
     * the whole cycle.
     */
    public static final float AGGRO_LEASH = 48.0F;

    /** Blocks within which a provoked singer wakes its partner. */
    public static final float ALERT_RANGE = 16.0F;

    /** Ticks a grudge lasts after the last hit taken. */
    private static final int GRUDGE_TICKS = 400;

    /** Ticks of silence between the end of a fight and the song resuming. */
    private static final int CHANT_HOLD_TICKS = 12;

    /**
     * Blocks within which a singer looks for its partner again after a fight.
     *
     * <p>Wider than {@link #PAIR_RANGE} because combat itself is what separates
     * the pair: one of them chases a player across the arena while the other
     * holds its ground. The duet only takes over inside the duet range, so
     * without a wider search a pair that fought side by side could finish the
     * fight eight and a half blocks apart and never sing again.
     */
    private static final float REUNION_RANGE = 24.0F;

    /**
     * Ticks after a fight during which the pair walks back together.
     *
     * <p>Time limited on purpose. Two singers that simply spawned far apart are
     * supposed to drift around on their own - that is the behaviour the pair
     * already had - so the reunion is only armed by the end of a fight, not by
     * being alone.
     */
    private static final int REUNION_TICKS = 400;

    /** Server-side kick countdown; {@code 0} when idle. */
    private int kickTick;
    private boolean kickLanded;
    private int kickCooldown;

    /** Client-side animation clock, restarted when {@link #KICK_SEQ} changes. */
    private int clientKickTick;
    private int lastKickSeq;

    /** Whoever last hurt this singer, hopped up on adrenaline until it fades. */
    private LivingEntity provoker;
    private int grudgeTicks;

    /**
     * Partner to walk back to once the fight is over, and how long that urge
     * lasts. Server side only; the client needs neither.
     */
    private SingerEntity reunion;
    private int reunionTicks;

    /** Combat state as of the last server tick, used to spot the end of one. */
    private boolean wasCombat;

    /**
     * Ticks the pair waits after a fight before the song picks up again.
     *
     * <p>Long enough to read as "they stopped to deal with that, now they go
     * back to it", and long enough that a target flickering on and off for a
     * couple of ticks cannot chop the track into fragments.
     */
    private int chantHold;

    private float gaitPhase;
    private float gaitPhaseOld;
    private float walkWeight;
    private float walkWeightOld;
    private double lastX;
    private double lastZ;
    private boolean measured;

    /** Partner resolved this tick, on either side; null when alone. */
    private SingerEntity partner;

    /** Slow roaming, active only while no partner is in range. */
    private WanderGoal wander;

    public SingerEntity(EntityType<? extends SingerEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCustomName(this.getType().getDescription());
        this.setCustomNameVisible(true);
        this.lastX = this.getX();
        this.lastZ = this.getZ();
    }

    /**
     * The duet partners are named characters, so their name plate stays on.
     *
     * Vanilla re-reads {@code CustomNameVisible} from entity NBT while the
     * entity is being placed, which silently flipped the flag back to false
     * after every summon and left the label invisible unless the crosshair was
     * pointed at the character.  Answering the query directly is immune to
     * that.
     */
    @Override
    public boolean shouldShowName() {
        return true;
    }

    /** Attribute set for one variant, with its own health pool. */
    public static AttributeSupplier.Builder attributes(double maxHealth) {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, maxHealth)
                .add(Attributes.MOVEMENT_SPEED, 0.16)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SINGING, false);
        builder.define(KICK_SEQ, 0);
        builder.define(COMBAT, false);
        builder.define(AIM_YAW, 0.0F);
    }

    /**
     * These two give ground instead of body-checking.
     *
     * <p>Vanilla collision resolution shoves every pushable entity that is
     * overlapped, and a player is pushable. A pair that crowds the player to
     * kick it therefore kept nudging it away, one bump at a time, until a
     * standing player ended up in the void without ever being knocked back by an
     * attack.
     *
     * <p>Simply doing nothing is not enough: with no push at all the pair walks
     * straight into the player's space and ends up inside the camera. So when
     * the overlapped entity is a player the shove is turned around - {@code
     * push(player)} moves <em>this</em> singer away from the player - which is
     * the same thing a boat does when a mob leans on it. The player keeps its
     * footing, the singer keeps its distance, and the two never interpenetrate.
     * Every other entity is still pushed the normal way, so the pair cannot
     * stand inside each other or inside other mobs either.
     */
    @Override
    protected void doPush(Entity entity) {
        if (entity instanceof Player player && !player.isSpectator()) {
            this.push(player);
            return;
        }
        super.doPush(entity);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Holds the movement flag while a duet is running. It deliberately does
        // not touch the navigator on every tick: the pair still has to be able
        // to walk toward each other, and stop-thrashing the path made the
        // approach crawl.
        this.goalSelector.addGoal(1, new Goal() {
            {
                this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
            }

            @Override
            public boolean canUse() {
                return SingerEntity.this.isSinging()
                        && SingerEntity.this.getTarget() == null;
            }

            @Override
            public boolean canContinueToUse() {
                return SingerEntity.this.isSinging()
                        && SingerEntity.this.getTarget() == null;
            }

            @Override
            public void tick() {
                if (SingerEntity.this.duetGap() <= DUET_DISTANCE) {
                    SingerEntity.this.getNavigation().stop();
                }
            }
        });

        // A lone singer keeps drifting around instead of standing like a
        // statue; it stops the moment a partner comes into range.
        // Priority 2 is combat: a singer that has picked a target must not also
        // be trying to stroll to a random spot, so the kick goal outranks the
        // wander and takes the movement flag away from it.
        this.goalSelector.addGoal(2, new KickAttackGoal(this));

        this.wander = new WanderGoal(this);
        this.goalSelector.addGoal(3, this.wander);

        // No LookAtPlayerGoal on purpose. These are performers, not pets: a
        // singer must look at its partner, never swivel its head onto whoever
        // happens to be walking past. An idle head wander is still allowed so a
        // lone singer does not freeze like a statue, but it is disabled the
        // moment a partner is found, because from then on the gaze is owned by
        // facePartner().
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this) {
            @Override
            public boolean canUse() {
                // Not while walking: the gaze belongs to the path then, so the
                // head turns where the body is going rather than snapping away.
                return !SingerEntity.this.isSinging()
                        && SingerEntity.this.getTarget() == null
                        && !SingerEntity.this.getNavigation().isInProgress()
                        && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !SingerEntity.this.isSinging()
                        && SingerEntity.this.getTarget() == null
                        && !SingerEntity.this.getNavigation().isInProgress()
                        && super.canContinueToUse();
            }
        });

        this.targetSelector.addGoal(1, new RageTargetGoal(this));
    }

    /** True when a partner is close enough to sing with. */
    public boolean isSinging() {
        return this.entityData.get(SINGING);
    }

    /** True while this singer is fighting, as synced to the client. */
    public boolean isInCombat() {
        return this.entityData.get(COMBAT);
    }

    /** Synced bearing to the current target, in game yaw degrees. */
    public float aimYaw() {
        return this.entityData.get(AIM_YAW);
    }

    /** {@code "si"} or {@code "liino"}; selects the mesh and the sound set. */
    public String variant() {
        // The types are DeferredHolders on NeoForge, so the comparison has to
        // unwrap the holder once the register phase has finished. variant() is
        // only ever called from the entity itself, which cannot exist before
        // that, so get() is safe here.
        return this.getType() == DuetMod.LIINO.get() ? "liino" : "si";
    }

    public float gaitPhase(float partialTick) {
        return lerp(this.gaitPhaseOld, this.gaitPhase, clamp01(partialTick));
    }

    public float walkWeight(float partialTick) {
        return lerp(this.walkWeightOld, this.walkWeight, clamp01(partialTick));
    }

    // ------------------------------------------------------------------
    // Combat
    // ------------------------------------------------------------------

    /** True while a kick is mid-animation on the server. */
    boolean isKickRunning() {
        return this.kickTick > 0;
    }

    /** True when the attack cooldown has come round again. */
    boolean canStartKick() {
        return this.kickTick <= 0 && this.kickCooldown <= 0;
    }

    /**
     * Progress through the kick animation, {@code 0..1}, or {@code -1} when the
     * character is not kicking.
     *
     * <p>Driven by the client-side clock so the boot moves on every rendered
     * frame rather than in the steps that entity data arrives in.
     */
    public float kickProgress(float partialTick) {
        if (this.clientKickTick <= 0) {
            return -1.0F;
        }
        float elapsed = (KICK_LENGTH - this.clientKickTick) + clamp01(partialTick);
        return clamp01(elapsed / (float) KICK_LENGTH);
    }

    /** Keeps the current target instead of scanning for another one. */
    boolean wantsToKeepTarget() {
        LivingEntity current = this.getTarget();
        return current != null && current.isAlive() && !current.isRemoved();
    }

    /** The entity that last hurt this singer, or null once the grudge fades. */
    LivingEntity getProvoker() {
        return this.grudgeTicks > 0 ? this.provoker : null;
    }

    /**
     * Reacts to being hurt.
     *
     * <p>Handled here rather than through {@code HurtByTargetGoal} because the
     * pair has to react together, and the vanilla goal only alerts mobs of the
     * same entity type - 祀 and 梨诺 are two different types, so neither would
     * ever wake the other. Anything that damages a singer therefore becomes its
     * target, and every singer within {@link #ALERT_RANGE} gets the same target
     * one tick later, which is what produces the two-on-one answer.
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) {
            if (source.getEntity() instanceof LivingEntity attacker
                    && attacker != this
                    && !(attacker instanceof SingerEntity)) {
                this.provoke(attacker);
                this.alertPartners(attacker);
            }
        }
        return hurt;
    }

    /** Records a grudge and turns on the attacker immediately. */
    void provoke(LivingEntity attacker) {
        if (attacker == null || !attacker.isAlive()) {
            return;
        }
        this.provoker = attacker;
        this.grudgeTicks = GRUDGE_TICKS;
        if (!this.level().isClientSide()) {
            this.setTarget(attacker);
        }
    }

    private void alertPartners(LivingEntity attacker) {
        List<SingerEntity> allies = this.level().getEntitiesOfClass(
                SingerEntity.class,
                this.getBoundingBox().inflate(ALERT_RANGE, 8.0, ALERT_RANGE),
                other -> other != this && other.isAlive());
        for (SingerEntity ally : allies) {
            // Same attacker, same grudge: both of them square up and kick.
            ally.provoker = attacker;
            ally.grudgeTicks = GRUDGE_TICKS;
            ally.setTarget(attacker);
        }
    }

    /**
     * Starts a kick.
     *
     * <p>Only the timing is decided here; the pose is authored in the animator
     * from the synced counter, and the damage is dealt by {@link #tickKick()}
     * once the leg has actually extended.
     */
    void beginKick(LivingEntity target) {
        if (!this.canStartKick() || this.level().isClientSide()) {
            return;
        }
        this.kickTick = KICK_LENGTH;
        this.kickLanded = false;
        this.kickCooldown = KICK_INTERVAL;
        this.entityData.set(KICK_SEQ, this.entityData.get(KICK_SEQ) + 1);
        this.getNavigation().stop();
        this.faceTowards(target);
    }

    /** Advances the kick and lands the hit on the impact frame. */
    private void tickKick() {
        if (this.kickCooldown > 0) {
            this.kickCooldown--;
        }
        if (this.kickTick <= 0) {
            return;
        }
        this.kickTick--;
        int elapsed = KICK_LENGTH - this.kickTick;
        if (!this.kickLanded && elapsed >= KICK_IMPACT) {
            this.kickLanded = true;
            this.landKick();
        }
    }

    /**
     * Deals the true damage and shoves the victim back.
     *
     * <p>Reach is re-checked here rather than trusted from when the kick
     * started: a target that walked away during the wind-up must not be hit by a
     * boot that is nowhere near it. The generous factor allows for the victim
     * being knocked back by its own attack in the meantime.
     */
    private void landKick() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive() || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.distanceToSqr(target) > KICK_REACH_SQ * 1.8) {
            return;
        }
        if (target instanceof Player player
                && (player.isCreative() || player.isSpectator())) {
            return;
        }

        // The vanilla hurt-immunity window is exactly what makes rapid hits feel
        // like they are not landing. Clearing it lets the one second attack
        // interval actually deliver every kick, while the interval itself keeps
        // the rate fair.
        // The hurt pipeline adds a knockback of its own, and its size depends on
        // whatever else touched the victim in the meantime. Capture the momentum
        // the target already had, take the hit, then overwrite the result with
        // the momentum plus one fixed shove. That way the knockback is the same
        // every single kick, it never stacks with a vanilla impulse, and only
        // this one number decides how far the victim travels.
        Vec3 momentum = target.getDeltaMovement();
        target.invulnerableTime = 0;
        target.hurtServer(level, DuetDamage.kick(level, this), KICK_DAMAGE);

        // Dead targets keep their fall, so the corpse is not frozen in the air.
        if (!target.isAlive()) {
            return;
        }

        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) {
            // Exactly on top of each other: shove along the attacker's facing
            // instead, which is the direction the boot was travelling anyway.
            double rad = Math.toRadians(this.getYRot());
            dx = -Math.sin(rad);
            dz = Math.cos(rad);
            len = 1.0;
        }
        target.setDeltaMovement(
                momentum.x + dx / len * KICK_KNOCKBACK,
                Math.max(momentum.y, KICK_KNOCKBACK_LIFT),
                momentum.z + dz / len * KICK_KNOCKBACK);
        target.hurtMarked = true;
    }

    public boolean canDuetWith(SingerEntity other) {
        return other != null
                && other != this
                && this.isAlive()
                && other.isAlive()
                && !this.isRemoved()
                && !other.isRemoved()
                && this.level() == other.level()
                && !this.variant().equals(other.variant())
                && this.distanceToSqr(other) <= PAIR_RANGE_SQ;
    }

    /** Closest valid partner, or null. */
    public SingerEntity findPartner() {
        List<SingerEntity> near = this.level().getEntitiesOfClass(
                SingerEntity.class,
                this.getBoundingBox().inflate(PAIR_RANGE, 3.0, PAIR_RANGE),
                this::canDuetWith);
        return near.stream()
                .min(Comparator.comparingDouble(this::distanceToSqr))
                .orElse(null);
    }

    /**
     * Distance to the current partner, or {@link Float#MAX_VALUE} when alone.
     *
     * <p>Callers only ever compare it against {@link #DUET_DISTANCE}, so the
     * lone case deliberately returns something that can never look close.
     */
    float duetGap() {
        if (this.partner == null) {
            return Float.MAX_VALUE;
        }
        return (float) Math.sqrt(this.distanceToSqr(this.partner));
    }

    @Override
    public void tick() {
        this.gaitPhaseOld = this.gaitPhase;
        this.walkWeightOld = this.walkWeight;

        // Combat clocks. The kick counter and the attack cooldown only ever
        // advance on the logical server; the client instead watches the synced
        // sequence number and runs its own animation clock from it, which is
        // what keeps the boot moving on every frame.
        if (this.level().isClientSide()) {
            int seq = this.entityData.get(KICK_SEQ);
            if (seq != this.lastKickSeq) {
                this.lastKickSeq = seq;
                this.clientKickTick = KICK_LENGTH;
            } else if (this.clientKickTick > 0) {
                this.clientKickTick--;
            }
        } else {
            this.tickKick();
            if (this.grudgeTicks > 0) {
                this.grudgeTicks--;
            }
            if (this.reunionTicks > 0) {
                this.reunionTicks--;
            }
            this.maintainTarget();
        }

        // The partner is resolved on both sides so the client can steer the
        // gaze as well: a server-only look would arrive one round trip late and
        // the heads would visibly lag behind the bodies.
        this.partner = this.findPartner();

        boolean combat = this.getTarget() != null;
        if (!this.level().isClientSide()) {
            // A fight suspends the duet outright. Both halves of that matter:
            // the pair must not sing while trading kicks, and they must not
            // walk toward each other either. Leaving the approach running had
            // two steering systems pulling in opposite directions at once, so
            // a provoked pair shuffled between its partner and its victim and
            // never settled on either.
            if (combat) {
                this.chantHold = CHANT_HOLD_TICKS;
            } else if (this.chantHold > 0) {
                this.chantHold--;
            }
            this.entityData.set(SINGING,
                    this.partner != null && !combat && this.chantHold <= 0);
            this.entityData.set(COMBAT, combat);

            if (PROBE && this.tickCount % 20 == 0) {
                // Server-side truth: the client can only see the synced flags,
                // so when the pair disagreed about who was singing this is the
                // side that has to explain why.
                String mate = this.partner == null ? "-" : this.partner.variant();
                String gap = this.partner == null ? "-"
                        : String.format("%.2f", Math.sqrt(this.distanceToSqr(this.partner)));
                String aim = this.getTarget() == null ? "-"
                        : this.getTarget().getType().toString();
                System.out.println("[mmdsrv] " + this.variant()
                        + " tick=" + this.tickCount
                        + " sing=" + (this.isSinging() ? 1 : 0)
                        + " combat=" + (combat ? 1 : 0)
                        + " partner=" + mate
                        + " gap=" + gap
                        + " target=" + aim
                        + " grudge=" + this.grudgeTicks
                        + " reunion=" + this.reunionTicks
                        + " pos=" + String.format("%.1f/%.1f/%.1f",
                                this.getX(), this.getY(), this.getZ()));
            }

            // The end of a fight arms the reunion. Nothing else does: a pair
            // that simply spawned far apart is supposed to drift on its own.
            if (this.wasCombat && !combat) {
                this.reunionTicks = REUNION_TICKS;
            }
            this.wasCombat = combat;

            if (combat) {
                this.reunion = null;
                this.entityData.set(AIM_YAW, this.bearingTo(this.getTarget()));
            } else if (this.partner != null) {
                // Inside duet range the ordinary approach owns the movement.
                this.reunion = null;
                this.approachOrHold();
            } else {
                this.tickReunion();
            }
        }

        super.tick();

        if (this.level().isClientSide()) {
            double dx = this.getX() - this.lastX;
            double dz = this.getZ() - this.lastZ;
            double moved = Math.sqrt(dx * dx + dz * dz);
            this.lastX = this.getX();
            this.lastZ = this.getZ();
            if (!this.measured) {
                moved = 0.0;
                this.measured = true;
            }
            moved = Math.min(moved, 0.5);

            // Walking is driven purely by real displacement now. It used to be
            // suppressed outright while singing, which meant a pair closing the
            // distance sang with frozen legs. Tying it to movement instead
            // keeps the approach walk animated and still fades to a stand once
            // they stop.
            boolean grounded = this.onGround()
                    && !this.isInWater()
                    && !this.isPassenger()
                    && this.isAlive();
            // Square-rooted instead of linear on purpose. A linear ramp only
            // reaches a readable stride at a full sprint, so an unhurried walk
            // produced a couple of degrees of leg swing and read as gliding.
            // The root curve lifts gentle walking into a visible stride while
            // still saturating at one for anything faster.
            float target = grounded ? Math.min(1.0F, (float) Math.sqrt(moved * 40.0F)) : 0.0F;
            this.walkWeight += (target - this.walkWeight) * 0.30F;
            if (this.walkWeight < 0.002F) {
                this.walkWeight = 0.0F;
            }
            if (grounded && moved > 1.0e-4) {
                this.gaitPhase += (float) moved * PHASE_PER_BLOCK;
                float period = (float) (Math.PI * 2.0);
                while (this.gaitPhase > period) {
                    this.gaitPhase -= period;
                }
            }
        }

        // Who owns the gaze: a fight always outranks the duet, because a singer
        // that is kicking something must look at it, not at its partner. Both
        // branches write after {@code super.tick()} so they survive the look
        // controller for this frame.
        if (this.level().isClientSide()) {
            // The client has no target of its own, so it follows the synced
            // bearing. It must not fall back to the duet gaze while fighting:
            // that was what turned the drawn body onto the partner and threw
            // the kick sideways.
            if (this.entityData.get(COMBAT)) {
                this.faceBearing(this.entityData.get(AIM_YAW),
                        COMBAT_TURN_RATE, COMBAT_HEAD_RATE);
            } else if (this.partner != null) {
                this.facePartner();
            }
        } else if (this.getTarget() != null) {
            this.faceTowards(this.getTarget());
        } else if (this.partner != null) {
            this.facePartner();
        }
    }

    /**
     * Drops a target that is no longer worth chasing.
     *
     * <p>Vanilla clears stale targets inside its own goals, but both goals here
     * are hand-written: {@link RageTargetGoal} deliberately refuses to re-scan
     * while a target is held, so nothing else would ever release one. Without
     * this a singer would stand over a corpse or chase a player who ran away
     * forever, which also silently breaks the duet.
     */
    private void maintainTarget() {
        LivingEntity target = this.getTarget();
        if (target == null) {
            return;
        }
        boolean drop = !target.isAlive() || target.isRemoved() || target.level() != this.level();
        // The grudge is what keeps a player marked. Once it fades they are only
        // forgotten if they also got away; a player still standing in front of
        // a singer keeps being a problem.
        if (!drop && target instanceof Player && this.grudgeTicks <= 0
                && this.distanceToSqr(target) > AGGRO_LEASH * AGGRO_LEASH) {
            drop = true;
        }
        if (drop) {
            this.setTarget(null);
            if (this.provoker == target) {
                this.provoker = null;
            }
        }
    }

    /**
     * Walks the pair together, then parks them.
     *
     * <p>Server side only. Inside {@link #PAIR_RANGE} the singers head for each
     * other; once they are within {@link #DUET_DISTANCE} both stop so the duet
     * happens at a fixed, readable spacing rather than nose to nose.
     */
    private void approachOrHold() {
        if (this.partner == null) {
            return;
        }
        double gap = this.duetGap();
        if (gap <= DUET_DISTANCE) {
            this.getNavigation().stop();
            return;
        }
        // Re-path only occasionally; asking every tick would thrash the
        // navigator and stall the walk.
        if (this.tickCount % 10 == 0 || this.getNavigation().isDone()) {
            this.getNavigation().moveTo(this.partner, APPROACH_SPEED);
        }
    }

    /**
     * Walks back toward the partner after a fight, so the song can resume.
     *
     * <p>{@link #approachOrHold()} only handles a partner that is already
     * inside {@link #PAIR_RANGE}. The fight is what breaks that: one of the
     * pair charges a player across the arena while the other is left standing
     * where the duet was, and when the target finally dies they are far enough
     * apart that neither finds the other again. They then wandered separately
     * forever, which looked exactly like the song having been permanently lost.
     *
     * <p>The walk runs over a wider leash and gives up after
     * {@link #REUNION_TICKS}, so a singer that cannot reach its partner - a
     * wall, a ravine, a partner penned up - goes back to roaming instead of
     * pushing against an obstacle indefinitely.
     */
    private void tickReunion() {
        if (this.reunionTicks <= 0) {
            this.reunion = null;
            return;
        }
        SingerEntity mate = this.reunion;
        if (mate != null && (!mate.isAlive()
                || mate.isRemoved()
                || mate.level() != this.level()
                || mate.getTarget() != null
                || this.variant().equals(mate.variant()))) {
            mate = null;
        }
        if (mate == null) {
            mate = this.findReunionPartner();
            this.reunion = mate;
        }
        if (mate == null) {
            return;
        }
        if (this.tickCount % 10 == 0 || this.getNavigation().isDone()) {
            this.getNavigation().moveTo(mate, APPROACH_SPEED);
        }
    }

    /** Nearest singer of the other variant that is not currently fighting. */
    private SingerEntity findReunionPartner() {
        List<SingerEntity> near = this.level().getEntitiesOfClass(
                SingerEntity.class,
                this.getBoundingBox().inflate(REUNION_RANGE, 8.0, REUNION_RANGE),
                other -> other != this
                        && other.isAlive()
                        && !other.isRemoved()
                        && other.getTarget() == null
                        && !this.variant().equals(other.variant()));
        return near.stream()
                .min(Comparator.comparingDouble(this::distanceToSqr))
                .orElse(null);
    }

    /**
     * True while this singer is walking back to its partner.
     *
     * <p>Used by {@link WanderGoal} to stand down: the navigator has one path
     * at a time, and a wander that starts mid-reunion sends the singer to a
     * random spot instead, so the pair never closes up and never sings again.
     */
    boolean wantsReunion() {
        return this.reunion != null && this.reunionTicks > 0;
    }

    /**
     * Swings body and head onto the partner.
     *
     * <p>The rotation is eased rather than snapped, so a pair that turns to
     * face each other does so as a smooth neck-and-shoulders movement instead
     * of a one-frame flip. Head yaw is written after {@code super.tick()}, which
     * is what lets it override the look controller for the rendered frame.
     */
    private void facePartner() {
        this.faceTowards(this.partner);
    }

    /**
     * Eases body and head onto {@code target}.
     *
     * <p>Shared by the duet and by combat on purpose. When the two had separate
     * code paths the singing pair swayed at one rate and the fighting pair at
     * another, and a singer that switched from walking to kicking snapped its
     * head round in a single frame.
     */
    private void faceTowards(LivingEntity target) {
        if (target == null) {
            return;
        }
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        boolean fighting = this.getTarget() != null;
        this.faceBearing(this.bearingTo(target),
                fighting ? COMBAT_TURN_RATE : TURN_RATE,
                fighting ? COMBAT_HEAD_RATE : HEAD_RATE);

        // Slight vertical tilt, so a tall singer does not stare at a short
        // one's collarbone and a short one does not stare at the sky.
        double dy = target.getEyeY() - this.getEyeY();
        double flat = Math.max(0.25, Math.sqrt(dx * dx + dz * dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        this.setXRot(Mth.rotLerp(0.30F, this.getXRot(), pitch));
    }

    /**
     * Compass bearing to {@code target}, in the same degrees the game stores
     * yaw in, so it can be synced and applied without further conversion.
     */
    private float bearingTo(LivingEntity target) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return this.getYRot();
        }
        // Yaw zero looks along +Z and grows toward -X, so the delta angle has
        // to be measured from +Z rather than from +X.
        return (float) (Math.toDegrees(Math.atan2(dz, dx))) - 90.0F;
    }

    /**
     * Eases body and head onto a bearing already expressed in game yaw degrees.
     *
     * <p>Split out from {@link #faceTowards} so the client can drive the same
     * movement from the synced {@link #AIM_YAW} without needing the target
     * entity at all. Both sides therefore use one turning law and the drawn
     * body and the hit box agree.
     */
    private void faceBearing(float bearing, float bodyRate, float headRate) {
        // Bodies turn first and slower; heads lead, which reads as two people
        // looking up at each other before the shoulders follow.
        float body = Mth.rotLerp(bodyRate, this.getYRot(), bearing);
        this.setYRot(body);
        this.setYBodyRot(body);
        this.yHeadRot = Mth.rotLerp(headRate, this.yHeadRot, bearing);
        this.setYHeadRot(this.yHeadRot);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    public boolean removeWhenFarAway(double distanceSq) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
