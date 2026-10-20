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
    "fabric.mod.json",
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


def main():
    jar = sys.argv[1]
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
    missing = [n for n in REQUIRED if n not in names]
    out("entries %d" % len(names))
    for n in REQUIRED:
        out(("ok   " if n in names else "MISS ") + n)
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
