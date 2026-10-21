# -*- coding: utf-8 -*-
"""Assert that a packed jar contains the entries the mod needs to function.

A missing damage_type json or one missing bypasses_* tag does not stop the game
from loading: the mod simply starts throwing "unknown damage type" the first
time a kick lands, which is far too late to notice. This checks the whole set
before the jar is installed anywhere.
"""
import sys
import zipfile


def out(s):
    sys.stdout.buffer.write((str(s) + "\n").encode("utf-8", "replace"))


REQUIRED = [
    "com/hanafubuki/duet/DuetMod.class",
    "com/hanafubuki/duet/SingerEntity.class",
    "com/hanafubuki/duet/KickAttackGoal.class",
    "com/hanafubuki/duet/RageTargetGoal.class",
    "com/hanafubuki/duet/DuetDamage.class",
    "com/hanafubuki/duet/mesh/Animator.class",
    "com/hanafubuki/duet/client/SingerRenderer.class",
    "data/mmdduet/damage_type/kick.json",
    "data/minecraft/tags/damage_type/bypasses_armor.json",
    "data/minecraft/tags/damage_type/bypasses_enchantments.json",
    "data/minecraft/tags/damage_type/bypasses_resistance.json",
    "data/minecraft/tags/damage_type/bypasses_effects.json",
    "data/minecraft/tags/damage_type/bypasses_shield.json",
    "data/minecraft/tags/damage_type/bypasses_wolf_armor.json",
    "assets/mmdduet/lang/zh_cn.json",
    "assets/mmdduet/sounds/performance.json",
]

# Which mod metadata file counts as "this jar can be loaded".
#
# The two loaders never share a jar: Fabric reads fabric.mod.json and
# NeoForge reads META-INF/neoforge.mods.toml, and a jar carrying the wrong
# one is rejected at startup. Checking for fabric.mod.json unconditionally
# therefore reported every NeoForge build as broken.
METADATA_ALTERNATIVES = [
    "fabric.mod.json",
    "META-INF/neoforge.mods.toml",
]


def main():
    # cmd re-quotes every argv it hands back, even ones it split itself, so a
    # path arrives as '"out\\x.jar"'.  Stripping is required or open() fails
    # with "Invalid argument".
    jar = sys.argv[1].strip()
    if len(jar) >= 2 and jar[0] == '"' and jar[-1] == '"':
        jar = jar[1:-1]
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
    missing = [n for n in REQUIRED if n not in names]
    present_meta = [n for n in METADATA_ALTERNATIVES if n in names]
    if len(present_meta) == 1:
        out("metadata: " + present_meta[0])
    elif not present_meta:
        missing.append(" | ".join(METADATA_ALTERNATIVES))
    else:
        out("metadata: BOTH loaders declared, which breaks one of them")
        missing.append("exactly one of " + " / ".join(METADATA_ALTERNATIVES))

    out("entries %d" % len(names))
    for n in REQUIRED:
        out(("ok   " if n in names else "MISS ") + n)

    # pack.mcmeta is what makes the resource system mount this jar's assets at
    # all; without it the mesh blobs ship but nothing ever loads them.
    if "pack.mcmeta" in names:
        out("ok   pack.mcmeta")
    else:
        out("note no pack.mcmeta (Fabric builds do not need one)")
    # The baked meshes and textures are large, so report their count rather
    # than listing them.
    tex = sum(1 for n in names if n.startswith("assets/mmdduet/entity/")
              and "/tex/" in n)
    meshes = sum(1 for n in names if n.startswith("assets/mmdduet/entity/")
                 and n.endswith(".bin"))
    out("textures %d, mesh blobs %d" % (tex, meshes))
    if missing:
        out("FAILED: %d missing" % len(missing))
        return 1
    out("all required entries present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
