package com.hanafubuki.duet.client;

import com.hanafubuki.duet.mesh.Animator;
import com.hanafubuki.duet.mesh.MmdModel;
import com.hanafubuki.duet.mesh.MmdSkinner;
import com.hanafubuki.duet.mesh.Skeleton;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-entity animation state for one baked character.
 *
 * <p>The immutable mesh and its render types are shared between every instance
 * of the same character; only the skeleton, the animation buffers and the
 * morph weights are per entity. Keeping the buffers per entity matters: two
 * characters of the same type on screen must never share a skinning buffer, or
 * one would be drawn wearing the other's pose.
 */
final class MmdRig {

    private static final Logger LOGGER = LoggerFactory.getLogger("mmdduet");
    private static final Map<String, Assets> CACHE = new HashMap<>();

    /** Immutable, shareable half of a character. */
    static final class Assets {
        final MmdModel model;
        final String tag;
        private final RenderType[] renderTypes;

        private Assets(MmdModel model, String tag) {
            this.model = model;
            this.tag = tag;
            this.renderTypes = new RenderType[Math.max(1, model.textureFiles.length)];
        }

        ResourceLocation texture(int index) {
            if (index < 0 || index >= this.model.textureFiles.length) {
                index = 0;
            }
            return new ResourceLocation("mmdduet",
                    "entity/" + this.tag + "/tex/" + this.model.textureFiles[index]);
        }

        RenderType renderType(int textureIndex) {
            if (textureIndex < 0 || textureIndex >= this.renderTypes.length) {
                textureIndex = 0;
            }
            RenderType cached = this.renderTypes[textureIndex];
            if (cached == null) {
                // entityCutout keeps the alpha cut-out for hair and eyes while
                // drawing each material with its own full resolution texture.
                cached = RenderType.entityCutout(this.texture(textureIndex));
                this.renderTypes[textureIndex] = cached;
            }
            return cached;
        }
    }

    /** Returns the shared assets for a character tag, or null if unloadable. */
    static Assets assets(String tag) {
        Assets cached = CACHE.get(tag);
        if (cached != null) {
            return cached;
        }
        try {
            MmdModel model = MmdModel.load(tag);
            Assets loaded = new Assets(model, tag);
            CACHE.put(tag, loaded);
            LOGGER.info("mmdduet: loaded {} - {} vertices, {} triangles, {} materials, {} textures",
                    tag, model.vertexCount, model.triangleCount(),
                    model.groups.length, model.textureFiles.length);
            return loaded;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("mmdduet: could not load character '{}'", tag, e);
            return null;
        }
    }

    final Assets assets;
    final Skeleton skeleton;
    final Animator animator;
    final MmdSkinner skinner;

    private float singBlend;
    private long poseVersion;
    int lastFrame;

    MmdRig(Assets assets) {
        this.assets = assets;
        this.skeleton = new Skeleton(assets.model);
        this.animator = new Animator(assets.model, this.skeleton);
        this.skinner = new MmdSkinner(assets.model, this.skeleton);
    }

    void animate(SingerState state) {
        float target = state.performanceSeconds >= 0.0F ? 1.0F : 0.0F;
        this.singBlend += (target - this.singBlend) * 0.10F;
        if (this.singBlend < 0.002F) {
            this.singBlend = 0.0F;
        } else if (this.singBlend > 0.998F) {
            this.singBlend = 1.0F;
        }

        this.animator.apply(
                state.gaitPhase,
                state.walkWeight,
                state.idleSeconds,
                // state.yRot is already the head yaw *relative to the body*.
                // The mesh is drawn turned by (180 - bodyRot), so a local
                // rotation of s about Y puts the head at (180 - bodyRot + s);
                // matching that to the wanted head direction gives
                // s = bodyRot - headRot = -yRot. Subtracting bodyRot again here
                // instead turned the head by roughly twice the body turn, which
                // read as the head looking over the character's shoulder or
                // straight backwards.
                -state.yRot,
                state.xRot,
                this.singBlend,
                Math.max(0.0F, state.performanceSeconds),
                // Which half of the duet this character owns. The two singers
                // have deliberately different stage business, so the animator
                // has to know which one it is posing.
                state.leadSinger,
                // Combat channel, -1 when idle. The kick owns the right leg and
                // adds a whole-body counterweight on top of the gait, so it has
                // to reach the animator rather than being layered afterwards.
                state.kickProgress);

        this.poseVersion++;
        this.skinner.update(this.poseVersion,
                state.mouthOpen, state.mouthWide, state.mouthRound, state.blink);
    }
}
