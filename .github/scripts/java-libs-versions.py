#!/usr/bin/env python3
"""Points the test applications at the qubership-core-java-libs versions of a given ref.

  collect <java-libs-dir>             prints {"modules": {...}, "boms": {...}}, both mapping
                                      "groupId:artifactId" to a version:
                                        modules - every module of the checked-out monorepo
                                        boms    - third-party BOMs the monorepo imports (the Quarkus
                                                  platform, for instance), when all of it agrees
                                                  on the version
                                      Properties are resolved along the in-repo parent chain.
  apply <versions.json> <pom.xml>...  rewrites the given poms: every parent, dependency or plugin
                                      that the monorepo publishes, and every import of one of those
                                      third-party BOMs, gets the version from the map, either in
                                      place or through the property it is declared with (following
                                      a property that only refers to another one)

The poms are edited as text, so their layout survives and a diff shows only the versions.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PROP = re.compile(r"\$\{([^}]+)\}")


def text(el, path):
    found = el.find(path, NS)
    return found.text.strip() if found is not None and found.text else None


def load(path):
    root = ET.parse(path).getroot()
    parent = root.find("m:parent", NS)
    props = {}
    for p in root.findall("m:properties/*", NS):
        props[p.tag.split("}", 1)[1]] = (p.text or "").strip()
    return {
        "path": path,
        "groupId": text(root, "m:groupId"),
        "artifactId": text(root, "m:artifactId"),
        "version": text(root, "m:version"),
        "parent": None if parent is None else {
            "groupId": text(parent, "m:groupId"),
            "artifactId": text(parent, "m:artifactId"),
            "version": text(parent, "m:version"),
            "relativePath": text(parent, "m:relativePath"),
        },
        "props": props,
    }


def collect(repo):
    poms = {}
    for base, dirs, files in os.walk(repo):
        dirs[:] = [d for d in dirs if d not in (".git", "target", "node_modules")]
        if "pom.xml" in files:
            path = os.path.normpath(os.path.join(base, "pom.xml"))
            try:
                poms[path] = load(path)
            except ET.ParseError:
                continue
    by_ga = {}
    for pom in poms.values():
        gid = pom["groupId"] or (pom["parent"] or {}).get("groupId")
        by_ga[f"{gid}:{pom['artifactId']}"] = pom

    def parent_of(pom):
        parent = pom["parent"]
        if not parent:
            return None
        rel = parent["relativePath"]
        if rel is None:
            rel = "../pom.xml"
        if rel:
            cand = os.path.normpath(os.path.join(os.path.dirname(pom["path"]), rel))
            if os.path.isdir(cand):
                cand = os.path.join(cand, "pom.xml")
            found = poms.get(cand)
            if found and found["artifactId"] == parent["artifactId"]:
                return found
        return by_ga.get(f"{parent['groupId']}:{parent['artifactId']}")

    cache = {}

    def context(pom):
        """Properties visible to a pom: its parents' first, its own on top."""
        key = pom["path"]
        if key in cache:
            return cache[key]
        cache[key] = {}  # guards against a cycle
        parent = parent_of(pom)
        ctx = dict(context(parent)) if parent else {}
        ctx.update(pom["props"])
        raw_version = pom["version"] or (pom["parent"] or {}).get("version")
        if pom["parent"]:
            ctx["project.parent.version"] = pom["parent"]["version"]
            ctx["parent.version"] = pom["parent"]["version"]
        ctx["project.version"] = raw_version
        ctx["version"] = raw_version
        cache[key] = ctx
        return ctx

    def resolve(value, ctx):
        for _ in range(10):
            new = PROP.sub(lambda m: ctx.get(m.group(1), m.group(0)), value)
            if new == value:
                break
            value = new
        return value

    modules = {}
    for ga, pom in sorted(by_ga.items()):
        ctx = context(pom)
        version = resolve(ctx["project.version"] or "", ctx)
        if version and not PROP.search(version):
            modules[ga] = version

    seen = {}
    for pom in poms.values():
        ctx = context(pom)
        root = ET.parse(pom["path"]).getroot()
        for dep in root.findall("m:dependencyManagement/m:dependencies/m:dependency", NS):
            if text(dep, "m:scope") != "import":
                continue
            ga = f"{resolve(text(dep, 'm:groupId') or '', ctx)}:{resolve(text(dep, 'm:artifactId') or '', ctx)}"
            version = resolve(text(dep, "m:version") or "", ctx)
            if ga in modules or not version or PROP.search(version) or PROP.search(ga):
                continue
            seen.setdefault(ga, set()).add(version)
    boms = {ga: versions.pop() for ga, versions in sorted(seen.items()) if len(versions) == 1}
    return {"modules": modules, "boms": boms}


BLOCK = re.compile(r"<(parent|dependency|plugin)>(.*?)</\1>", re.S)


def apply(versions, path):
    with open(path, encoding="utf-8") as f:
        src = f.read()
    declared = {}
    block = re.search(r"<properties>(.*?)</properties>", src, re.S)
    if block:
        for name, value in re.findall(r"<([\w.\-]+)>\s*(.*?)\s*</\1>", block.group(1), re.S):
            declared[name] = value

    def expand(value):
        for _ in range(10):
            new = PROP.sub(lambda m: declared.get(m.group(1), m.group(0)), value)
            if new == value:
                break
            value = new
        return value

    props = {}
    changes = []

    def fix_block(m):
        body = m.group(2)
        g = re.search(r"<groupId>\s*(.*?)\s*</groupId>", body)
        a = re.search(r"<artifactId>\s*(.*?)\s*</artifactId>", body)
        v = re.search(r"<version>\s*(.*?)\s*</version>", body)
        if not (g and a and v):
            return m.group(0)
        ga = f"{expand(g.group(1))}:{expand(a.group(1))}"
        wanted = versions["modules"].get(ga)
        if not wanted and m.group(1) == "dependency" and re.search(r"<scope>\s*import\s*</scope>", body):
            wanted = versions["boms"].get(ga)
        if not wanted:
            return m.group(0)
        prop = re.fullmatch(r"\$\{([^}]+)\}", v.group(1))
        if prop:
            name = prop.group(1)
            # a property that is only another property: the version lives in that one
            while re.fullmatch(r"\$\{([^}]+)\}", declared.get(name, "")):
                name = declared[name][2:-1]
            if name not in ("project.version", "version"):
                props[name] = wanted
            return m.group(0)
        if v.group(1) != wanted:
            changes.append(f"{g.group(1)}:{a.group(1)} {v.group(1)} -> {wanted}")
        body = body[:v.start(1)] + wanted + body[v.end(1):]
        return f"<{m.group(1)}>{body}</{m.group(1)}>"

    src = BLOCK.sub(fix_block, src)
    for name, wanted in props.items():
        pattern = re.compile(rf"(<{re.escape(name)}>)\s*(.*?)\s*(</{re.escape(name)}>)")
        found = pattern.search(src)
        if not found:
            print(f"::warning file={path}::property {name} is used for a java-libs artifact but not defined here")
            continue
        if found.group(2) != wanted:
            changes.append(f"property {name} {found.group(2)} -> {wanted}")
        src = src[:found.start()] + f"{found.group(1)}{wanted}{found.group(3)}" + src[found.end():]
    with open(path, "w", encoding="utf-8") as f:
        f.write(src)
    for change in changes:
        print(f"{path}: {change}")


def main(argv):
    if len(argv) >= 2 and argv[0] == "collect":
        json.dump(collect(argv[1]), sys.stdout, indent=2, sort_keys=True)
        print()
    elif len(argv) >= 3 and argv[0] == "apply":
        with open(argv[1], encoding="utf-8") as f:
            versions = json.load(f)
        for path in argv[2:]:
            apply(versions, path)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
