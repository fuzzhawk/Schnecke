"""Check a generated .als against Live 10's own schema and a set Live 10 really wrote.

Usage: python3 validate_live10.py LIVE_SCHEMAS_JSON_GZ REFERENCE_10.0.6.als GENERATED.als

* LIVE_SCHEMAS_JSON_GZ: abletoolz/schema/data/live_schemas.json.gz from abletoolz (MPL-2.0),
  the classes and members Live declares for each schema version.
* REFERENCE: a set saved by Live 10.0.6 (e.g. abletoolz's test/version_fixtures/skeletons/10.0.6.als).

Reports, for schema 10.0_370:
  UNDECLARED  an element whose tag the schema does not declare as a member of its parent's class
  MISSING     a child that every element of that class in the reference carries, but ours lacks
  ORDER       children in a different order than Live writes them
"""

from __future__ import annotations

import gzip
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

VERSION = "10.0_370"
GENERIC = re.compile(r"^\w+<(\w+)>$")


class Schema:
    def __init__(self, path: str) -> None:
        d = json.load(gzip.open(path))
        versions = [x["minor_version"] for x in d["schemas"]]
        self.bit = 1 << versions.index(VERSION)
        self.classes = {k for k, v in d["classes"].items() if int(v, 16) & self.bit}
        self.members: dict[str, dict[str, str]] = {}
        for cls, per in d["members"].items():
            m = {}
            for member, types in per.items():
                for t, mask in types.items():
                    if int(mask, 16) & self.bit:
                        m[member] = t
                        break
            if m:
                self.members[cls] = m

    def child_class(self, parent_cls: str | None, child: ET.Element, parent_holds_elements: bool) -> tuple[str | None, bool, bool]:
        """(class of child, whether child's own children are class-tagged elements, declared?)"""
        if parent_holds_elements or parent_cls is None or parent_cls not in self.members:
            cls = child.tag if child.tag in self.classes else None
            return cls, cls is not None and cls not in self.members, True
        t = self.members[parent_cls].get(child.tag)
        if t is None:
            return None, False, False
        g = GENERIC.match(t)
        if g:
            return t, True, True
        if t in self.members:
            return t, False, True
        # A value type or an untyped container: anything inside is tagged with its own class.
        return t, True, True


def walk(schema: Schema, el: ET.Element, cls: str | None, holds: bool, path: str, visit) -> None:
    for child in el:
        ccls, cholds, declared = schema.child_class(cls, child, holds)
        visit(el, cls, child, ccls, declared, f"{path}/{child.tag}")
        walk(schema, child, ccls, cholds, f"{path}/{child.tag}", visit)


def load(path: str) -> ET.Element:
    raw = open(path, "rb").read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    return ET.fromstring(raw)


def main() -> int:
    schema = Schema(sys.argv[1])
    ref = load(sys.argv[2])
    gen = load(sys.argv[3])
    if gen.get("MinorVersion") != VERSION:
        print(f"generated set is {gen.get('MinorVersion')}, expected {VERSION}")
        return 1

    ref_children: dict[str, list[list[str]]] = defaultdict(list)

    def collect(parent, pcls, child, ccls, declared, path):
        if ccls and ccls in schema.members:
            ref_children[ccls].append([c.tag for c in child])

    ref_children["LiveSet"].append([c.tag for c in ref.find("LiveSet")])
    walk(schema, ref.find("LiveSet"), "LiveSet", False, "LiveSet", collect)
    required = {c: set.intersection(*(set(x) for x in lists)) for c, lists in ref_children.items()}
    order = {}
    for c, lists in ref_children.items():
        idx = {}
        for lst in lists:
            for i, tag in enumerate(lst):
                idx.setdefault(tag, i)
        order[c] = idx

    problems: list[str] = []
    checked = defaultdict(int)

    def check_children(el: ET.Element, cls: str, path: str) -> None:
        tags = [c.tag for c in el]
        checked[cls] += 1
        if cls in required:
            for tag in sorted(required[cls] - set(tags)):
                problems.append(f"MISSING    {path}: <{tag}> (class {cls})")
            idx = order[cls]
            known = [idx[t] for t in tags if t in idx]
            if known != sorted(known):
                problems.append(f"ORDER      {path} (class {cls})")

    def visit(parent, pcls, child, ccls, declared, path):
        if not declared:
            problems.append(f"UNDECLARED {path} (parent class {pcls})")
        if ccls and ccls in schema.members:
            check_children(child, ccls, path)

    check_children(gen.find("LiveSet"), "LiveSet", "LiveSet")
    walk(schema, gen.find("LiveSet"), "LiveSet", False, "LiveSet", visit)

    seen = set()
    for p in problems:
        key = re.sub(r"\.\d+", ".N", p)
        if key not in seen:
            seen.add(key)
            print(p)
    print(f"checked {sum(checked.values())} elements of {len(checked)} classes against Live {VERSION}; "
          f"{len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
