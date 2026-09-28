# -*- coding: utf-8 -*-
"""Which Minecraft versions this tree builds for, and where to find one's client jar.

The list is read from `gradle.properties` rather than written here. It is the same line the
build reads, so a version added to the build is a version the generators write for, with no
second place to remember.
"""
import io
import os


def targets(root):
    """The game versions this tree builds for, newest first."""
    line = _property(root, "minecraft_targets")
    if not line:
        raise SystemExit("gradle.properties has no minecraft_targets")
    return [part.strip() for part in line.split(",") if part.strip()]


def at_least(target, floor):
    """Whether a version is `floor` or newer.

    Compared as numbers, not as text: 26.10 comes after 26.3, and a string comparison puts it
    before. There is no 26.10 yet, and this is what stops the day there is from being the day
    every loot table quietly reverts to the old format.
    """
    return _key(target) >= _key(floor)


def _key(version):
    return tuple(int(part) if part.isdigit() else -1 for part in version.split("."))


def data_root(root, target):
    """Where a version's own data files live.

    Almost everything the mod ships is identical across versions and stays in `resources`.
    This root holds only what is not -- today the loot tables, whose format changed in 26.3.
    """
    return os.path.join(root, "common", "src", "main", "resources-" + target, "data")


def _property(root, name):
    path = os.path.join(root, "gradle.properties")
    # Java's .properties format is ISO-8859-1, which is why mod_authors is escaped in it.
    with io.open(path, encoding="iso-8859-1") as f:
        for line in f:
            line = line.strip()
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    return None


def client_jar(root):
    """A vanilla client jar to sample textures and the font from.

    Any of the supported versions will do: the vanilla assets this reads -- the item sprites and
    ascii.png -- are byte-identical across them, which was checked rather than assumed. The
    newest one present wins, so the file follows whatever was built last instead of pinning a
    version that may no longer be in the cache.
    """
    cache = os.path.expanduser("~/.gradle/caches/neoformruntime/artifacts")
    for target in targets(root):
        path = os.path.join(cache, "minecraft_%s_client.jar" % target)
        if os.path.isfile(path):
            return path
    raise SystemExit(
        "no Minecraft client jar in " + cache + "\n"
        "  Build once first -- gradlew :fabric:build -- which is what puts it there.")
