package com.hanafubuki.duet.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Immutable baked MMD character, format 2.
 *
 * <p>Format 2 keeps the skeleton exactly as the PMX authored it: every bone is
 * present with its real parent, and every vertex keeps its original weights.
 * The earlier format collapsed the rig into a handful of slots and re-parented
 * unrelated bone chains onto the torso, which tore the water sleeves and the
 * hair into slivers the moment they rotated.
 *
 * <p>Loaded straight from the mod jar so no resource-system mapping can
 * silently alter it. Positions keep full float precision, UVs are the original
 * PMX values, and every material keeps its own texture slot, which is what
 * keeps the model crisp in game instead of collapsing into an atlas.
 */
public final class MmdModel {

    /** Animation roles, resolved at bake time against the real skeleton. */
    public static final int ROLE_CENTER = 0;
    public static final int ROLE_LOWER = 1;
    public static final int ROLE_UPPER = 2;
    public static final int ROLE_UPPER1 = 3;
    public static final int ROLE_CHEST = 4;
    public static final int ROLE_NECK = 5;
    public static final int ROLE_HEAD = 6;
    public static final int ROLE_SHOULDER_R = 7;
    public static final int ROLE_ARM_R = 8;
    public static final int ROLE_ELBOW_R = 9;
    public static final int ROLE_WRIST_R = 10;
    public static final int ROLE_SHOULDER_L = 11;
    public static final int ROLE_ARM_L = 12;
    public static final int ROLE_ELBOW_L = 13;
    public static final int ROLE_WRIST_L = 14;
    public static final int ROLE_THIGH_R = 15;
    public static final int ROLE_KNEE_R = 16;
    public static final int ROLE_ANKLE_R = 17;
    public static final int ROLE_THIGH_L = 18;
    public static final int ROLE_KNEE_L = 19;
    public static final int ROLE_ANKLE_L = 20;
    public static final int ROLE_COUNT = 21;

    /** Manifest key per role, in {@code ROLE_*} order. */
    public static final String[] ROLE_KEYS = {
            "center", "lower", "upper", "upper1", "chest", "neck", "head",
            "shoulder_r", "arm_r", "elbow_r", "wrist_r",
            "shoulder_l", "arm_l", "elbow_l", "wrist_l",
            "thigh_r", "knee_r", "ankle_r", "thigh_l", "knee_l", "ankle_l"
    };

    /** One material: a contiguous index run plus the single texture it uses. */
    public static final class Group {
        public final int texture;
        public final int start;
        public final int count;
        public final float alpha;
        public final boolean doubleSided;
        public final String name;

        Group(int texture, int start, int count, float alpha, boolean doubleSided, String name) {
            this.texture = texture;
            this.start = start;
            this.count = count;
            this.alpha = alpha;
            this.doubleSided = doubleSided;
            this.name = name;
        }
    }

    /** One face morph, stored as sparse vertex offsets. */
    public static final class Morph {
        public final String name;
        public final int[] vertex;
        public final float[] dx;
        public final float[] dy;
        public final float[] dz;

        Morph(String name, int[] vertex, float[] dx, float[] dy, float[] dz) {
            this.name = name;
            this.vertex = vertex;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    public final String tag;
    public final int format;
    public final int vertexCount;
    public final float[] positions;
    public final float[] normals;
    public final float[] uvs;
    /** Four bone indices per vertex, indexing the full baked skeleton. */
    public final int[] boneIndices;
    public final float[] boneWeights;
    /** Number of usable weights per vertex, 1..4. */
    public final byte[] weightCount;
    public final int[] indices;
    public final Group[] groups;
    public final String[] textureFiles;
    public final Morph[] morphs;
    /** Model height in PMX units, used to derive the render scale. */
    public final float heightUnits;

    /** Full authored skeleton. */
    public final int boneCount;
    public final String[] boneNames;
    public final int[] boneParent;
    /** Absolute bind position per bone, {@code boneCount * 3}. */
    public final float[] boneBind;
    /** Role slot to bone index, -1 when the PMX had no such bone. */
    public final int[] roles;
    /** Lowest and highest mesh vertex, in PMX units. */
    public final float yMin;
    public final float yMax;
    /** Ankle height in bind pose, i.e. the model's ground plane. */
    public final float soleY;

    private MmdModel(String tag, int format, int vertexCount, float[] positions, float[] normals,
                     float[] uvs, int[] boneIndices, float[] boneWeights, byte[] weightCount,
                     int[] indices, Group[] groups, String[] textureFiles, Morph[] morphs,
                     float heightUnits, int boneCount, String[] boneNames, int[] boneParent,
                     float[] boneBind, int[] roles, float yMin, float yMax, float soleY) {
        this.tag = tag;
        this.format = format;
        this.vertexCount = vertexCount;
        this.positions = positions;
        this.normals = normals;
        this.uvs = uvs;
        this.boneIndices = boneIndices;
        this.boneWeights = boneWeights;
        this.weightCount = weightCount;
        this.indices = indices;
        this.groups = groups;
        this.textureFiles = textureFiles;
        this.morphs = morphs;
        this.heightUnits = heightUnits;
        this.boneCount = boneCount;
        this.boneNames = boneNames;
        this.boneParent = boneParent;
        this.boneBind = boneBind;
        this.roles = roles;
        this.yMin = yMin;
        this.yMax = yMax;
        this.soleY = soleY;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    /** Bone index for an animation role, or -1 when the model lacks it. */
    public int role(int slot) {
        if (slot < 0 || slot >= this.roles.length) {
            return -1;
        }
        return this.roles[slot];
    }

    public String boneName(int index) {
        if (index < 0 || index >= this.boneCount) {
            return "<none>";
        }
        return this.boneNames[index];
    }

    public Morph morph(String name) {
        for (Morph m : this.morphs) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** Loads mesh, skeleton, textures and morphs for a baked tag. */
    public static MmdModel load(String tag) throws IOException {
        String base = "/assets/mmdduet/entity/" + tag + "/";

        JsonObject manifest;
        try (InputStream in = open(base + "manifest.json")) {
            manifest = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int format = manifest.has("format") ? manifest.get("format").getAsInt() : 1;
        if (format != 2) {
            throw new IOException("character '" + tag + "' was baked with format " + format
                    + ", but this build needs format 2");
        }

        ByteBuffer in = readAll(base + "mesh.bin");
        byte[] magic = new byte[4];
        in.get(magic);
        if (magic[0] != 'M' || magic[1] != 'M' || magic[2] != 'D' || magic[3] != 'B') {
            throw new IOException("bad mesh magic for " + tag);
        }
        int version = in.getInt();
        if (version != 2) {
            throw new IOException("unsupported mesh version " + version);
        }

        int groupCount = in.getInt();
        Group[] groups = new Group[groupCount];
        for (int i = 0; i < groupCount; i++) {
            int tex = in.getInt();
            int start = in.getInt();
            int count = in.getInt();
            float alpha = in.getFloat();
            boolean dbl = in.getInt() != 0;
            groups[i] = new Group(tex, start, count, alpha, dbl, "");
        }

        int nv = in.getInt();
        float[] pos = new float[nv * 3];
        float[] nrm = new float[nv * 3];
        float[] uv = new float[nv * 2];
        int[] bones = new int[nv * 4];
        float[] weights = new float[nv * 4];
        byte[] weightCount = new byte[nv];
        for (int i = 0; i < nv; i++) {
            pos[i * 3] = in.getFloat();
            pos[i * 3 + 1] = in.getFloat();
            pos[i * 3 + 2] = in.getFloat();
            nrm[i * 3] = in.getFloat();
            nrm[i * 3 + 1] = in.getFloat();
            nrm[i * 3 + 2] = in.getFloat();
            uv[i * 2] = in.getFloat();
            uv[i * 2 + 1] = in.getFloat();
            bones[i * 4] = in.getShort() & 0xFFFF;
            bones[i * 4 + 1] = in.getShort() & 0xFFFF;
            bones[i * 4 + 2] = in.getShort() & 0xFFFF;
            bones[i * 4 + 3] = in.getShort() & 0xFFFF;
            weights[i * 4] = in.getFloat();
            weights[i * 4 + 1] = in.getFloat();
            weights[i * 4 + 2] = in.getFloat();
            weights[i * 4 + 3] = in.getFloat();
            int used = 0;
            float total = 0f;
            for (int k = 0; k < 4; k++) {
                if (weights[i * 4 + k] > 0f) {
                    used++;
                    total += weights[i * 4 + k];
                }
            }
            if (used == 0 || total <= 0f) {
                // Never let an unweighted vertex collapse to the origin.
                bones[i * 4] = 0;
                weights[i * 4] = 1f;
                used = 1;
            }
            weightCount[i] = (byte) used;
        }

        int ni = in.getInt();
        int[] idx = new int[ni];
        for (int i = 0; i < ni; i++) {
            idx[i] = in.getInt();
        }

        // ---- full authored skeleton ------------------------------------
        int nb = in.getInt();
        String[] boneNames = new String[nb];
        int[] boneParent = new int[nb];
        float[] boneBind = new float[nb * 3];
        for (int i = 0; i < nb; i++) {
            int nameLen = in.getInt();
            byte[] raw = new byte[nameLen];
            in.get(raw);
            boneNames[i] = new String(raw, StandardCharsets.UTF_8);
            boneParent[i] = in.getInt();
            boneBind[i * 3] = in.getFloat();
            boneBind[i * 3 + 1] = in.getFloat();
            boneBind[i * 3 + 2] = in.getFloat();
        }

        // A baked weight may never point outside the skeleton.
        for (int i = 0; i < nv; i++) {
            for (int k = 0; k < 4; k++) {
                int b = bones[i * 4 + k];
                if (b < 0 || b >= nb) {
                    bones[i * 4 + k] = 0;
                    weights[i * 4 + k] = 0f;
                }
            }
        }

        JsonArray texArr = manifest.getAsJsonArray("textures");
        String[] texFiles = new String[texArr.size()];
        for (int i = 0; i < texArr.size(); i++) {
            texFiles[i] = texArr.get(i).getAsJsonObject().get("file").getAsString();
        }

        JsonArray groupArr = manifest.getAsJsonArray("groups");
        for (int i = 0; i < groupArr.size() && i < groups.length; i++) {
            JsonObject g = groupArr.get(i).getAsJsonObject();
            groups[i] = new Group(groups[i].texture, groups[i].start, groups[i].count,
                    groups[i].alpha, groups[i].doubleSided,
                    g.has("name") ? g.get("name").getAsString() : "");
        }

        int[] roles = new int[ROLE_COUNT];
        for (int i = 0; i < ROLE_COUNT; i++) {
            roles[i] = -1;
        }
        if (manifest.has("roles")) {
            JsonObject roleObj = manifest.getAsJsonObject("roles");
            for (int i = 0; i < ROLE_COUNT; i++) {
                if (!roleObj.has(ROLE_KEYS[i])) {
                    continue;
                }
                int value;
                try {
                    value = roleObj.get(ROLE_KEYS[i]).getAsInt();
                } catch (RuntimeException e) {
                    value = -1;
                }
                roles[i] = (value >= 0 && value < nb) ? value : -1;
            }
        }

        float yMin = manifest.has("yMin") ? manifest.get("yMin").getAsFloat() : 0f;
        float yMax = manifest.has("yMax") ? manifest.get("yMax").getAsFloat() : 1f;
        float height = manifest.has("heightUnits")
                ? manifest.get("heightUnits").getAsFloat()
                : (yMax - yMin);
        float soleY = manifest.has("soleY") ? manifest.get("soleY").getAsFloat() : 0f;

        Morph[] morphs = loadMorphs(base);

        return new MmdModel(tag, format, nv, pos, nrm, uv, bones, weights, weightCount, idx,
                groups, texFiles, morphs, height, nb, boneNames, boneParent, boneBind,
                roles, yMin, yMax, soleY);
    }

    private static Morph[] loadMorphs(String base) throws IOException {
        java.util.List<Morph> out = new java.util.ArrayList<>();
        ByteBuffer in = readAll(base + "morphs.bin");
        byte[] magic = new byte[4];
        in.get(magic);
        if (magic[0] != 'M' || magic[1] != 'M' || magic[2] != 'D' || magic[3] != 'M') {
            throw new IOException("bad morph magic");
        }
        in.getInt();
        int count = in.getInt();
        for (int i = 0; i < count; i++) {
            int nameLen = in.getInt();
            byte[] nameBytes = new byte[nameLen];
            in.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            int entries = in.getInt();
            int[] vi = new int[entries];
            float[] dx = new float[entries];
            float[] dy = new float[entries];
            float[] dz = new float[entries];
            for (int k = 0; k < entries; k++) {
                vi[k] = in.getInt();
                dx[k] = in.getFloat();
                dy[k] = in.getFloat();
                dz[k] = in.getFloat();
            }
            out.add(new Morph(name, vi, dx, dy, dz));
        }
        return out.toArray(new Morph[0]);
    }

    private static ByteBuffer readAll(String path) throws IOException {
        try (InputStream in = open(path)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 22);
            byte[] chunk = new byte[1 << 16];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return ByteBuffer.wrap(buf.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private static InputStream open(String path) throws IOException {
        InputStream in = MmdModel.class.getResourceAsStream(path);
        if (in == null) {
            throw new IOException("missing resource " + path);
        }
        return in;
    }
}
