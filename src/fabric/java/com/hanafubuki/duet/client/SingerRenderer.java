package com.hanafubuki.duet.client;

import com.hanafubuki.duet.SingerEntity;
import com.hanafubuki.duet.mesh.MmdModel;
import com.hanafubuki.duet.mesh.Performance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Draws a baked MMD character.
 *
 * <p>The vanilla box-model pipeline is bypassed entirely. The mesh is skinned
 * on the CPU in model space, then emitted once per PMX material through
 * {@code submitCustomGeometry}, so every material keeps its own full
 * resolution texture and its own draw call. Real PMX UVs, one texture per
 * material, no atlas, no back-face culling: that combination is what keeps the
 * characters sharp in game.
 */
public final class SingerRenderer extends MobRenderer<SingerEntity, SingerState, MmdEntityModel> {

    /**
     * Model units to blocks. Every character shares one scale so their
     * relative proportions stay exactly as authored.
     */
    private static final float SCALE = 0.0826F;
    private static final int MAX_RIGS = 96;

    /**
     * Degrees added to the body yaw before the mesh is drawn.
     *
     * <p>A Minecraft entity faces {@code (-sin t, 0, cos t)} for body yaw
     * {@code t}, while the baked mesh faces {@code -Z} in model space; rotating
     * the mesh by {@code (yawA - t)} degrees turns its forward axis
     * {@code (-sin(yawA - t), 0, -cos(yawA - t))} onto the entity's, which only
     * holds for {@code yawA = 180}. The formula is therefore
     * {@code 180 - bodyRot}.
     */
    private static final float YAW_OFFSET = 180.0F;
    private static final float YAW_SIGN = -1.0F;

    /** Development-only rotation trace, enabled with {@code -Dmmdduet.probe}. */
    private static final boolean PROBE = System.getProperty("mmdduet.probe") != null;

    private final MmdRig.Assets assets;
    private final Identifier fallbackTexture;
    private final Map<Integer, MmdRig> rigs = new HashMap<>();
    private int frame;

    public SingerRenderer(EntityRendererProvider.Context context, String tag) {
        super(context, new MmdEntityModel(), 0.5F);
        this.assets = MmdRig.assets(tag);
        this.fallbackTexture = this.assets != null
                ? this.assets.texture(0)
                : Identifier.fromNamespaceAndPath("mmdduet", "textures/missing.png");
    }

    @Override
    public SingerState createRenderState() {
        return new SingerState();
    }

    @Override
    public Identifier getTextureLocation(SingerState state) {
        return this.fallbackTexture;
    }

    @Override
    public void extractRenderState(SingerEntity entity, SingerState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.singing = entity.isSinging();
        // Which half of the duet this character owns. 祀 leads and 梨诺 answers,
        // matching the reference duet's split between the driving singer and
        // the softer one; the animator poses the two differently.
        state.leadSinger = "si".equals(entity.variant());
        state.gaitPhase = entity.gaitPhase(partialTick);
        state.walkWeight = entity.walkWeight(partialTick);
        state.idleSeconds = state.ageInTicks * 0.05F;
        state.performanceSeconds = DuetClient.performanceSeconds(entity, partialTick);
        // Combat pose channel. Read from the entity's client-side kick clock so
        // the boot moves on every rendered frame instead of stepping with the
        // synced sequence counter.
        state.kickProgress = entity.kickProgress(partialTick);

        if (PROBE && state.kickProgress >= 0.0F) {
            // Sampled on every frame the boot is moving rather than every
            // twentieth tick: a sixteen tick kick can fall entirely between two
            // of those samples, which is exactly how it looked invisible.
            System.out.println("[mmdkick] " + entity.variant()
                    + " tick=" + entity.tickCount
                    + " kp=" + state.kickProgress
                    + " combat=" + (entity.isInCombat() ? 1 : 0)
                    + " aim=" + entity.aimYaw()
                    + " yRot=" + entity.getYRot()
                    + " body=" + state.bodyRot
                    + " drawn=" + (YAW_OFFSET + YAW_SIGN * state.bodyRot));
        }

        if (PROBE && entity.tickCount % 20 == 0) {
            System.out.println("[mmdrot] " + entity.variant()
                    + " tick=" + entity.tickCount
                    + " yRot=" + entity.getYRot()
                    + " yBodyRot=" + entity.yBodyRot
                    + " yHeadRot=" + entity.yHeadRot
                    + " stateBodyRot=" + state.bodyRot
                    + " stateYRot=" + state.yRot
                    + " pose=" + (YAW_OFFSET + YAW_SIGN * state.bodyRot)
                    + " walk=" + state.walkWeight
                    + " gait=" + state.gaitPhase
                    + " duet=" + (state.singing ? 1 : 0)
                    + " mouth=" + state.mouthOpen
                    + " perf=" + state.performanceSeconds);
        }

        float open = 0.0F;
        float wide = 0.0F;
        float round = 0.0F;
        if (state.performanceSeconds >= 0.0F) {
            // Only the singer whose turn it is moves its mouth. Without this
            // both characters mime the whole track, which reads immediately as
            // a broken lip sync because the wrong one is mouthing the words.
            float turn = Performance.activityAt(state.performanceSeconds, state.leadSinger);
            if (turn > 0.001F) {
                float[] shapes = SHAPES.get();
                AudioLevels.shapes(state.performanceSeconds, shapes);
                // The reference scales the jaw by 0.15..1.0 of the envelope so
                // the mouth keeps a little life between syllables instead of
                // clamping shut on every gap.
                float scale = turn * (0.15F + 0.85F * AudioLevels.levelAt(state.performanceSeconds));
                open = shapes[0] * scale;
                wide = shapes[1] * turn;
                round = shapes[2] * turn;
            }
        }
        state.mouthOpen = open;
        state.mouthWide = wide;
        state.mouthRound = round;

        float blinkClock = state.ageInTicks * 0.043F + entity.getId() * 0.37F;
        float phase = blinkClock - (float) Math.floor(blinkClock);
        state.blink = phase > 0.965F
                ? (float) Math.sin((phase - 0.965F) / 0.035F * Math.PI)
                : 0.0F;

        if (this.assets != null) {
            this.frame++;
            int id = entity.getId();
            MmdRig rig = this.rigs.get(id);
            if (rig == null) {
                if (this.rigs.size() >= MAX_RIGS) {
                    pruneRigs(this.rigs, this.frame);
                }
                rig = new MmdRig(this.assets);
                this.rigs.put(id, rig);
            }
            rig.lastFrame = this.frame;
            state.rig = rig;
        }
    }

    private static void pruneRigs(Map<Integer, MmdRig> rigs, int frame) {
        Iterator<Map.Entry<Integer, MmdRig>> it = rigs.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().lastFrame < frame - 2) {
                it.remove();
            }
        }
    }

    private static final ThreadLocal<float[]> SHAPES =
            ThreadLocal.withInitial(() -> new float[3]);

    @Override
    public void submit(SingerState state, PoseStack pose, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        // Shadow, name tag and vanilla layers are handled here. The model
        // itself is empty, so this costs one no-op submission.
        super.submit(state, pose, collector, camera);

        MmdRig rig = state.rig;
        if (rig == null || state.isInvisible) {
            return;
        }
        rig.animate(state);

        int light = state.lightCoords;
        int overlay = getOverlayCoords(state, this.getWhiteOverlayProgress(state));
        int tint = this.getModelTint(state);

        MmdModel model = rig.assets.model;
        float[] positions = rig.skinner.positions();
        float[] normals = rig.skinner.normals();
        float[] uvs = model.uvs;
        int[] indices = model.indices;
        MmdModel.Group[] groups = model.groups;

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(YAW_OFFSET + YAW_SIGN * state.bodyRot));
        pose.scale(SCALE, SCALE, SCALE);

        for (int gi = 0; gi < groups.length; gi++) {
            MmdModel.Group group = groups[gi];
            if (group.count <= 0 || group.alpha < 0.05F) {
                continue;
            }
            RenderType type = rig.assets.renderType(group.texture);
            int start = group.start;
            int end = group.start + group.count;
            collector.submitCustomGeometry(pose, type, (poseEntry, buffer) ->
                    emit(poseEntry, buffer, positions, normals, uvs, indices, start, end,
                            light, overlay, tint));
        }
        pose.popPose();
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer buffer,
                             float[] positions, float[] normals, float[] uvs, int[] indices,
                             int start, int end, int light, int overlay, int tint) {
        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();

        // JOML stores the matrix components in package-private fields, so the
        // accessor methods are the only legal way to read them from here.
        float m00 = matrix.m00();
        float m01 = matrix.m01();
        float m02 = matrix.m02();
        float m10 = matrix.m10();
        float m11 = matrix.m11();
        float m12 = matrix.m12();
        float m20 = matrix.m20();
        float m21 = matrix.m21();
        float m22 = matrix.m22();
        float m30 = matrix.m30();
        float m31 = matrix.m31();
        float m32 = matrix.m32();

        float n00 = normalMatrix.m00();
        float n01 = normalMatrix.m01();
        float n02 = normalMatrix.m02();
        float n10 = normalMatrix.m10();
        float n11 = normalMatrix.m11();
        float n12 = normalMatrix.m12();
        float n20 = normalMatrix.m20();
        float n21 = normalMatrix.m21();
        float n22 = normalMatrix.m22();

        // The custom-geometry buffer of entity RenderTypes is fed as QUADS, not
        // as a triangle list. Writing three vertices per triangle made the
        // consumer group them four at a time, so every quad pulled in a vertex
        // belonging to a different triangle. That is what shredded the model
        // into webbing and threw long slivers across the screen. Each triangle
        // is therefore emitted as a degenerate quad whose fourth corner repeats
        // the third, which rasterises as the exact triangle it was baked from.
        for (int i = start; i + 2 < end; i += 3) {
            for (int k = 0; k < 4; k++) {
                // Quad corner 4 re-uses corner 3 so the quad collapses onto the
                // triangle instead of adding a sliver of its own.
                int vertex = indices[i + (k < 3 ? k : 2)];
                int p3 = vertex * 3;
                int p2 = vertex * 2;

                float x = positions[p3];
                float y = positions[p3 + 1];
                float z = positions[p3 + 2];

                float wx = m00 * x + m10 * y + m20 * z + m30;
                float wy = m01 * x + m11 * y + m21 * z + m31;
                float wz = m02 * x + m12 * y + m22 * z + m32;

                float nx = normals[p3];
                float ny = normals[p3 + 1];
                float nz = normals[p3 + 2];
                float tx = n00 * nx + n10 * ny + n20 * nz;
                float ty = n01 * nx + n11 * ny + n21 * nz;
                float tz = n02 * nx + n12 * ny + n22 * nz;
                float len = tx * tx + ty * ty + tz * tz;
                if (len > 1.0E-8F) {
                    float inv = (float) (1.0 / Math.sqrt(len));
                    tx *= inv;
                    ty *= inv;
                    tz *= inv;
                }

                buffer.addVertex(wx, wy, wz, tint,
                        uvs[p2], uvs[p2 + 1], overlay, light, tx, ty, tz);
            }
        }
    }
}
