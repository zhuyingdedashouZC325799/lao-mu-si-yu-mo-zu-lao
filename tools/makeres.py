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
    if key not in TARGETS:
        raise SystemExit("unknown target %r, known: %s"
                         % (key, ", ".join(sorted(TARGETS))))
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
