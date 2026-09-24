#!/usr/bin/env python3
"""口径回归：把本仓库 core/JevPrompts.kt 里的题目集 / 话术库 / prompt 与 iOS 版
Shared/JevPrompts.swift 逐字对比（两边共同的源头是 macOS 版 src/judge.py / styles.py / generate.py）。

    python3 tools/prompt_check.py [iOS 仓库路径]

默认路径是 ~/Documents/Justforfun/jev-chat-jarvis-ios。对不上就非零退出——
「同一口径」这句话必须能被一条命令证伪，否则它只是口号。
"""
import re
import sys
from pathlib import Path

DEFAULT_IOS = Path.home() / "Documents/Justforfun/jev-chat-jarvis-ios"
KOTLIN = Path(__file__).resolve().parent.parent / "app/src/main/java/com/jev/simple/core/JevPrompts.kt"


def swift_string_dict(text, name):
    m = re.search(r'let %s: \[String: String\] = \[(.*?)\n\]' % name, text, re.S)
    return re.findall(r'"((?:[^"\\]|\\.)*)"\s*:\s*"((?:[^"\\]|\\.)*)"', m.group(1))


def kotlin_string_map(text, name):
    m = re.search(r'val %s: Map<String, String> = linkedMapOf\((.*?)\n\)' % name, text, re.S)
    body = re.sub(r'//[^\n]*', '', m.group(1))
    return re.findall(r'"((?:[^"\\]|\\.)*)"\s*to\s*"((?:[^"\\]|\\.)*)"', body)


def swift_string_list(text, name):
    m = re.search(r'let %s: \[String\] = \[(.*?)\n\]' % name, text, re.S)
    return re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))


def kotlin_string_list(text, name):
    m = re.search(r'val %s: List<String> = listOf\((.*?)\n\)' % name, text, re.S)
    return re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))


def swift_string_list_map(text, name):
    m = re.search(r'let %s: \[String: \[String\]\] = \[(.*?)\n\]' % name, text, re.S)
    return [
        (k, tuple(re.findall(r'"((?:[^"\\]|\\.)*)"', v)))
        for k, v in re.findall(r'"((?:[^"\\]|\\.)*)"\s*:\s*\[([^\]]*)\]', m.group(1))
    ]


def kotlin_string_list_map(text, name):
    m = re.search(r'val %s: Map<String, List<String>> = linkedMapOf\((.*?)\n\)' % name, text, re.S)
    body = re.sub(r'//[^\n]*', '', m.group(1))
    return [
        (k, tuple(re.findall(r'"((?:[^"\\]|\\.)*)"', v)))
        for k, v in re.findall(r'"((?:[^"\\]|\\.)*)"\s*to\s*listOf\(([^)]*)\)', body)
    ]


def main() -> int:
    ios_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_IOS
    swift_file = ios_dir / "Shared/JevPrompts.swift"
    if not swift_file.exists():
        print(f"找不到 iOS 版口径文件：{swift_file}", file=sys.stderr)
        print("用法：python3 tools/prompt_check.py [iOS 仓库路径]", file=sys.stderr)
        return 2

    swift = swift_file.read_text(encoding="utf-8")
    kotlin = KOTLIN.read_text(encoding="utf-8")
    failures = 0

    def check(label, a, b):
        nonlocal failures
        if a == b:
            print(f"✅ {label}：{len(a)} 项逐字一致")
            return
        failures += 1
        print(f"❌ {label}：不一致")
        if a and isinstance(a[0], tuple):
            da, db = dict(a), dict(b)
            for k in sorted(set(da) | set(db)):
                if da.get(k) != db.get(k):
                    print(f"   [{k}]\n     swift : {da.get(k)}\n     kotlin: {db.get(k)}")
        else:
            for i, (x, y) in enumerate(zip(a, b)):
                if x != y:
                    print(f"   [{i}] swift={x!r}\n        kotlin={y!r}")
            if len(a) != len(b):
                print(f"   长度 swift={len(a)} kotlin={len(b)}")

    check("INTENTS", swift_string_dict(swift, "INTENTS"), kotlin_string_map(kotlin, "INTENTS"))
    check("RISK_LEVELS", swift_string_list(swift, "RISK_LEVELS"), kotlin_string_list(kotlin, "RISK_LEVELS"))
    check("ACTION_MAP", swift_string_list_map(swift, "ACTION_MAP"), kotlin_string_list_map(kotlin, "ACTION_MAP"))
    check("BUILTIN_TONES", swift_string_dict(swift, "BUILTIN_TONES"), kotlin_string_map(kotlin, "BUILTIN_TONES"))
    check("BUILTIN_TONE_ORDER",
          swift_string_list(swift, "BUILTIN_TONE_ORDER"),
          kotlin_string_list(kotlin, "BUILTIN_TONE_ORDER"))

    ps = re.search(r'let PROMPT_ONE = """\n(.*?)\n"""', swift, re.S).group(1)
    pk = re.search(r'const val PROMPT_ONE = """(.*?)\n"""', kotlin, re.S).group(1).lstrip("\n")
    check("PROMPT_ONE", [ps], [pk])

    for label, pat in [("PER_TONE", r"let PER_TONE = (\d+)"), ("NONE_LABEL", r'let NONE_LABEL = "([^"]*)"')]:
        s = re.search(pat, swift).group(1)
        k = re.search(pat.replace("let", r"(?:const )?val"), kotlin)
        k = k.group(1) if k else None
        check(label, [s], [k])

    print()
    if failures:
        print(f"❌ {failures} 处口径不一致——四端同口径是产品承诺，改不动就一起改。")
        return 1
    print("✅ 全部口径一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
