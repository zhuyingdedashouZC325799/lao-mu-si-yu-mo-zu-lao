package com.hanafubuki.duet.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Track-synchronised performance data for the duet.
 *
 * <p>This is the part of the reference mod that is worth copying exactly: not
 * its motion, which was tracked from its own video and belongs to its own
 * choreography, but the way a turn is shaped. Each singer owns alternating
 * stretches of the song; inside a stretch the singer's pose rises over a few
 * tenths of a second, holds, and falls again just before the other one starts.
 * On top of that a single accent channel - the vocal envelope minus its own
 * local average, squashed through a tanh - pumps the arms on every stressed
 * syllable, so the gesture is driven by the music instead of by a metronome.
 *
 * <p>The data itself is baked from our own track by {@code tools/bake_perf.py},
 * so nothing here is specific to the reference song.
 */
public final class Performance {

    /** One singer's turn. */
    public record Cue(float start, float end, boolean si) {
    }

    private static final Cue[] CUES;
    private static final float[] THRUST;
    private static final float[] SWAY;
    private static final float FPS;
    private static final float DURATION;
    private static final float FADE;

    static {
        Cue[] cues = new Cue[0];
        float[] thrust = new float[]{0.5f};
        float[] sway = new float[]{0f};
        float fps = 60f;
        float duration = 60f;
        float fade = 0.14f;
        try (InputStream in = Performance.class.getResourceAsStream(
                "/assets/mmdduet/sounds/performance.json")) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                fps = root.get("fps").getAsFloat();
                duration = root.get("duration").getAsFloat();
                fade = root.has("fade_seconds") ? root.get("fade_seconds").getAsFloat() : fade;
                JsonArray arr = root.getAsJsonArray("cues");
                Cue[] parsed = new Cue[arr.size()];
                for (int i = 0; i < parsed.length; i++) {
                    JsonObject c = arr.get(i).getAsJsonObject();
                    parsed[i] = new Cue(c.get("start").getAsFloat(),
                            c.get("end").getAsFloat(),
                            "si".equals(c.get("speaker").getAsString()));
                }
                cues = parsed;
                thrust = read(root.getAsJsonArray("thrust"), 0.5f);
                sway = read(root.getAsJsonArray("sway"), 0f);
            }
        } catch (RuntimeException | java.io.IOException e) {
            // A missing or corrupt track must not take the renderer down; the
            // performance simply degrades to a held pose.
        }
        CUES = cues;
        THRUST = thrust;
        SWAY = sway;
        FPS = fps;
        DURATION = duration;
        FADE = fade;
    }

    private Performance() {
    }

    private static float[] read(JsonArray arr, float fallback) {
        if (arr == null || arr.isEmpty()) {
            return new float[]{fallback};
        }
        float[] out = new float[arr.size()];
        for (int i = 0; i < out.length; i++) {
            JsonElement e = arr.get(i);
            out[i] = e.getAsFloat();
        }
        return out;
    }

    public static float duration() {
        return DURATION;
    }

    /** Wrap into one pass of the song, so the track can loop. */
    private static float wrap(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0f || DURATION <= 0f) {
            return 0f;
        }
        float t = seconds % DURATION;
        return t < 0f ? t + DURATION : t;
    }

    private static float sample(float[] data, float seconds, float fps) {
        if (data.length == 0) {
            return 0f;
        }
        float pos = seconds * fps;
        int i = (int) pos;
        if (i >= data.length - 1) {
            return data[data.length - 1];
        }
        if (i < 0) {
            return data[0];
        }
        float frac = pos - i;
        return data[i] + (data[i + 1] - data[i]) * frac;
    }

    /**
     * Who is singing right now, as a 0..1 weight.
     *
     * <p>Faded at both ends of a turn, so the mouth and the gesture open and
     * close with the phrase instead of switching.
     */
    public static float activityAt(float seconds, boolean si) {
        float t = wrap(seconds);
        for (Cue cue : CUES) {
            if (cue.si() == si && t >= cue.start() && t < cue.end()) {
                float w = Math.min(1f, Math.min((t - cue.start()) / FADE,
                        (cue.end() - t) / FADE));
                return w * w * (3f - 2f * w);
            }
        }
        return 0f;
    }

    /**
     * Weight of the singing pose, as opposed to merely singing.
     *
     * <p>The pose takes a third of a second to arrive and leaves a little
     * earlier than the voice, which is what keeps the arms from stopping dead
     * on the last syllable.
     */
    public static float poseAt(float seconds, boolean si) {
        float t = wrap(seconds);
        for (Cue cue : CUES) {
            if (cue.si() == si && t >= cue.start() && t < cue.end() + 0.30f) {
                return ease(Math.min((t - cue.start()) / 0.32f,
                        (cue.end() + 0.30f - t) / 0.36f));
            }
        }
        return 0f;
    }

    /** Accent channel, 0..1, peaking on each stressed syllable. */
    public static float thrustAt(float seconds) {
        float t = wrap(seconds);
        float v = sample(THRUST, t, FPS);
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Accent channel slightly earlier, used to lead the body before the arm. */
    public static float thrustBefore(float seconds, float lead) {
        return thrustAt(wrap(seconds) - lead);
    }

    /** Slow 1..-1 sway fitted to the track, for the listening body. */
    public static float swayAt(float seconds) {
        float t = wrap(seconds);
        float v = sample(SWAY, t, FPS);
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }

    private static float ease(float v) {
        float x = v < 0f ? 0f : (v > 1f ? 1f : v);
        return x * x * x * (x * (x * 6f - 15f) + 10f);
    }
}
