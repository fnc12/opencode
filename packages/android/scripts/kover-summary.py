#!/usr/bin/env python3
"""Print a compact coverage summary from a Kover XML report: overall line/branch
percentages plus the worst-covered classes, so a gap is obvious at a glance."""
import sys
import xml.etree.ElementTree as ET


def counter(el, typ):
    for c in el.findall("counter"):
        if c.get("type") == typ:
            cov = int(c.get("covered"))
            return cov, cov + int(c.get("missed"))
    return 0, 0


def main(path):
    root = ET.parse(path).getroot()
    print("  coverage:")
    for typ in ("LINE", "BRANCH"):
        cov, tot = counter(root, typ)
        pct = 100 * cov / tot if tot else 0.0
        print(f"    {typ:7} {cov}/{tot}  {pct:.1f}%")
    rows = []
    for pkg in root.findall("package"):
        for cls in pkg.findall("class"):
            name = (cls.get("name") or "").split("/")[-1]
            cov, tot = counter(cls, "LINE")
            if tot and cov < tot:
                rows.append((tot - cov, cov, tot, name))
    rows.sort(reverse=True)
    if rows:
        print("  worst-covered (missed lines):")
        for missed, cov, tot, name in rows[:12]:
            print(f"    {cov:4}/{tot:<4} ({100*cov/tot:5.1f}%)  {name}")


if __name__ == "__main__":
    main(sys.argv[1])
