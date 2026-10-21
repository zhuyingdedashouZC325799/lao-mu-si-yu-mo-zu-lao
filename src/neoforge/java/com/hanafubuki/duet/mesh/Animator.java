package com.hanafubuki.duet.mesh;

/**
 * Procedural animation for the full MMD skeleton.
 *
 * <p>Everything is authored directly on {@link Skeleton} role bones, so the
 * result is a real skeletal pose rather than part transforms: arms are folded
 * down out of the bind A-pose, legs run a planted-foot walk cycle, and the
 * centre bone is lowered so the supporting foot always touches the model's
 * ground plane. Because the whole authored hierarchy is kept, rotating the
 * upper arm also carries the twist bones and the sleeve chain, and rotating the
 * head carries the hair.
 */
public final class Animator {

    private static final float PI = (float) Math.PI;

    private final Skeleton sk;

    /** Rotation about Z that folds each arm down to the body. */
    private final float armDropR;
    private final float armDropL;
    /** Ankle height in bind pose; the sole sits at model y = 0. */
    private final float soleY;
    private final float thighLen;
    private final float shinLen;

    /**
     * Hip to ankle distance the solver aims for while the foot is on the floor.
     *
     * <p>Slightly shorter than the two bones laid end to end, which keeps the
     * knee softly bent through the whole stance instead of locking it straight.
     * Because the stance drop is computed from this span rather than from the
     * bones, the knee angle stays constant for the whole stance and there is no
     * visible hitch where one foot takes over from the other.
     */
    private final float legSpan;

    /** Scratch results for the two legs, to keep the per-frame work allocation free. */
    private final float[] legSolveR = new float[2];
    private final float[] legSolveL = new float[2];

    public Animator(MmdModel model, Skeleton skeleton) {
        this.sk = skeleton;
        this.armDropR = dropFor(skeleton, skeleton.armR, skeleton.elbowR);
        this.armDropL = dropFor(skeleton, skeleton.armL, skeleton.elbowL);
        this.soleY = model.soleY;
        this.thighLen = len3(
                skeleton.bindX(skeleton.kneeR) - skeleton.bindX(skeleton.thighR),
                skeleton.bindY(skeleton.kneeR) - skeleton.bindY(skeleton.thighR),
                skeleton.bindZ(skeleton.kneeR) - skeleton.bindZ(skeleton.thighR));
        this.shinLen = len3(
                skeleton.bindX(skeleton.ankleR) - skeleton.bindX(skeleton.kneeR),
                skeleton.bindY(skeleton.ankleR) - skeleton.bindY(skeleton.kneeR),
                skeleton.bindZ(skeleton.ankleR) - skeleton.bindZ(skeleton.kneeR));
        this.legSpan = (this.thighLen + this.shinLen) * 0.994f;
    }

    /**
     * Two bone inverse kinematics: thigh and knee angles that put the ankle at
     * {@code (forward, drop)} relative to the hip.
     *
     * <p>Authoring the leg by joint angles is what produced every foot problem
     * so far. Two sines cannot be tuned into a walk, because the position of the
     * foot is the sum of both of them: making the knee fold at the right moment
     * then moved the foot, so the two effects kept fighting. Solving for the
     * foot position instead makes the trajectory the thing that is authored,
     * and the joints follow. Stance is then straight, level and never slips, and
     * swing is a lifted arc, by construction rather than by tuning.
     *
     * <p>The sign convention is measured, not assumed: on this rig a positive X
     * rotation swings a limb toward {@code -Z}, which is the direction the mesh
     * faces, and a negative X on the knee is the one that folds the shin
     * backward. {@code LegProbe} in the offline harness prints that table.
     *
     * @param out receives {@code {thighForward, kneeFlexion}} in radians
     */
    private float[] solveLeg(float forward, float drop, float[] out) {
        float l1 = this.thighLen;
        float l2 = this.shinLen;
        float d = (float) Math.sqrt(forward * forward + drop * drop);
        float lo = Math.abs(l1 - l2) + 1.0e-3f;
        float hi = l1 + l2 - 1.0e-3f;
        if (d < lo) {
            d = lo;
        } else if (d > hi) {
            d = hi;
        }
        float cosKnee = (l1 * l1 + l2 * l2 - d * d) / (2f * l1 * l2);
        cosKnee = clampAbs(cosKnee, 1f);
        float cosBeta = (l1 * l1 + d * d - l2 * l2) / (2f * l1 * d);
        cosBeta = clampAbs(cosBeta, 1f);
        // Interior angle at the knee, so the flexion measured from straight is
        // its supplement; the thigh sits the other angle off the hip-to-ankle
        // line, on the side that puts the shin behind the thigh.
        out[0] = (float) Math.atan2(forward, drop) + (float) Math.acos(cosBeta);
        out[1] = PI - (float) Math.acos(cosKnee);
        return out;
    }

    /**
     * Angle about Z that rotates the arm chain onto the vertical axis.
     *
     * <p>Solved from the bind geometry so it adapts to whatever pose the model
     * was authored in, instead of a hard-coded constant.
     */
    private static float dropFor(Skeleton sk, int shoulder, int elbow) {
        if (shoulder < 0 || elbow < 0) {
            return 0f;
        }
        float dx = sk.bindX(elbow) - sk.bindX(shoulder);
        float dy = sk.bindY(elbow) - sk.bindY(shoulder);
        if (Math.abs(dx) < 1e-4f && Math.abs(dy) < 1e-4f) {
            return 0f;
        }
        float angle = (float) Math.atan2(dx, dy) + PI;
        if (angle > PI) {
            angle -= 2f * PI;
        }
        if (angle < -PI) {
            angle += 2f * PI;
        }
        // Keep a few degrees of clearance so sleeves never clip the torso.
        return angle * 0.93f;
    }

    private static float len3(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clampAbs(float v, float limit) {
        if (v > limit) {
            return limit;
        }
        if (v < -limit) {
            return -limit;
        }
        return v;
    }

    private static final float PI2 = (float) (Math.PI * 2.0);

    private static final float HALF_PI = (float) (Math.PI * 0.5);

    /** Wraps a cycle position into {@code [0, 2PI)}. */
    private static float wrapCycle(float phase) {
        float p = phase % PI2;
        return p < 0f ? p + PI2 : p;
    }

    /**
     * Half the horizontal travel of one step, in model units.
     *
     * <p>The foot starts a stance this far in front of the hip and ends it the
     * same distance behind, so the whole stride is {@code 2 * STRIDE}. That
     * number is also what {@code SingerEntity.PHASE_PER_BLOCK} is derived from:
     * the ground can only pass under the planted foot without sliding if the
     * two agree exactly.
     */
    private static final float STRIDE = 4.65f;

    /**
     * Peak height of the swinging foot above its stance height, model units.
     *
     * <p>Thirty millimetres at the current world scale: enough that the boot
     * clearly clears the floor, small enough to stay a walk rather than a march.
     */
    private static final float LIFT = 1.30f;

    /**
     * Half the length of the boot, model units.
     *
     * <p>Used by the ground clamp: a boot that is pitched off its bind angle
     * pivots about the ankle, so its heel or its toe hangs below the sole
     * plane by roughly this radius times the sine of the tilt. Without this
     * the toe visibly sank through the floor at the heel strike.
     */
    private static final float HEEL_RADIUS = 1.20f;

    /**
     * Forward offset of the foot for step clock {@code u}, model units.
     *
     * <p>Stance runs from the heel strike at zero to the toe off at half a
     * turn, and the foot travels backward in a straight line through it. A
     * straight line is the point: the body moves forward at a constant speed,
     * so a planted foot has to move backward at a constant speed too. Any
     * easing here shows up as a skate.
     *
     * <p>The swing has to cover the same distance forward in the same amount of
     * time, but with an eased start and end, which is what the cosine ramp is
     * for. The foot therefore leaves the ground slowly, sweeps past the hip,
     * and slows down again into the next heel strike.
     */
    private static float footForward(float u, float half) {
        float p = wrapCycle(u);
        if (p <= PI) {
            return half * (1f - 2f * (p / PI));
        }
        float t = (p - PI) / PI;
        float s = 0.5f - 0.5f * (float) Math.cos(PI * t);
        return -half + 2f * half * s;
    }

    /**
     * Height the foot is lifted by for step clock {@code u}, model units.
     *
     * <p>Zero for the whole stance, so the planted foot stays exactly on the
     * floor, then a half sine through the swing. The sine matters at both ends:
     * it leaves and rejoins the floor with zero slope, so neither the lift off
     * nor the touch down snaps.
     */
    private static float footLift(float u, float amount) {
        float p = wrapCycle(u);
        if (p <= PI) {
            return 0f;
        }
        return amount * (float) Math.sin(p - PI);
    }

    /**
     * Ankle rotation that rolls the boot from heel strike to toe off.
     *
     * <p>Positive X lifts the toe on this rig, so the foot starts toes up,
     * flattens through mid-stance and drops its heel for the push off, then
     * picks the toe back up to clear the ground. It rejoins the stance value at
     * the heel strike so the cycle has no step in it.
     */
    private static float footPitch(float u) {
        float p = wrapCycle(u);
        if (p <= PI) {
            return 0.16f * (float) Math.cos(p);
        }
        float t = (p - PI) / PI;
        return -0.16f + 0.32f * t + 0.10f * (float) Math.sin(PI * t);
    }

    /**
     * Largest relative angle the ankle is allowed to take, in radians.
     *
     * <p>The boot is skinned across the shin and the foot, so a large relative
     * angle smears the cuff. Tracking a level sole exactly through the swing
     * demanded over ninety degrees there; capping the ankle lets the boot hang
     * off the folded shin instead, which is also what a real foot does.
     */
    private static final float ANKLE_MAX = 0.55f;

    /**
     * Keyframes of one kick, as fractions of the hip-to-ankle span.
     *
     * <p>Stride, chamber, extension, recovery, stand: {@code forward} is how
     * far the ankle sits ahead of the hip and {@code drop} how far below it, and
     * the ankle column is the extra sole pitch on top of the automatic
     * levelling. The boot is authored as a foot trajectory for the same reason
     * the walk is: solving the two bones for a target position is what stops
     * the shin from being stretched when the leg swings wide.
     *
     * <p>{@code drop = 1} at both ends is the standing stance, so a kick started
     * or finished while walking rejoins the gait without a pop.
     */
    private static final float[] KICK_KEYS = {
            // progress, forward, drop, ankle
            0.00f, 0.00f, 1.000f, 0.00f,
            0.20f, 0.16f, 0.800f, 0.28f,
            0.34f, 0.33f, 0.530f, 0.52f,
            0.50f, 0.90f, 0.340f, 0.72f,
            0.66f, 0.70f, 0.490f, 0.34f,
            0.82f, 0.31f, 0.730f, 0.08f,
            1.00f, 0.00f, 1.000f, 0.00f,
    };

    /** Scratch result for the kicking leg: {@code {thigh, knee, ankle}}. */
    private final float[] kickSolve = new float[3];

    /** Scratch for one eased keyframe pair: {@code {forward, drop, ankle}}. */
    private final float[] kickFrom = new float[3];
    private final float[] kickTo = new float[3];

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Solves the kicking leg for progress {@code kp}.
     *
     * <p>Walks {@link #KICK_KEYS} to find the two surrounding keyframes, blends
     * the foot target between them with a smoothstep so the boot has no corners
     * in its path, and hands that target to the same solver the walk uses. The
     * result is {@code {thighRotation, kneeFlexion, extraAnklePitch}}.
     */
    private float[] solveKick(float kp, float span) {
        int stride = 4;
        int frames = KICK_KEYS.length / stride;
        // Clamp the segment index so a progress of exactly 1 lands on the last
        // pair instead of running off the end of the table.
        int seg = frames - 2;
        for (int f = 0; f + 1 < frames; f++) {
            if (kp <= KICK_KEYS[(f + 1) * stride]) {
                seg = f;
                break;
            }
        }
        float t0 = KICK_KEYS[seg * stride];
        float t1 = KICK_KEYS[(seg + 1) * stride];
        float blend = t1 > t0 ? smoothstep(t0, t1, kp) : 0f;
        for (int k = 0; k < 3; k++) {
            this.kickFrom[k] = KICK_KEYS[seg * stride + 1 + k];
            this.kickTo[k] = KICK_KEYS[(seg + 1) * stride + 1 + k];
        }
        float fwd = lerp(this.kickFrom[0], this.kickTo[0], blend) * span;
        float drop = lerp(this.kickFrom[1], this.kickTo[1], blend) * span;
        float ankle = lerp(this.kickFrom[2], this.kickTo[2], blend);

        float[] solved = this.solveLeg(fwd, drop, this.legSolveR);
        this.kickSolve[0] = solved[0];
        this.kickSolve[1] = solved[1];
        this.kickSolve[2] = ankle;
        return this.kickSolve;
    }

    /**
     * Poses the skeleton and expands it.
     *
     * @param gaitPhase  accumulated walk phase in radians; one cycle = 2 steps
     * @param walkWeight 0 = standing, 1 = full walk cycle
     * @param idleSeconds free-running clock for breathing, wraps naturally
     * @param headYawDeg relative head yaw, degrees
     * @param headPitchDeg head pitch, degrees (positive = looking down)
     * @param singWeight 0..1 blend of the performance pose
     * @param singSeconds seconds into the duet, used for the sway rhythm
     * @param lead true for the singer that drives the duet, false for the one
     *             that answers it; the two have opposite stage business
     * @param kickProgress 0..1 through a kick, or -1 when not kicking
     */
    public void apply(float gaitPhase, float walkWeight, float idleSeconds,
                      float headYawDeg, float headPitchDeg,
                      float singWeight, float singSeconds, boolean lead,
                      float kickProgress) {
        Skeleton sk = this.sk;
        sk.reset();

        float w = clamp01(walkWeight);
        float sg = clamp01(singWeight);
        float rest = clamp01(1f - w) * (1f - 0.6f * sg);

        // The turn window, not the duet as a whole. Both singers are in the
        // duet for its whole length, but only one of them is singing at any
        // moment, so the pose has to follow the turn or they would perform
        // continuously and look like they were both miming.
        float perf = sg * Performance.poseAt(singSeconds, lead);

        float ph = gaitPhase;
        float sinP = (float) Math.sin(ph);
        float cosP = (float) Math.cos(ph);
        float cos2P = (float) Math.cos(2f * ph);
        float breath = (float) Math.sin(idleSeconds * 1.55f);
        float breath2 = (float) Math.sin(idleSeconds * 0.83f + 1.1f);

        // ---- legs: solved from the footprint, not from joint angles -----
        // Each leg runs on its own step clock: zero at the heel strike, a
        // quarter turn at mid-stance, half a turn at the toe off, three
        // quarters through the swing. The right leg is a half turn ahead of
        // the left, so exactly one of them is on the floor at any moment and
        // the two swap over the instant they are level with each other.
        float half = STRIDE * w;
        float liftAmount = LIFT * w;
        float span = this.legSpan;

        float uR = wrapCycle(ph);
        float uL = wrapCycle(ph + PI);

        float fwdR = footForward(uR, half);
        float fwdL = footForward(uL, half);
        float dropR = (float) Math.sqrt(Math.max(0.01f, span * span - fwdR * fwdR))
                - footLift(uR, liftAmount);
        float dropL = (float) Math.sqrt(Math.max(0.01f, span * span - fwdL * fwdL))
                - footLift(uL, liftAmount);

        float[] legR = this.solveLeg(fwdR, dropR, this.legSolveR);
        float[] legL = this.solveLeg(fwdL, dropL, this.legSolveL);
        float ankleBaseR = footPitch(uR);

        // ---- kick override -------------------------------------------
        // Replaces the right leg's solved pose while the kick runs. The keyframes
        // are solved through the same two bone IK as the walk, so the boot travels
        // the authored arc with the shin at the length it was modelled at - the
        // stretched foot that shows up when a leg is posed by joint angles alone
        // cannot happen here.
        float kickW = 0f;
        if (kickProgress >= 0f) {
            float kp = clamp01(kickProgress);
            float[] solved = this.solveKick(kp, span);
            // A short ramp in only. The table already ends standing, so ramping
            // out as well would snap the boot back mid retraction.
            kickW = smoothstep(0f, 0.10f, kp);
            legR[0] = lerp(legR[0], solved[0], kickW);
            legR[1] = lerp(legR[1], solved[1], kickW);
            ankleBaseR = lerp(ankleBaseR, solved[2], kickW);
        }

        sk.setRotation(sk.thighR, legR[0], 0f, 0f);
        sk.setRotation(sk.thighL, legL[0], 0f, 0f);
        sk.setRotation(sk.kneeR, -legR[1], 0f, 0f);
        sk.setRotation(sk.kneeL, -legL[1], 0f, 0f);

        // The ankle cancels whatever the thigh and knee already did to the
        // shin, which keeps the sole parallel to the floor, and then adds the
        // heel strike and push off roll on top of that.
        float ankleR = clampAbs(ankleBaseR - (legR[0] - legR[1]), ANKLE_MAX);
        float ankleL = clampAbs(footPitch(uL) - (legL[0] - legL[1]), ANKLE_MAX);
        sk.setRotation(sk.ankleR, ankleR, 0f, 0f);
        sk.setRotation(sk.ankleL, ankleL, 0f, 0f);

        // Slight knee softening while standing so the legs are never ramrod
        // straight; this reads much better than a locked bind pose.
        float soft = 0.055f + 0.02f * breath;
        sk.addRotation(sk.kneeR, -soft * rest, 0f, 0f);
        sk.addRotation(sk.kneeL, -soft * rest, 0f, 0f);
        sk.addRotation(sk.ankleR, soft * 0.5f * rest, 0f, 0f);
        sk.addRotation(sk.ankleL, soft * 0.5f * rest, 0f, 0f);

        // ---- torso: pelvis lead, weight shift, counter-twist ----------
        // The pelvis turns toward whichever leg is swinging forward, slides
        // over the standing leg, and the chest twists the opposite way to
        // cancel it. That chain is most of what separates "walking" from
        // "sliding a statue", so it is worth the arithmetic.
        float hipLead = sinP;
        float shift = 0.16f * (float) Math.sin(ph) * w;
        sk.setOffset(sk.lower, shift, 0f, 0f);
        sk.setRotation(sk.lower,
                0.012f * w,
                0.105f * hipLead * w,
                0.048f * cosP * w);
        sk.setRotation(sk.upper,
                0.050f * w + 0.010f * breath * rest,
                -0.135f * hipLead * w,
                0.030f * hipLead * w + 0.012f * breath2 * rest);
        sk.setRotation(sk.upper1, 0f, -0.045f * hipLead * w, 0.010f * breath * rest);
        sk.setRotation(sk.chest, 0f, -0.050f * hipLead * w, 0.012f * breath * rest);

        // ---- arms: fold out of the A-pose, then swing ------------------
        // The swing is driven off the same-side leg's footprint so the arm can
        // never drift out of phase with the leg it belongs to: it takes the
        // opposite sign, and the elbow folds as the hand comes forward. Both
        // arms share one rotation convention on this rig -- the earlier build
        // gave them mirrored signs, which swung them together like a soldier.
        float armSwing = 0.105f;
        float armFwdR = -armSwing * fwdR;
        float armFwdL = -armSwing * fwdL;
        sk.setRotation(sk.armR,
                armFwdR + 0.020f * breath * rest,
                0f,
                armDropR - 0.030f * breath * rest);
        sk.setRotation(sk.armL,
                armFwdL + 0.020f * breath2 * rest,
                0f,
                armDropL + 0.030f * breath2 * rest);

        float elbowBase = 0.13f;
        float elbowSwing = 0.70f;
        sk.setRotation(sk.elbowR,
                elbowBase + elbowSwing * clamp01(armFwdR / 0.45f), 0f, 0f);
        sk.setRotation(sk.elbowL,
                elbowBase + elbowSwing * clamp01(armFwdL / 0.45f), 0f, 0f);
        sk.setRotation(sk.wristR, 0.05f, 0f, 0f);
        sk.setRotation(sk.wristL, 0.05f, 0f, 0f);

        // ---- kick counterweight --------------------------------------
        // A kick that only moves the leg reads as a leg twitch nailed onto a
        // walking body. Throwing a boot forward with real mass means the torso
        // leans back over the standing foot, the arms come up to balance, and
        // the shoulders counter-rotate against the driving hip. All three are
        // added on top of whatever the gait authored, scaled by how far into
        // the kick the boot is, so the whole body arrives at the extension
        // together instead of the leg arriving alone.
        float kickEnergy = 0f;
        if (kickProgress >= 0f) {
            float kp = clamp01(kickProgress);
            // Rises through the chamber, peaks as the boot extends, then eases
            // off through the retraction.
            kickEnergy = (float) Math.sin(Math.PI * Math.min(1f, kp * 1.15f));
            kickEnergy *= kickEnergy;
            float lean = 0.30f * kickEnergy;
            sk.addRotation(sk.lower, kickEnergy * 0.055f, -kickEnergy * 0.12f,
                    -kickEnergy * 0.05f);
            sk.addRotation(sk.upper, -lean * 0.40f, kickEnergy * 0.16f, 0f);
            sk.addRotation(sk.upper1, -lean * 0.22f, kickEnergy * 0.06f, 0f);
            sk.addRotation(sk.chest, -lean * 0.24f, kickEnergy * 0.05f, 0f);

            // Left arm swings forward and across, the right one drives back:
            // the asymmetric pair that a real kick produces and that stops the
            // pose from looking like a symmetrical jump.
            sk.addRotation(sk.shoulderL, 0f, 0f, kickEnergy * 0.10f);
            sk.addRotation(sk.shoulderR, 0f, 0f, -kickEnergy * 0.05f);
            sk.addRotation(sk.armL, kickEnergy * 0.62f, 0f, kickEnergy * 0.34f);
            sk.addRotation(sk.elbowL, kickEnergy * 0.85f, 0f, 0f);
            sk.addRotation(sk.armR, -kickEnergy * 0.34f, 0f, -kickEnergy * 0.20f);
            sk.addRotation(sk.elbowR, kickEnergy * 0.42f, 0f, 0f);

            // The standing leg straightens and takes the load as the body rises
            // over it, which is what keeps the character from looking planted
            // in concrete while the other boot is in the air.
            legL[1] *= lerp(1f, 0.55f, kickEnergy);
            sk.setRotation(sk.kneeL, -legL[1], 0f, 0f);
        }

        // ---- head: look direction plus a little life ------------------
        float yawR = (float) Math.toRadians(headYawDeg);
        float pitchR = (float) Math.toRadians(headPitchDeg);
        sk.setRotation(sk.neck, -pitchR * 0.30f, yawR * 0.30f, 0f);
        sk.setRotation(sk.head, -pitchR * 0.70f + 0.012f * breath * rest, yawR * 0.70f,
                0.014f * breath2 * rest);

        if (kickEnergy > 0.001f) {
            // Overrides the head wander while fighting: the gaze is owned by the
            // victim through the whole kick, not just at the impact frame.
            // The sign is the inverse of the torso lean added above, cancelling
            // most of it so the head keeps pointing at the target instead of
            // being dragged up with the chest.
            sk.addRotation(sk.neck, kickEnergy * 0.055f, 0f, 0f);
            sk.addRotation(sk.head, kickEnergy * 0.085f, 0f, 0f);
        }

        // ---- performance ----------------------------------------------
        // Two different performers, the way the reference duet has them. The
        // lead punches the phrase; the answer sings it more softly with the
        // hands held together. Everything below is driven by the song, not by
        // a free-running sine: `accent` is the vocal envelope minus its own
        // local average, so it peaks on stressed syllables and sits still when
        // the voice is not moving. That is what stops the gesture from reading
        // as an idle animation played at the wrong tempo.
        if (perf > 0.001f) {
            float t = singSeconds;
            float accent = Performance.thrustAt(t);
            // Slightly staler accent for the arms than for the torso, so the
            // body arrives first and the hands follow it.
            float accentArm = Performance.thrustBefore(t, 0.10f);
            float accent2 = 2f * accent - 1f;
            float sway = Performance.swayAt(t);

            // Chin up and ribcage open. Shared by both singers because it is
            // the single clearest read of "singing" there is, and it works at
            // any distance the player might be watching from.
            sk.addRotation(sk.chest, -0.030f * perf, 0f, 0f);
            sk.addRotation(sk.neck, -0.030f * perf, 0f, 0f);
            sk.addRotation(sk.head, -0.050f * perf, 0f, 0f);

            // Arm angles are offsets from the *hanging* pose, not from the bind
            // A-pose: the neutral arm is already folded down by armDropR/L. An
            // earlier build added the raise on top of the bind pose, which put
            // the upper arms out at shoulder height and turned the wide sleeves
            // into a pair of wings. The reference duet instead keeps the upper
            // arms low and does the singing with the forearms: a forward swing
            // of the upper arm plus a deep elbow flex leaves both hands in front
            // of the chest, which is the silhouette that reads as singing.
            if (lead) {
                // ---- lead: forward-weighted, arms pumping on the accent ----
                // The reference lead leans the whole torso into the phrase -
                // 0.23 rad plus an accent term - and tips the head back by
                // about 0.20. Those two numbers are most of why its singer
                // reads as projecting; the arms are a smaller part of it.
                sk.addRotation(sk.upper, perf * (0.215f + 0.070f * accent2), 0f, 0f);
                sk.addRotation(sk.upper1, perf * (0.045f + 0.018f * accent2), 0f, 0f);
                sk.addRotation(sk.lower, 0f, 0f, 0.020f * perf * accent2);

                sk.addRotation(sk.head, -perf * (0.125f + 0.065f * accent2),
                        -0.10f * perf * sway, 0.020f * perf * accent2);

                // Shoulders lift and roll forward as the arms come up, which is
                // what makes the lift look driven by the chest rather than by
                // the arm alone.
                sk.addRotation(sk.shoulderR, 0f, 0f, -perf * (0.10f + 0.04f * accentArm));
                sk.addRotation(sk.shoulderL, 0f, 0f, perf * (0.10f + 0.04f * accentArm));

                // The reference singer barely lifts the upper arm: the whole
                // gesture lives in the forearm, which folds up in front of the
                // chest and jabs on every stress. Copying that split matters,
                // because lifting the upper arm instead swings this model's
                // very long sleeves out sideways into wings.
                sk.addRotation(sk.armR, perf * (0.22f + 0.30f * accentArm), 0f,
                        -perf * (0.13f + 0.05f * accentArm));
                sk.addRotation(sk.armL, perf * (0.22f + 0.30f * accentArm), 0f,
                        perf * (0.13f + 0.05f * accentArm));

                sk.addRotation(sk.elbowR, perf * (1.26f + 0.52f * accentArm), 0f, 0f);
                sk.addRotation(sk.elbowL, perf * (1.26f + 0.52f * accentArm), 0f, 0f);
                sk.addRotation(sk.wristR, perf * (0.26f + 0.12f * accent2), 0f, 0f);
                sk.addRotation(sk.wristL, perf * (0.26f + 0.12f * accent2), 0f, 0f);
            } else {
                // ---- answer: gentle, asymmetric, hands together ------------
                sk.addRotation(sk.upper, perf * (0.030f + 0.012f * accent2),
                        0.045f * perf * sway, 0.030f * perf * sway);
                sk.addRotation(sk.upper1, 0f, 0.020f * perf * sway,
                        0.018f * perf * accent2);
                sk.addRotation(sk.lower, 0f, 0.035f * perf * sway, 0f);

                sk.addRotation(sk.head, -0.040f * perf * accent2,
                        0.055f * perf * sway, 0.055f * perf * sway);

                sk.addRotation(sk.shoulderR, 0f, 0f, -0.06f * perf);
                sk.addRotation(sk.shoulderL, 0f, 0f, 0.06f * perf);

                // One hand rides higher than the other and the two trade places
                // with the sway, so the pose breathes instead of freezing. The
                // hands finish in front of the chest rather than out to the
                // sides, which is the softer silhouette this singer wants.
                sk.addRotation(sk.armR, perf * (0.62f + 0.12f * accent2), 0f,
                        -perf * (0.34f + 0.10f * sway));
                sk.addRotation(sk.armL, perf * (0.54f - 0.12f * accent2), 0f,
                        perf * (0.24f - 0.10f * sway));

                sk.addRotation(sk.elbowR, perf * (1.56f + 0.14f * accent2), 0f, 0f);
                sk.addRotation(sk.elbowL, perf * (1.32f - 0.14f * accent2), 0f, 0f);
                sk.addRotation(sk.wristR, 0.30f * perf, 0f, 0.10f * perf * sway);
                sk.addRotation(sk.wristL, 0.22f * perf, 0f, -0.10f * perf * sway);
            }
        }

        // ---- ground clamp --------------------------------------------
        sk.compute();
        // The ankle pivot sits above the sole in bind pose, so its height is
        // only a stand-in for the sole height. A pitched boot hangs lower than
        // that by its heel radius times the sine of the tilt, so the tilt is
        // subtracted before taking the minimum. Without it the boot at the heel
        // strike, which is the most tilted frame, punched through the floor.
        float tiltR = legR[0] - legR[1] + ankleR;
        float tiltL = legL[0] - legL[1] + ankleL;
        float soleR = sk.pivotY(sk.ankleR) - HEEL_RADIUS * Math.abs((float) Math.sin(tiltR));
        float soleL = sk.pivotY(sk.ankleL) - HEEL_RADIUS * Math.abs((float) Math.sin(tiltL));
        float lowest = Math.min(soleR, soleL);
        float lift = soleY - lowest;
        // No vertical bob is added here any more. The solved stance drop is
        // itself the shortest at the heel strike and longest at mid-stance, so
        // the pelvis already rises and falls twice a cycle exactly the way a
        // compass gait does. Adding an explicit term on top of that only
        // doubled the amplitude and made the walk bounce.
        float bob = 0.010f * breath * rest;
        sk.setOffset(sk.center, 0f, lift + bob, 0f);
        sk.compute();
    }

    public float thighLength() {
        return this.thighLen;
    }

    public float shinLength() {
        return this.shinLen;
    }
}
