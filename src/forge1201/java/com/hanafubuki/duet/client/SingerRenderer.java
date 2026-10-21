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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Draws a baked MMD character.
 *
 * <p>The vanilla box-model pipeline is bypassed entirely. The mesh is skinned on
 * the CPU in model space, then emitted once per PMX material into that
 * material's own {@link RenderType}, so every material keeps its own full
 * resolution texture and its own draw call. Real PMX UVs, one texture per
 * material, no atlas, no back-face culling: that combination is what keeps the
 * characters sharp in game.
 */
public final class SingerRenderer extends MobRenderer<SingerEntity, MmdEntityModel> {

    /**
     * Model units to blocks. Every character shares one scale so their relative
     * proportions stay exactly as authored.
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

    /**
     * Vertical shift applied to the name plate.
     *
     * <p>Vanilla anchors the label at {@code boundingBoxHeight + 0.5}, which for
     * this 1.9 block hitbox lands at 2.4 - well above the modelled head, because
     * the MMD mesh is only about 1.7 blocks tall. 1.20.1's
     * {@code EntityType.Builder} has no {@code nameTagOffset}, so the shift is
     * applied here instead.
     */
    private static final float NAMETAG_SHIFT = 1.70F - (1.9F + 0.5F);

    /** Development-only rotation trace, enabled with {@code -Dmmdduet.probe}. */
    private static final boolean PROBE = System.getProperty("mmdduet.probe") != null;

    private final MmdRig.Assets assets;
    private final ResourceLocation fallbackTexture;
    private final Map<Integer, MmdRig> rigs = new HashMap<>();
    /** Scratch state reused every frame; rendering is single threaded. */
    private final SingerState state = new SingerState();
    private int frame;

    public SingerRenderer(EntityRendererProvider.Context context, String tag) {
        super(context, new MmdEntityModel(), 0.5F);
        this.assets = MmdRig.assets(tag);
        this.fallbackTexture = this.assets != null
                ? this.assets.texture(0)
                : new ResourceLocation("mmdduet", "textures/missing.png");
    }

    @Override
    public ResourceLocation getTextureLocation(SingerEntity entity) {
        // Only ever used by the empty placeholder model's render-type lookup,
        // which returns null anyway; the real textures are bound per material.
        return this.fallbackTexture;
    }

    /**
     * The named characters always show their label.
     *
     * <p>Vanilla re-reads {@code CustomNameVisible} from entity NBT while the
     * entity is being placed, which silently flipped the flag back to false
     * after every summon. Answering the query directly is immune to that.
     */
    @Override
    protected boolean shouldShowName(SingerEntity entity) {
        return true;
    }

    @Override
    protected void renderNameTag(SingerEntity entity, Component text, PoseStack pose,
                                 MultiBufferSource buffer, int packedLight) {
        pose.pushPose();
        pose.translate(0.0, NAMETAG_SHIFT, 0.0);
        super.renderNameTag(entity, text, pose, buffer, packedLight);
        pose.popPose();
    }

    @Override
    public void render(SingerEntity entity, float entityYaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffer, int packedLight) {
        // The mesh first, then the vanilla pass so the name plate lands on top
        // of it. The vanilla pass's own model is empty and costs no draw call,
        // but it still owns the shadow and the label.
        this.drawMesh(entity, partialTick, pose, buffer, packedLight);
        super.render(entity, entityYaw, partialTick, pose, buffer, packedLight);
    }

    private void drawMesh(SingerEntity entity, float partialTick, PoseStack pose,
                          MultiBufferSource buffer, int packedLight) {
        if (this.assets == null || entity.isInvisible()) {
            return;
        }

        SingerState st = this.state;
        st.singing = entity.isSinging();
        // Which half of the duet this character owns. 祀 leads and 梨诺 answers,
        // matching the reference duet's split between the driving singer and the
        // softer one; the animator poses the two differently.
        st.leadSinger = "si".equals(entity.variant());
        st.gaitPhase = entity.gaitPhase(partialTick);
        st.walkWeight = entity.walkWeight(partialTick);
        st.ageInTicks = entity.tickCount + partialTick;
        st.idleSeconds = st.ageInTicks * 0.05F;
        st.performanceSeconds = DuetClient.performanceSeconds(entity, partialTick);
        // Combat pose channel. Read from the entity's client-side kick clock so
        // the boot moves on every rendered frame instead of stepping with the
        // synced sequence counter.
        st.kickProgress = entity.kickProgress(partialTick);

        // Body yaw and the head's offset from it, interpolated exactly the way
        // vanilla's humanoid renderer does it, so the animator receives the same
        // numbers it received from the render state in the 26.x builds.
        st.bodyRot = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float headRot = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot);
        st.yRot = headRot - st.bodyRot;
        st.xRot = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());

        if (PROBE && entity.tickCount % 20 == 0) {
            System.out.println("[mmdrot] " + entity.variant()
                    + " tick=" + entity.tickCount
                    + " body=" + st.bodyRot
                    + " head=" + st.yRot
                    + " pose=" + (YAW_OFFSET + YAW_SIGN * st.bodyRot)
                    + " walk=" + st.walkWeight
                    + " gait=" + st.gaitPhase
                    + " duet=" + (st.singing ? 1 : 0)
                    + " perf=" + st.performanceSeconds);
        }

        float open = 0.0F;
        float wide = 0.0F;
        float round = 0.0F;
        if (st.performanceSeconds >= 0.0F) {
            // Only the singer whose turn it is moves its mouth. Without this
            // both characters mime the whole track, which reads immediately as
            // a broken lip sync because the wrong one is mouthing the words.
            float turn = Performance.activityAt(st.performanceSeconds, st.leadSinger);
            if (turn > 0.001F) {
                float[] shapes = SHAPES.get();
                AudioLevels.shapes(st.performanceSeconds, shapes);
                // The reference scales the jaw by 0.15..1.0 of the envelope so
                // the mouth keeps a little life between syllables instead of
                // clamping shut on every gap.
                float scale = turn * (0.15F + 0.85F * AudioLevels.levelAt(st.performanceSeconds));
                open = shapes[0] * scale;
                wide = shapes[1] * turn;
                round = shapes[2] * turn;
            }
        }
        st.mouthOpen = open;
        st.mouthWide = wide;
        st.mouthRound = round;

        float blinkClock = st.ageInTicks * 0.043F + entity.getId() * 0.37F;
        float phase = blinkClock - (float) Math.floor(blinkClock);
        st.blink = phase > 0.965F
                ? (float) Math.sin((phase - 0.965F) / 0.035F * Math.PI)
                : 0.0F;

        MmdRig rig = this.rigFor(entity);
        st.rig = rig;
        rig.animate(st);

        int overlay = LivingEntityRenderer.getOverlayCoords(
                entity, this.getWhiteOverlayProgress(entity, partialTick));

        MmdModel model = rig.assets.model;
        float[] positions = rig.skinner.positions();
        float[] normals = rig.skinner.normals();
        float[] uvs = model.uvs;
        int[] indices = model.indices;
        MmdModel.Group[] groups = model.groups;

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(YAW_OFFSET + YAW_SIGN * st.bodyRot));
        pose.scale(SCALE, SCALE, SCALE);

        PoseStack.Pose entry = pose.last();
        Matrix4f matrix = entry.pose();
        Matrix3f normalMatrix = entry.normal();

        for (int gi = 0; gi < groups.length; gi++) {
            MmdModel.Group group = groups[gi];
            if (group.count <= 0 || group.alpha < 0.05F) {
                continue;
            }
            // One buffer per PMX material: no atlas, so no bleeding between
            // materials and no downscaling of any texture.
            VertexConsumer vc = buffer.getBuffer(rig.assets.renderType(group.texture));
            int start = group.start;
            int end = group.start + group.count;
            for (int i = start; i + 2 < end; i += 3) {
                for (int k = 0; k < 4; k++) {
                    // The entity RenderTypes are fed as QUADS, not as a triangle
                    // list. Writing three vertices per triangle made the
                    // consumer group them four at a time, so every quad pulled
                    // in a vertex belonging to a different triangle - that is
                    // what shredded the model into webbing. Each triangle is
                    // therefore emitted as a degenerate quad whose fourth
                    // corner repeats the third, which rasterises as the exact
                    // triangle it was baked from.
                    int vertex = indices[i + (k < 3 ? k : 2)];
                    int p3 = vertex * 3;
                    int p2 = vertex * 2;
                    vc.vertex(matrix, positions[p3], positions[p3 + 1], positions[p3 + 2])
                            .color(255, 255, 255, 255)
                            .uv(uvs[p2], uvs[p2 + 1])
                            .overlayCoords(overlay)
                            .uv2(packedLight)
                            .normal(normalMatrix, normals[p3], normals[p3 + 1], normals[p3 + 2])
                            .endVertex();
                }
            }
        }
        pose.popPose();
    }

    private MmdRig rigFor(SingerEntity entity) {
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
        return rig;
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
}
