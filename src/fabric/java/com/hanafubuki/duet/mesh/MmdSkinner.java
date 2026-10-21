package com.hanafubuki.duet.mesh;

/**
 * Linear blend skinning for a baked {@link MmdModel}.
 *
 * <p>The base mesh is never modified. Positions are morphed (real MMD vertex
 * morphs, so the mouth actually reshapes instead of hinging), then skinned
 * into a reusable output buffer. Work is skipped entirely when neither the
 * pose nor the morph weights changed since the last call, which is what keeps
 * a 60k vertex character cheap while the entity stands still.
 */
public final class MmdSkinner {

    private final MmdModel model;
    private final Skeleton skeleton;

    private final float[] morphPositions;
    private final float[] outPositions;
    private final float[] outNormals;

    private final MmdModel.Morph openMorph;
    private final MmdModel.Morph wideMorph;
    private final MmdModel.Morph roundMorph;
    private final MmdModel.Morph blinkMorph;

    private float lastOpen = Float.NaN;
    private float lastWide = Float.NaN;
    private float lastRound = Float.NaN;
    private float lastBlink = Float.NaN;
    private long lastPoseVersion = Long.MIN_VALUE;
    private boolean dirty = true;

    public MmdSkinner(MmdModel model, Skeleton skeleton) {
        this.model = model;
        this.skeleton = skeleton;
        this.morphPositions = new float[model.positions.length];
        this.outPositions = new float[model.positions.length];
        this.outNormals = new float[model.normals.length];
        this.openMorph = model.morph("open");
        this.wideMorph = model.morph("wide");
        this.roundMorph = model.morph("round");
        this.blinkMorph = model.morph("blink");
    }

    public float[] positions() {
        return outPositions;
    }

    public float[] normals() {
        return outNormals;
    }

    /**
     * Re-skins if the pose or morph weights changed.
     *
     * @param poseVersion caller supplied counter that changes whenever the
     *                    skeleton was re-posed this frame
     */
    public void update(long poseVersion, float open, float wide, float round, float blink) {
        float o = clamp01(open);
        float wi = clamp01(wide);
        float r = clamp01(round);
        float b = clamp01(blink);
        if (!dirty && poseVersion == lastPoseVersion
                && o == lastOpen && wi == lastWide && r == lastRound && b == lastBlink) {
            return;
        }
        lastOpen = o;
        lastWide = wi;
        lastRound = r;
        lastBlink = b;
        lastPoseVersion = poseVersion;
        dirty = false;

        float[] src = model.positions;
        if (o > 0.001f || wi > 0.001f || r > 0.001f || b > 0.001f) {
            System.arraycopy(model.positions, 0, morphPositions, 0, model.positions.length);
            applyMorph(openMorph, o);
            applyMorph(wideMorph, wi);
            applyMorph(roundMorph, r);
            applyMorph(blinkMorph, b);
            src = morphPositions;
        }
        skin(src);
    }

    private void applyMorph(MmdModel.Morph morph, float weight) {
        if (morph == null || weight <= 0.001f) {
            return;
        }
        int[] vi = morph.vertex;
        for (int k = 0; k < vi.length; k++) {
            int base = vi[k] * 3;
            morphPositions[base] += weight * morph.dx[k];
            morphPositions[base + 1] += weight * morph.dy[k];
            morphPositions[base + 2] += weight * morph.dz[k];
        }
    }

    private void skin(float[] src) {
        float[] rot = skeleton.rot;
        float[] shift = skeleton.shift;
        int[] bones = model.boneIndices;
        float[] weights = model.boneWeights;
        byte[] count = model.weightCount;
        float[] baseNrm = model.normals;
        int nv = model.vertexCount;

        for (int i = 0; i < nv; i++) {
            int i3 = i * 3;
            int i4 = i * 4;
            float x = src[i3];
            float y = src[i3 + 1];
            float z = src[i3 + 2];
            float nx = baseNrm[i3];
            float ny = baseNrm[i3 + 1];
            float nz = baseNrm[i3 + 2];

            int n = count[i];
            if (n == 1) {
                int o = bones[i4] * 9;
                int so = bones[i4] * 3;
                float r0 = rot[o];
                float r1 = rot[o + 1];
                float r2 = rot[o + 2];
                float r3 = rot[o + 3];
                float r4 = rot[o + 4];
                float r5 = rot[o + 5];
                float r6 = rot[o + 6];
                float r7 = rot[o + 7];
                float r8 = rot[o + 8];
                outPositions[i3] = r0 * x + r1 * y + r2 * z + shift[so];
                outPositions[i3 + 1] = r3 * x + r4 * y + r5 * z + shift[so + 1];
                outPositions[i3 + 2] = r6 * x + r7 * y + r8 * z + shift[so + 2];
                outNormals[i3] = r0 * nx + r1 * ny + r2 * nz;
                outNormals[i3 + 1] = r3 * nx + r4 * ny + r5 * nz;
                outNormals[i3 + 2] = r6 * nx + r7 * ny + r8 * nz;
                continue;
            }

            float px = 0f;
            float py = 0f;
            float pz = 0f;
            float qx = 0f;
            float qy = 0f;
            float qz = 0f;
            for (int k = 0; k < n; k++) {
                int bone = bones[i4 + k];
                float wgt = weights[i4 + k];
                if (wgt <= 0f) {
                    continue;
                }
                int o = bone * 9;
                int so = bone * 3;
                float r0 = rot[o];
                float r1 = rot[o + 1];
                float r2 = rot[o + 2];
                float r3 = rot[o + 3];
                float r4 = rot[o + 4];
                float r5 = rot[o + 5];
                float r6 = rot[o + 6];
                float r7 = rot[o + 7];
                float r8 = rot[o + 8];
                px += wgt * (r0 * x + r1 * y + r2 * z + shift[so]);
                py += wgt * (r3 * x + r4 * y + r5 * z + shift[so + 1]);
                pz += wgt * (r6 * x + r7 * y + r8 * z + shift[so + 2]);
                qx += wgt * (r0 * nx + r1 * ny + r2 * nz);
                qy += wgt * (r3 * nx + r4 * ny + r5 * nz);
                qz += wgt * (r6 * nx + r7 * ny + r8 * nz);
            }
            outPositions[i3] = px;
            outPositions[i3 + 1] = py;
            outPositions[i3 + 2] = pz;
            outNormals[i3] = qx;
            outNormals[i3 + 1] = qy;
            outNormals[i3 + 2] = qz;
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
