package com.hanafubuki.duet.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Amplitude envelope of the duet track, sampled by the baker at 60 Hz.
 *
 * <p>Drives the lip sync, so the mouth actually follows the sung syllables
 * instead of cycling blindly.
 */
public final class AudioLevels {

    private static float[] levels = new float[0];
    private static float fps = 60.0F;
    private static float duration = 0.0F;
    private static boolean loaded;
    private static boolean failed;

    private AudioLevels() {
    }

    private static void ensureLoaded() {
        if (loaded || failed) {
            return;
        }
        loaded = true;
        try (InputStream in = AudioLevels.class.getResourceAsStream(
                "/assets/mmdduet/sounds/audio_levels.json")) {
            if (in == null) {
                failed = true;
                return;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("fps")) {
                fps = root.get("fps").getAsFloat();
            }
            if (root.has("duration")) {
                duration = root.get("duration").getAsFloat();
            }
            var arr = root.getAsJsonArray("levels");
            float[] out = new float[arr.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = arr.get(i).getAsFloat();
            }
            levels = out;
        } catch (IOException | RuntimeException e) {
            failed = true;
            levels = new float[0];
        }
    }

    public static float duration() {
        ensureLoaded();
        return duration;
    }

    /** Raw envelope value at {@code seconds}, linearly interpolated. */
    public static float levelAt(float seconds) {
        ensureLoaded();
        int n = levels.length;
        if (n == 0 || seconds < 0f) {
            return 0f;
        }
        float pos = seconds * fps;
        int i = (int) pos;
        if (i >= n - 1) {
            return 0f;
        }
        float frac = pos - i;
        float a = levels[i];
        float b = levels[i + 1];
        return a + (b - a) * frac;
    }

    /**
     * Resolves the three vowel shapes and the blink for a moment in the song.
     *
     * <p>The envelope is smoothed for the jaw opening and differentiated to
     * pick a slightly rounder or wider shape between syllables, which keeps
     * the mouth from repeating one identical pose.
     */
    public static void shapes(float seconds, float[] out4) {
        ensureLoaded();
        if (levels.length == 0 || seconds < 0f) {
            out4[0] = 0f;
            out4[1] = 0f;
            out4[2] = 0f;
            return;
        }
        float raw = levelAt(seconds);
        float smooth = (levelAt(seconds - 0.045f) + levelAt(seconds) * 2f
                + levelAt(seconds + 0.045f)) * 0.25f;
        float slope = levelAt(seconds + 0.03f) - levelAt(seconds - 0.03f);

        float open = clamp01((smooth - 0.045f) * 1.55f);
        // Opening syllables read as "a", decaying ones as "u", held ones as "i".
        float wide = clamp01(open * 0.55f + slope * 6.0f * open);
        float round = clamp01(open * 0.62f - slope * 8.0f * open);
        out4[0] = open;
        out4[1] = wide;
        out4[2] = round;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
