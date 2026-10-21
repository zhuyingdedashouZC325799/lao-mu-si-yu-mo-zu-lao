package com.hanafubuki.duet.client;

import com.hanafubuki.duet.DuetMod;
import com.hanafubuki.duet.SingerEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Binds the two MMD renderers to their entity types.
 *
 * <p>Annotated with {@code Dist.CLIENT} so Forge never loads this class on a
 * dedicated server, where the renderer package does not exist at all.
 *
 * <p>The two providers are declared classes instead of lambdas on purpose. A
 * lambda is compiled to a {@code private static} method plus an
 * {@code invokedynamic} whose call-site name is the single abstract method of
 * the functional interface ({@code create}). Reobfuscation rewrites declared
 * member names by walking the superclass and interface chain of the owning
 * type, and an {@code invokedynamic} carries no owning type, so the synthetic
 * body would keep the official name {@code create} while the SRG runtime looks
 * for {@code m_174009_} - producing an {@code AbstractMethodError} the moment
 * Forge binds the renderer. A named nested class has a real method declaration
 * whose owner is this file, so the chain resolves through
 * {@link EntityRendererProvider} and the name is rewritten like any other
 * override.
 */
@Mod.EventBusSubscriber(modid = DuetMod.ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DuetClientSetup {

    private DuetClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(DuetMod.SI.get(), new SiRendererProvider());
        event.registerEntityRenderer(DuetMod.LIINO.get(), new LiinoRendererProvider());
    }

    /** Provider for the {@code si} character. */
    private static final class SiRendererProvider
            implements EntityRendererProvider<SingerEntity> {

        @Override
        public SingerRenderer create(EntityRendererProvider.Context context) {
            return new SingerRenderer(context, "si");
        }
    }

    /** Provider for the {@code liino} character. */
    private static final class LiinoRendererProvider
            implements EntityRendererProvider<SingerEntity> {

        @Override
        public SingerRenderer create(EntityRendererProvider.Context context) {
            return new SingerRenderer(context, "liino");
        }
    }
}
