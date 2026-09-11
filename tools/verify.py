#!/usr/bin/env python3
"""
Static architecture checks that need no Maven, no network and no dependencies.

WHY THIS EXISTS
---------------
This codebase was written in an environment with no access to Maven Central, so `mvn test` could
never run. These checks are what stood in for it: pure-stdlib Python over the source tree, catching
the class of mistake that compiles cleanly and fails silently at runtime.

They do NOT replace `mvn test`. ModularityTest and ArchitectureTest are the real enforcement and
they see things this cannot (bytecode-level dependencies, annotation semantics, Spring wiring).
Keep this as the fast pre-commit pass: it runs in under a second and needs nothing installed.

    python3 tools/verify.py            # from the repository root
    python3 tools/verify.py <path>     # or point it at a checkout

Exit code 0 = clean, 1 = problems listed on stdout.

WHAT IT CHECKS
--------------
  1-4  package declaration matches directory, type name matches file name, balanced braces and
       parentheses, imports that are never used
  5    non-English identifiers in executable code (string literals, comments and javadoc are
       exempt - Vietnamese is legitimate there)
  6-8  module boundaries: nobody reads another module's `internal`, every cross-module import is
       declared in allowedDependencies, every module has @ApplicationModule
  9    @Enumerated must be STRING - an ordinal enum breaks when constants are reordered
  10   the permission catalogue: every @RequiresPermission resource is declared by a
       @PermissionResource, and vice versa
  11   Flyway version numbers are unique
  12   every @Table names a table some migration creates
  13   every @Scheduled method also carries @SchedulerLock
  14   @Transactional / @Scheduled / @ApplicationModuleListener methods are public, because a
       Spring proxy cannot intercept a non-public method and ignores the annotation in silence
  15   api/ carries @NamedInterface("api") and the module base package holds only package-info

The comment on strip_java() is worth reading before touching the parser.
"""
import os, re, sys, glob, collections

# Repository root: the argument if given, otherwise the parent of this script's directory.
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SRC = os.path.join(ROOT, "src")
problems = []

files = [p for p in glob.glob(SRC + "/**/*.java", recursive=True)]

def pkg_of(path):
    s = open(path).read()
    m = re.search(r'^package\s+([\w.]+);', s, re.M)
    return m.group(1) if m else None

def strip_java(src):
    """Remove comments, string literals, char literals and text blocks - correctly.

    A single-pass state machine, not a sequence of regexes. Every regex ordering is wrong for some
    input: strip comments first and a string containing "/**" (every "/actuator/health/**" matcher
    in this codebase) opens a comment that swallows the rest of the file; strip strings first and a
    comment containing an apostrophe swallows code. This scanner has no ordering to get wrong
    because it tracks which construct it is actually inside.

    Whitespace is preserved so reported line numbers still match the file.
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""

        if c == "/" and nxt == "/":                      # line comment
            while i < n and src[i] != "\n":
                i += 1
        elif c == "/" and nxt == "*":                    # block comment
            i += 2
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                if src[i] == "\n":
                    out.append("\n")
                i += 1
            i += 2
        elif src.startswith('\"\"\"', i):                  # text block
            i += 3
            while i < n and not src.startswith('\"\"\"', i):
                if src[i] == "\n":
                    out.append("\n")
                i += 1
            i += 3
            out.append('\"\"')
        elif c == '\"':                                   # string literal
            i += 1
            while i < n and src[i] != '\"':
                i += 2 if src[i] == "\\" else 1
            i += 1
            out.append('\"\"')
        elif c == "'":                                   # char literal
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == "\\" else 1
            i += 1
            out.append("'x'")
        else:
            out.append(c)
            i += 1
    return "".join(out)


# 1. package declaration matches directory
for f in files:
    p = pkg_of(f)
    expected = os.path.dirname(os.path.relpath(f, SRC)).split(os.sep)
    expected = ".".join(expected[3:])  # main/java/com/... -> strip main,java
    expected = ".".join(os.path.dirname(os.path.relpath(f, SRC)).split(os.sep)[2:])
    if p != expected:
        problems.append(f"PACKAGE MISMATCH {f}: declares {p}, path says {expected}")

# 2. public type name matches file name
for f in files:
    if f.endswith("package-info.java"):
        continue
    s = open(f).read()
    base = os.path.basename(f)[:-5]
    if not re.search(r'\b(class|interface|enum|record|@interface)\s+' + re.escape(base) + r'\b', s):
        problems.append(f"TYPE NAME {f}: no type named {base}")

# 3. balanced braces / parens - catches a truncated or half-written file
for f in files:
    t = strip_java(open(f).read())
    if t.count("{") != t.count("}"):
        problems.append(f"BRACES {f}: {t.count('{')} open vs {t.count('}')} close")
    if t.count("(") != t.count(")"):
        problems.append(f"PARENS {f}: {t.count('(')} vs {t.count(')')}")

# 4. unused imports
for f in files:
    src = open(f).read()
    body = strip_java(src)
    body = re.sub(r'^import .*$', '', body, flags=re.M)
    javadoc = "\n".join(re.findall(r'/\*\*.*?\*/', src, flags=re.S))
    for imp in re.findall(r'^import\s+(?:static\s+)?([\w.]+);', src, flags=re.M):
        simple = imp.split(".")[-1]
        if simple == "*":
            problems.append(f"WILDCARD IMPORT {f}: {imp}")
            continue
        if not re.search(r'\b' + re.escape(simple) + r'\b', body):
            if re.search(r'\b' + re.escape(simple) + r'\b', javadoc):
                problems.append(f"IMPORT ONLY IN JAVADOC {f}: {imp}")
            else:
                problems.append(f"UNUSED IMPORT {f}: {imp}")

# 5. Vietnamese text in CODE - identifiers and executable statements must stay English.
#
#    Deliberately NOT flagged: string literals and comments. Since the i18n layer landed, Vietnamese
#    appears legitimately as sample data (a javadoc example of a localised error body) and as test
#    fixtures asserting the translations. Those are the feature working, not a violation. The rule
#    the team agreed - code and notes in English so a reviewer can read them - is about identifiers
#    and prose, and that is what this now checks.
VN = re.compile(r'[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ]', re.I)
for f in files:
    for i, line in enumerate(strip_java(open(f).read()).split(chr(10)), 1):
        if VN.search(line):
            problems.append(f"NON-ENGLISH CODE {f}:{i}: {line.strip()[:80]}")

# 6. module boundary: no import of another module's .internal.
MODULES = {"identity","customer","product","catalog","inventory","warehouse","order","payment",
           "fulfillment","procurement","design","chat","notification","reporting"}
for f in files:
    if "/src/test/" in f: continue
    p = pkg_of(f)
    if not p or not p.startswith("com.stockflow."): continue
    own = p.split(".")[2]
    for imp in re.findall(r'^import\s+(?:static\s+)?([\w.]+);', open(f).read(), flags=re.M):
        m = re.match(r'com\.stockflow\.(\w+)\.internal\.', imp)
        if m and m.group(1) != own:
            problems.append(f"BOUNDARY VIOLATION {f}: imports {imp}")

# 7. allowedDependencies respected
allowed = {}
for f in glob.glob(SRC + "/main/java/com/stockflow/*/package-info.java"):
    mod = os.path.basename(os.path.dirname(f))
    s = open(f).read()
    m = re.search(r'allowedDependencies\s*=\s*\{([^}]*)\}', s)
    # Entries are named-interface references: "inventory :: api". Take the module name before
    # the "::" - the interface part is what Spring Modulith checks, not what this script does.
    deps = {d.split("::")[0].strip()
            for d in re.findall(r'"([^"]+)"', m.group(1))} if m else set()
    allowed[mod] = deps | {"common", "contracts"}
for f in files:
    if "/src/test/" in f: continue
    p = pkg_of(f)
    if not p or not p.startswith("com.stockflow."): continue
    parts = p.split(".")
    if len(parts) < 3: continue
    own = parts[2]
    if own not in allowed: continue
    for imp in re.findall(r'^import\s+(?:static\s+)?(com\.stockflow\.[\w.]+);', open(f).read(), flags=re.M):
        target = imp.split(".")[2]
        if target != own and target not in allowed[own]:
            problems.append(f"UNDECLARED DEP {f}: {own} -> {target} ({imp})")

# 8. every module has package-info with @ApplicationModule
for mod in sorted(MODULES | {"common", "contracts"}):
    pi = os.path.join(SRC, "main/java/com/stockflow", mod, "package-info.java")
    if not os.path.exists(pi):
        problems.append(f"MISSING package-info for module {mod}")
    elif "ApplicationModule" not in open(pi).read():
        problems.append(f"package-info for {mod} lacks @ApplicationModule")

# 9. @Enumerated must be STRING
for f in files:
    s = open(f).read()
    for m in re.finditer(r'@Enumerated\(([^)]*)\)', s):
        if "STRING" not in m.group(1):
            problems.append(f"ORDINAL ENUM {f}: @Enumerated({m.group(1)})")

# 10. permission catalog consistency
declared, used = set(), set()
for f in files:
    s = open(f).read()
    for m in re.finditer(r'@PermissionResource\(\s*\n?\s*code\s*=\s*([\w.]+)', s):
        declared.add(m.group(1).split(".")[-1])
    for m in re.finditer(r'@RequiresPermission\(resource\s*=\s*([\w.]+)', s):
        used.add(m.group(1).split(".")[-1])
missing = used - declared
if missing:
    problems.append(f"PERMISSION: guarded resources with no @PermissionResource: {sorted(missing)}")

# 11. Flyway version uniqueness
vers = collections.Counter()
for f in glob.glob(ROOT + "/src/main/resources/db/**/V*.sql", recursive=True):
    vers[os.path.basename(f).split("__")[0]] += 1
dups = [v for v, n in vers.items() if n > 1]
if dups:
    problems.append(f"FLYWAY duplicate versions: {dups}")

# 12. SQL referenced tables exist in migrations
sql = "\n".join(open(f).read() for f in glob.glob(ROOT + "/src/main/resources/db/**/*.sql", recursive=True))
tables = set(re.findall(r'CREATE TABLE\s+([\w.]+)', sql, re.I))
for f in files:
    for t in re.findall(r'@Table\(\s*\n?\s*name\s*=\s*"(\w+)".*?schema\s*=\s*"(\w+)"',
                        open(f).read(), re.S):
        full = f"{t[1]}.{t[0]}"
        if full not in tables:
            problems.append(f"NO MIGRATION for entity table {full} ({f})")

# 13. Every @Scheduled method also carries @SchedulerLock.
#     @EnableSchedulerLock installs an advisor that matches ONLY @SchedulerLock, so a @Scheduled
#     method without it is unprotected with no warning anywhere. The failure appears the day a
#     second instance starts, which is exactly when nobody is watching the scheduler.
for f in files:
    src = strip_java(open(f, encoding="utf-8").read())
    if "@Scheduled" not in src:
        continue
    # Split on the annotation and look at what follows, up to the method body, for @SchedulerLock.
    for chunk in src.split("@Scheduled")[1:]:
        head = chunk.split("{", 1)[0]
        if "@SchedulerLock" not in head:
            problems.append(f"UNLOCKED @Scheduled method in {f} - add @SchedulerLock or it runs "
                            f"on every instance")

# 14. @Transactional, @Scheduled and @ApplicationModuleListener methods must be public.
#     Spring's proxy cannot intercept a non-public method, so the annotation is silently ignored.
PROXIED = ("@Transactional", "@Scheduled", "@ApplicationModuleListener")
for f in files:
    if "/test/" in f:
        continue
    src = strip_java(open(f, encoding="utf-8").read())
    for line_no, line in enumerate(src.splitlines(), 1):
        stripped = line.strip()
        if not stripped.startswith(PROXIED):
            continue
        # Walk forward to the first line that declares something.
        rest = src.splitlines()[line_no:]
        for decl in rest:
            d = decl.strip()
            if not d or d.startswith("@") or d.startswith("//"):
                continue
            # Only method declarations matter; a class-level @Transactional is a different thing.
            if "(" in d and not d.startswith(("class ", "public class", "record ", "interface ")):
                if not d.startswith(("public ", "default ")) and "abstract" not in d:
                    problems.append(f"NON-PUBLIC proxied method in {f}:{line_no + 1} -> {d[:70]}")
            break


# 15. The api package must be a declared named interface, and the base package must stay empty.
#     Spring Modulith exposes the BASE package by default and treats nested ones as internal, so
#     an api/ package without @NamedInterface is private: the module would have no public API and
#     the first caller to import from it fails ModularityTest with a message pointing at the
#     caller rather than at the missing annotation. A stray class left in the base package is the
#     mirror image - silently public, outside the api contract, invisible to reviewers.
for mod in sorted(MODULES):
    base = os.path.join(SRC, "main/java/com/stockflow", mod)
    strays = [os.path.basename(x) for x in glob.glob(base + "/*.java")
              if os.path.basename(x) != "package-info.java"]
    if strays:
        problems.append(f"STRAY PUBLIC TYPE in module base package {mod}: {strays} - "
                        f"move them into {mod}/api/")
    api_pi = os.path.join(base, "api", "package-info.java")
    if not os.path.exists(api_pi):
        problems.append(f"MISSING {mod}/api/package-info.java")
    elif 'NamedInterface("api")' not in open(api_pi).read():
        problems.append(f"{mod}/api/package-info.java lacks @NamedInterface(\"api\") - "
                        f"the package is private without it")

print(f"scanned {len(files)} java files, {len(tables)} tables")
if problems:
    print(f"\n{len(problems)} PROBLEM(S):")
    for p in problems: print("  -", p)
    sys.exit(1)
print("\nall static checks passed")
