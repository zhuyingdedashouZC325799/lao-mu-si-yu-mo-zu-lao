package com.hanafubuki.duet.client;

import com.hanafubuki.duet.SingerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;

/**
 * Placeholder model required by {@code MobRenderer}'s type parameters.
 *
 * <p>The real character is not a box rig, so nothing is ever drawn through this
 * model: {@link SingerRenderer} emits the skinned MMD geometry itself. Keeping
 * the model empty also means the vanilla model pipeline never touches our
 * textures - the render-type lookup returns null for every texture, so vanilla
 * resolves no buffer at all and cannot draw a placeholder over the mesh.
 */
public final class MmdEntityModel extends EntityModel<SingerEntity> {

    public MmdEntityModel() {
        super(texture -> null);
    }

    @Override
    public void setupAnim(SingerEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // Intentionally empty: the animator drives the real skeleton.
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int packedLight,
                               int packedOverlay, float red, float green, float blue,
                               float alpha) {
        // Intentionally empty: no box geometry exists for this character.
    }
}
