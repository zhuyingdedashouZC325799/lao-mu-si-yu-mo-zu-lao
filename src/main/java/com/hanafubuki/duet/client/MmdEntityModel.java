package com.hanafubuki.duet.client;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Placeholder model required by {@code MobRenderer}'s type parameters.
 *
 * <p>The real character is not a box rig, so nothing is ever drawn through
 * this model: {@link SingerRenderer} emits the skinned MMD geometry itself
 * through the submit-node collector. Keeping the model empty also means the
 * vanilla model pipeline never touches our textures.
 */
public final class MmdEntityModel extends EntityModel<SingerState> {

    public MmdEntityModel() {
        super(new ModelPart(List.of(), Map.of()));
    }
}
