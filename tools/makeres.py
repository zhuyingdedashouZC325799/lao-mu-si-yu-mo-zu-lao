# -*- coding: utf-8 -*-
"""Materialise the resource tree for one Fabric target version.

Usage: makeres.py <target-key>

Only ``fabric.mod.json`` differs between Fabric targets: it carries the
minecraft, loader, fabric-api and java ranges the loader enforces at startup,
and a range that excludes the running game is the single most common reason a
jar shows up as "incompatible" instead of loading. Everything else - the baked
meshes, textures, sounds and item models - is copied from build262\\res, which
stays the one authoritative resource build.

All version strings live in TARGETS instead of the command line on purpose:
cmd on this machine strips the quotes around a range, so ``">=26.1.2 <26.2"``
silently arrived as two words glued together and produced a json that named a
nonsense version.
"""
import io
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_RES = os.path.join(ROOT, "build262", "res")

TARGETS = {
    # key          build dir       mod version             minecraft        loader        fabric-api       java
    "262": ("build262", "1.0.0-fabric-26.2",
            ">=26.2 <26.3", ">=0.20.0", ">=0.161.0", ">=25"),
    "261fab": ("build261fab", "1.0.0-fabric-26.1.2",
               ">=26.1.2 <26.2", ">=0.19.2", ">=0.148.0", ">=25"),
    "1201": ("build1201", "1.0.0-fabric-1.20.1",
             ">=1.20.1 <1.20.2", ">=0.15.0", ">=0.92.0", ">=17"),
}

# NeoForge targets do not use fabric.mod.json at all: the loader reads
# META-INF/neoforge.mods.toml instead, and leaves a Fabric metadata file in
# place only long enough to log a "duplicate mod file" warning. They are kept
# in a separate table so the Fabric rows above stay a single flat tuple.
NEOFORGE_TARGETS = {
    # key        build dir      mod version              minecraft          neoforge
    "261neo": ("build261neo", "1.0.0-neoforge-26.1.2", "[26.1.2,26.2)", "[26.1.2,)"),
}

# Resource pack format for the 26.x line. Read out of the real
# neoforge-universal jar rather than guessed: 26.x replaced the old integer
# "pack_format" with min_format/max_format arrays, and a 1.21-style integer
# here makes the client reject the pack and load the mod with no textures.
PACK_FORMAT = [101, 1]

MOD_ID = "mmdduet"
MOD_NAME = u"\u8001\u7267\u7940\u4e0e\u9b54\u65cf\u4f6c"  # 老牧祀与魔族佬
MOD_DESC = (u"\u7940\u4e0e\u68a8\u8bfa\u7684 MMD \u7acb\u4f53\u6a21\u578b\u5b9e\u4f53"
            u"\uff1a\u5404\u81ea\u7f13\u6162\u6e38\u8d70\uff0c\u516b\u683c\u5185\u76f8\u9047"
            u"\u5c31\u8f6c\u8eab\u76f8\u5bf9\u3001\u5408\u5531\u300a\u4e00\u82b1\u4e00\u53f6"
            u"\u4e00\u83e9\u63d0\u300b\uff1b\u88ab\u5192\u72af\u65f6\u4f1a\u505c\u5531\u3001"
            u"\u8ffd\u4eba\u3001\u4e00\u811a\u8e39\u51fa 3.25 \u70b9\u771f\u5b9e\u4f24\u5bb3\u3002")


def write_neoforge_meta(dst, mod_version, mc_dep, nf_dep):
    """Write the two metadata files a NeoForge 26.x jar needs.

    neoforge.mods.toml is what the loader parses for mod discovery and for the
    dependency ranges; pack.mcmeta is what the resource system parses before it
    will mount this jar's assets and data at all.
    """
    meta_dir = os.path.join(dst, "META-INF")
    if not os.path.isdir(meta_dir):
        os.makedirs(meta_dir)

    toml = u"""modLoader = "javafml"
loaderVersion = "[1,)"
license = "All-Rights-Reserved"

[[mods]]
modId = "%s"
version = "%s"
displayName = "%s"
authors = "hanafubuki"
description = '''%s'''

[[dependencies.%s]]
modId = "neoforge"
type = "required"
versionRange = "%s"
ordering = "NONE"
side = "BOTH"

[[dependencies.%s]]
modId = "minecraft"
type = "required"
versionRange = "%s"
ordering = "NONE"
side = "BOTH"
""" % (MOD_ID, mod_version, MOD_NAME, MOD_DESC, MOD_ID, nf_dep, MOD_ID, mc_dep)

    with io.open(os.path.join(meta_dir, "neoforge.mods.toml"), "w",
                 encoding="utf-8") as f:
        f.write(toml)

    # Optional integrations, declared the same way as on Fabric so load order
    # and the "requires or is compatible with" listing stay readable.
    with io.open(os.path.join(dst, "pack.mcmeta"), "w", encoding="utf-8") as f:
        json.dump({"pack": {
            "description": MOD_NAME,
            "min_format": PACK_FORMAT,
            "max_format": PACK_FORMAT,
        }}, f, ensure_ascii=False, indent=2)
        f.write(u"\n")

    # Fabric metadata would make the NeoForge loader complain about a jar it
    # cannot read; the two loaders never share one jar.
    fabric_json = os.path.join(dst, "fabric.mod.json")
    if os.path.exists(fabric_json):
        os.remove(fabric_json)


def read_json(path):
    with io.open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path, data):
    with io.open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    key = sys.argv[1]
    if key not in TARGETS and key not in NEOFORGE_TARGETS:
        raise SystemExit("unknown target %r, known: %s"
                         % (key, ", ".join(sorted(list(TARGETS) + list(NEOFORGE_TARGETS)))))

    if key in NEOFORGE_TARGETS:
        build_dir, mod_version, mc_dep, nf_dep = NEOFORGE_TARGETS[key]
        dst = os.path.join(ROOT, build_dir, "res")
        # The mesh, texture and sound tree is identical to the Fabric build, so
        # it is copied wholesale and only the metadata is replaced.
        if os.path.isdir(dst):
            shutil.rmtree(dst)
        shutil.copytree(SRC_RES, dst)
        write_neoforge_meta(dst, mod_version, mc_dep, nf_dep)
        print("res ->", dst)
        print("version", mod_version)
        print("minecraft", mc_dep, "| neoforge", nf_dep)
        print("metadata: META-INF/neoforge.mods.toml + pack.mcmeta format", PACK_FORMAT)
        return 0

    build_dir, mod_version, mc_dep, loader_dep, api_dep, java_dep = TARGETS[key]
    dst = os.path.join(ROOT, build_dir, "res")

    # For the "262" key dst *is* SRC_RES: rmtree+copy would delete the one
    # authoritative resource build and then find nothing left to copy.
    in_place = os.path.normcase(os.path.abspath(dst)) == \
        os.path.normcase(os.path.abspath(SRC_RES))
    if os.path.isdir(dst):
        if not in_place:
            shutil.rmtree(dst)
            shutil.copytree(SRC_RES, dst)
    else:
        shutil.copytree(SRC_RES, dst)

    mod_json = read_json(os.path.join(dst, "fabric.mod.json"))
    mod_json["id"] = "mmdduet"
    mod_json["version"] = mod_version
    mod_json["depends"]["minecraft"] = mc_dep
    mod_json["depends"]["fabricloader"] = loader_dep
    mod_json["depends"]["fabric-api"] = api_dep
    mod_json["depends"]["java"] = java_dep
    # HealthCeiling lifts max_health past the vanilla 1024 clamp through
    # reflection, which this mod does itself; declaring the soft dependency
    # just keeps load order sane when AttributeFix is also installed.
    mod_json["recommends"] = {"attributefix": "*"}
    write_json(os.path.join(dst, "fabric.mod.json"), mod_json)

    print("res ->", dst)
    if in_place:
        print("(in place: resource tree reused, only fabric.mod.json rewritten)")
    print("version", mod_version)
    print("minecraft", mc_dep, "| loader", loader_dep,
          "| api", api_dep, "| java", java_dep)
    return 0


if __name__ == "__main__":
    sys.exit(main())
