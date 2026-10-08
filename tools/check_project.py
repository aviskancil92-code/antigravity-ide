#!/usr/bin/env python3
"""Pemeriksa statis ringan (tanpa Android SDK) — gerbang kualitas di CI dan sebelum commit.

Memeriksa: XML valid, referensi R.* / @tipe/nama ada, view-binding cocok dengan id layout,
package == direktori, impor lintas-paket lengkap, kurung seimbang, kelas manifest ada.
Keluar dengan kode 1 bila ada masalah.
"""
import re, sys, glob, os
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app/src/main")
JAVA = os.path.join(MAIN, "java")
RES = os.path.join(MAIN, "res")
errors = []
def err(m): errors.append(m)

# ---- 1. XML valid ----
xml_files = glob.glob(RES + "/**/*.xml", recursive=True) + [MAIN + "/AndroidManifest.xml"]
for f in xml_files:
    try: ET.parse(f)
    except Exception as e: err(f"XML tidak valid {os.path.relpath(f, ROOT)}: {e}")

# ---- 2. inventaris resource ----
res = {k: set() for k in ["string", "color", "drawable", "layout", "mipmap", "id", "style", "xml"]}
for f in glob.glob(RES + "/values*/*.xml"):
    try: t = ET.parse(f).getroot()
    except Exception: continue
    for e in t:
        n = e.get("name")
        if e.tag in ("string", "color", "style") and n: res[e.tag].add(n)
for f in glob.glob(RES + "/**/*", recursive=True):
    if not os.path.isfile(f): continue
    d = os.path.basename(os.path.dirname(f)); stem = os.path.splitext(os.path.basename(f))[0]
    for typ in ("drawable", "layout", "mipmap", "xml"):
        if d.startswith(typ): res[typ].add(stem)
for f in glob.glob(RES + "/layout*/*.xml"):
    for m in re.finditer(r'@\+id/(\w+)', open(f, encoding="utf-8").read()): res["id"].add(m.group(1))

# ---- 3. referensi di XML ----
for f in xml_files:
    txt = open(f, encoding="utf-8").read()
    for m in re.finditer(r'@(string|color|drawable|layout|mipmap|style|xml)/([\w.]+)', txt):
        typ, name = m.groups()
        if name.replace('.', '_') not in res[typ] and name not in res[typ] and not (typ == "style" and name.startswith("Theme")):
            err(f"{os.path.relpath(f, ROOT)}: @{typ}/{name} tidak ada")
    for m in re.finditer(r'@id/(\w+)', txt):
        if m.group(1) not in res["id"]: err(f"{os.path.relpath(f, ROOT)}: @id/{m.group(1)} tidak ada")

# ---- 4. Kotlin ----
kt = glob.glob(JAVA + "/**/*.kt", recursive=True)
decl = {}  # nama top-level -> paket
pkg_of = {}
for f in kt:
    s = open(f, encoding="utf-8").read()
    pm = re.search(r'^package ([\w.]+)', s, re.M)
    if not pm: err(f"{f}: tanpa package"); continue
    pkg = pm.group(1); pkg_of[f] = pkg
    exp = os.path.relpath(os.path.dirname(f), JAVA).replace(os.sep, ".")
    if pkg != exp: err(f"{os.path.relpath(f, ROOT)}: package {pkg} != direktori {exp}")
    for m in re.finditer(r'^(?:sealed |data |abstract |open |enum )*(?:class|object|interface) (\w+)', s, re.M):
        decl[m.group(1)] = pkg

def strip(s):
    # Urutan penting: literal karakter & string dulu (mengandung "//" atau "/*"), baru komentar.
    s = re.sub(r"'(?:\\.|[^'\\\n])'", "''", s)
    s = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', s)
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'//[^\n]*', '', s)
    return s

for f in kt:
    raw = open(f, encoding="utf-8").read(); s = strip(raw)
    rel = os.path.relpath(f, ROOT)
    for a, b in ("{}", "()", "[]"):
        if s.count(a) != s.count(b): err(f"{rel}: kurung {a}{b} tidak seimbang ({s.count(a)} vs {s.count(b)})")
    pkg = pkg_of.get(f, "")
    imports = re.findall(r'^import ([\w.*]+)', raw, re.M)
    wild = {i[:-2] for i in imports if i.endswith(".*")}
    explicit = {i.split(".")[-1]: i for i in imports if not i.endswith(".*")}
    for name, dpkg in decl.items():
        if dpkg == pkg: continue
        if re.search(r'\b' + name + r'\b', s):
            if name in explicit and explicit[name] == f"{dpkg}.{name}": continue
            if dpkg in wild: continue
            # nama bisa hanya anggota tipe lain (mis. .Running) -> periksa pemakaian berdiri sendiri
            if re.search(r'(?<![\w.])' + name + r'\b', s):
                err(f"{rel}: memakai {name} (paket {dpkg}) tanpa import")
    # R.*
    for m in re.finditer(r'(?<![\w.])R\.(string|color|drawable|layout|mipmap|id|style)\.(\w+)', s):
        typ, name = m.groups()
        if name not in res[typ]: err(f"{rel}: R.{typ}.{name} tidak ada")
        if not re.search(r'import com\.antigravity\.ide\.R\b', raw) and pkg != "com.antigravity.ide":
            err(f"{rel}: memakai R tanpa import com.antigravity.ide.R"); break
    # binding.<id>
    for m in re.finditer(r'\bbinding\.(\w+)', s):
        n = m.group(1)
        if n in ("root",): continue
        ids = {re.sub(r'_(\w)', lambda x: x.group(1).upper(), i) for i in res["id"]}
        if n not in ids: err(f"{rel}: binding.{n} tanpa id layout")

# ---- 5. manifest ----
man = open(MAIN + "/AndroidManifest.xml", encoding="utf-8").read()
for m in re.finditer(r'android:name="(\.[\w.]+)"', man):
    cls = "com.antigravity.ide" + m.group(1)
    path = os.path.join(JAVA, cls.replace(".", "/") + ".kt")
    if not os.path.isfile(path): err(f"manifest: kelas {cls} tidak ditemukan")

if errors:
    print("DITEMUKAN %d MASALAH:" % len(errors))
    for e in errors: print(" -", e)
    sys.exit(1)
print("OK: %d berkas Kotlin, %d XML diperiksa — tidak ada masalah." % (len(kt), len(xml_files)))
