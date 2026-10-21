package com.hanafubuki.duet.mesh;

/**
 * Runtime pose for the full MMD skeleton.
 *
 * <p>Every authored bone gets a slot, keeping the original parent links, so a
 * rotation on the upper arm carries the twist bones, the sleeve chain and the
 * hand exactly the way the modeller intended. Poses are authored as per-bone
 * local rotations plus optional local translations, then expanded once per
 * frame into the data the skinning pass needs.
 *
 * <p>Skinning identity: a vertex bound to bone {@code j} maps to
 * {@code R[j] * (v - bind[j]) + pivot[j]}, which is exactly
 * {@code R[j] * v + shift[j]} with {@code shift[j] = pivot[j] - R[j] * bind[j]}.
 */
public final class Skeleton {

    public final int boneCount;

    private final float[] bind;
    private final int[] parent;
    /** Bone indices ordered so a parent always precedes its children. */
    private final int[] order;

    private final float[] rotX;
    private final float[] rotY;
    private final float[] rotZ;
    private final float[] offX;
    private final float[] offY;
    private final float[] offZ;

    /** Accumulated rotation per bone, row-major 3x3. */
    public final float[] rot;
    /** {@code pivot - R * bind} per bone, the skinning translation. */
    public final float[] shift;
    /** Posed pivot position per bone, in model units. */
    public final float[] pivot;

    /** Resolved animation roles, -1 when the model has no such bone. */
    public final int center;
    public final int lower;
    public final int upper;
    public final int upper1;
    public final int chest;
    public final int neck;
    public final int head;
    public final int shoulderR;
    public final int shoulderL;
    public final int armR;
    public final int armL;
    public final int elbowR;
    public final int elbowL;
    public final int wristR;
    public final int wristL;
    public final int thighR;
    public final int thighL;
    public final int kneeR;
    public final int kneeL;
    public final int ankleR;
    public final int ankleL;

    public Skeleton(MmdModel model) {
        int n = Math.max(1, model.boneCount);
        this.boneCount = n;
        this.bind = new float[n * 3];
        System.arraycopy(model.boneBind, 0, this.bind, 0, Math.min(model.boneBind.length, n * 3));

        this.parent = new int[n];
        System.arraycopy(model.boneParent, 0, this.parent, 0, Math.min(model.boneParent.length, n));
        for (int i = 0; i < n; i++) {
            int p = this.parent[i];
            if (p < 0 || p >= n || p == i) {
                this.parent[i] = -1;
            }
        }
        this.order = topological(this.parent, n);

        this.rotX = new float[n];
        this.rotY = new float[n];
        this.rotZ = new float[n];
        this.offX = new float[n];
        this.offY = new float[n];
        this.offZ = new float[n];
        this.rot = new float[n * 9];
        this.shift = new float[n * 3];
        this.pivot = new float[n * 3];

        this.center = model.role(MmdModel.ROLE_CENTER);
        this.lower = model.role(MmdModel.ROLE_LOWER);
        this.upper = model.role(MmdModel.ROLE_UPPER);
        this.upper1 = model.role(MmdModel.ROLE_UPPER1);
        this.chest = model.role(MmdModel.ROLE_CHEST);
        this.neck = model.role(MmdModel.ROLE_NECK);
        this.head = model.role(MmdModel.ROLE_HEAD);
        this.shoulderR = model.role(MmdModel.ROLE_SHOULDER_R);
        this.shoulderL = model.role(MmdModel.ROLE_SHOULDER_L);
        this.armR = model.role(MmdModel.ROLE_ARM_R);
        this.armL = model.role(MmdModel.ROLE_ARM_L);
        this.elbowR = model.role(MmdModel.ROLE_ELBOW_R);
        this.elbowL = model.role(MmdModel.ROLE_ELBOW_L);
        this.wristR = model.role(MmdModel.ROLE_WRIST_R);
        this.wristL = model.role(MmdModel.ROLE_WRIST_L);
        this.thighR = model.role(MmdModel.ROLE_THIGH_R);
        this.thighL = model.role(MmdModel.ROLE_THIGH_L);
        this.kneeR = model.role(MmdModel.ROLE_KNEE_R);
        this.kneeL = model.role(MmdModel.ROLE_KNEE_L);
        this.ankleR = model.role(MmdModel.ROLE_ANKLE_R);
        this.ankleL = model.role(MmdModel.ROLE_ANKLE_L);
    }

    /**
     * Breadth-first expansion of the parent links.
     *
     * <p>PMX does not promise that a bone index is greater than its parent's,
     * so the order has to be computed rather than assumed. Bones left over sit
     * in a parent cycle; they are promoted to roots so the single forward pass
     * in {@link #compute()} stays valid.
     */
    private static int[] topological(int[] parent, int n) {
        int[] childStart = new int[n + 1];
        for (int i = 0; i < n; i++) {
            int p = parent[i];
            if (p >= 0) {
                childStart[p + 1]++;
            }
        }
        for (int i = 0; i < n; i++) {
            childStart[i + 1] += childStart[i];
        }
        int[] fill = new int[n];
        System.arraycopy(childStart, 0, fill, 0, n);
        int[] child = new int[n];
        for (int i = 0; i < n; i++) {
            int p = parent[i];
            if (p >= 0) {
                child[fill[p]++] = i;
            }
        }

        int[] order = new int[n];
        int[] queue = new int[n];
        boolean[] seen = new boolean[n];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < n; i++) {
            if (parent[i] < 0) {
                seen[i] = true;
                queue[tail++] = i;
            }
        }
        int filled = 0;
        while (head < tail) {
            int i = queue[head++];
            order[filled++] = i;
            for (int k = childStart[i]; k < childStart[i + 1]; k++) {
                int c = child[k];
                if (!seen[c]) {
                    seen[c] = true;
                    queue[tail++] = c;
                }
            }
        }
        for (int i = 0; i < n && filled < n; i++) {
            if (!seen[i]) {
                parent[i] = -1;
                order[filled++] = i;
            }
        }
        return order;
    }

    public float bindX(int slot) {
        return (slot < 0 || slot >= this.boneCount) ? 0f : this.bind[slot * 3];
    }

    public float bindY(int slot) {
        return (slot < 0 || slot >= this.boneCount) ? 0f : this.bind[slot * 3 + 1];
    }

    public float bindZ(int slot) {
        return (slot < 0 || slot >= this.boneCount) ? 0f : this.bind[slot * 3 + 2];
    }

    public float pivotX(int slot) {
        return (slot < 0 || slot >= this.boneCount) ? 0f : this.pivot[slot * 3];
    }

    public float pivotY(int slot) {
        return (slot < 0 || slot >= this.boneCount) ? 0f : this.pivot[slot * 3 + 1];
    }

    public float pivotZ(int slot) {
        return (slot < 0 || slot >= this.boneCount) ? 0f : this.pivot[slot * 3 + 2];
    }

    /** Clears the pose back to bind. */
    public void reset() {
        java.util.Arrays.fill(this.rotX, 0f);
        java.util.Arrays.fill(this.rotY, 0f);
        java.util.Arrays.fill(this.rotZ, 0f);
        java.util.Arrays.fill(this.offX, 0f);
        java.util.Arrays.fill(this.offY, 0f);
        java.util.Arrays.fill(this.offZ, 0f);
    }

    public void setRotation(int slot, float x, float y, float z) {
        if (slot < 0 || slot >= this.boneCount) {
            return;
        }
        this.rotX[slot] = x;
        this.rotY[slot] = y;
        this.rotZ[slot] = z;
    }

    public void addRotation(int slot, float x, float y, float z) {
        if (slot < 0 || slot >= this.boneCount) {
            return;
        }
        this.rotX[slot] += x;
        this.rotY[slot] += y;
        this.rotZ[slot] += z;
    }

    public void setOffset(int slot, float x, float y, float z) {
        if (slot < 0 || slot >= this.boneCount) {
            return;
        }
        this.offX[slot] = x;
        this.offY[slot] = y;
        this.offZ[slot] = z;
    }

    public void addOffset(int slot, float x, float y, float z) {
        if (slot < 0 || slot >= this.boneCount) {
            return;
        }
        this.offX[slot] += x;
        this.offY[slot] += y;
        this.offZ[slot] += z;
    }

    /**
     * Expands the local pose into per-bone rotations, pivots and skinning
     * translations, in one pass over {@link #order}.
     */
    public void compute() {
        int n = this.boneCount;
        float[] rot = this.rot;
        float[] shift = this.shift;
        float[] pivot = this.pivot;
        float[] bind = this.bind;
        int[] parent = this.parent;

        for (int oi = 0; oi < n; oi++) {
            int i = this.order[oi];
            float rx = this.rotX[i];
            float ry = this.rotY[i];
            float rz = this.rotZ[i];
            int o = i * 9;

            float cx = (float) Math.cos(rx);
            float sx = (float) Math.sin(rx);
            float cy = (float) Math.cos(ry);
            float sy = (float) Math.sin(ry);
            float cz = (float) Math.cos(rz);
            float sz = (float) Math.sin(rz);

            // local = Ry * Rx * Rz
            float l00 = cy * cz + sy * sx * sz;
            float l01 = -cy * sz + sy * sx * cz;
            float l02 = sy * cx;
            float l10 = cx * sz;
            float l11 = cx * cz;
            float l12 = -sx;
            float l20 = -sy * cz + cy * sx * sz;
            float l21 = sy * sz + cy * sx * cz;
            float l22 = cy * cx;

            float tx = this.offX[i];
            float ty = this.offY[i];
            float tz = this.offZ[i];

            int p = parent[i];
            float px;
            float py;
            float pz;
            if (p < 0) {
                rot[o] = l00;
                rot[o + 1] = l01;
                rot[o + 2] = l02;
                rot[o + 3] = l10;
                rot[o + 4] = l11;
                rot[o + 5] = l12;
                rot[o + 6] = l20;
                rot[o + 7] = l21;
                rot[o + 8] = l22;
                px = bind[i * 3] + tx;
                py = bind[i * 3 + 1] + ty;
                pz = bind[i * 3 + 2] + tz;
            } else {
                int po = p * 9;
                float p00 = rot[po];
                float p01 = rot[po + 1];
                float p02 = rot[po + 2];
                float p10 = rot[po + 3];
                float p11 = rot[po + 4];
                float p12 = rot[po + 5];
                float p20 = rot[po + 6];
                float p21 = rot[po + 7];
                float p22 = rot[po + 8];

                rot[o] = p00 * l00 + p01 * l10 + p02 * l20;
                rot[o + 1] = p00 * l01 + p01 * l11 + p02 * l21;
                rot[o + 2] = p00 * l02 + p01 * l12 + p02 * l22;
                rot[o + 3] = p10 * l00 + p11 * l10 + p12 * l20;
                rot[o + 4] = p10 * l01 + p11 * l11 + p12 * l21;
                rot[o + 5] = p10 * l02 + p11 * l12 + p12 * l22;
                rot[o + 6] = p20 * l00 + p21 * l10 + p22 * l20;
                rot[o + 7] = p20 * l01 + p21 * l11 + p22 * l21;
                rot[o + 8] = p20 * l02 + p21 * l12 + p22 * l22;

                // Offset of this bone's pivot from the parent pivot, expressed
                // in the parent's frame.
                float dx = bind[i * 3] - bind[p * 3] + tx;
                float dy = bind[i * 3 + 1] - bind[p * 3 + 1] + ty;
                float dz = bind[i * 3 + 2] - bind[p * 3 + 2] + tz;

                px = pivot[p * 3] + p00 * dx + p01 * dy + p02 * dz;
                py = pivot[p * 3 + 1] + p10 * dx + p11 * dy + p12 * dz;
                pz = pivot[p * 3 + 2] + p20 * dx + p21 * dy + p22 * dz;
            }

            pivot[i * 3] = px;
            pivot[i * 3 + 1] = py;
            pivot[i * 3 + 2] = pz;

            float bx = bind[i * 3];
            float by = bind[i * 3 + 1];
            float bz = bind[i * 3 + 2];
            shift[i * 3] = px - (rot[o] * bx + rot[o + 1] * by + rot[o + 2] * bz);
            shift[i * 3 + 1] = py - (rot[o + 3] * bx + rot[o + 4] * by + rot[o + 5] * bz);
            shift[i * 3 + 2] = pz - (rot[o + 6] * bx + rot[o + 7] * by + rot[o + 8] * bz);
        }
    }
}
