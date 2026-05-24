"""
Find every Dalvik instruction that WRITES to a specific instance/static field.

Originally written to crack the mystery of where com/suzuki/application/fragment/C.G
gets assigned at runtime — the Java source decompile showed no assignment beyond
the default "1N", but captures showed "3Y". Turned out ProGuard had moved the
assignment to androidx/appcompat/app/z.java (BATTERY_CHANGED receiver).

Now general-purpose. Useful any time JADX shows a field with only a default
initialization but you suspect runtime reassignment is hidden somewhere ProGuard
has relocated.

Usage:
    python tools/find_field_writes.py <class-path>.<FieldName>

Examples:
    python tools/find_field_writes.py com/suzuki/application/fragment/C.M
    python tools/find_field_writes.py com/suzuki/application/fragment/A0.v0
    python tools/find_field_writes.py com/google/android/gms/measurement/internal/K.f
"""

import sys

from loguru import logger
logger.remove()  # silence androguard's loguru chatter

from androguard.misc import AnalyzeAPK  # noqa: E402

APK = "apk/base.apk"


def find_writes(class_path: str, field_name: str, max_context: int = 2):
    target_class = f"L{class_path};"
    print(f"Loading {APK} ...")
    _a, _d, dx = AnalyzeAPK(APK)
    print(f"Loaded. Searching for writes to {target_class}->{field_name}\n")

    hits = 0
    for method in dx.get_methods():
        m = method.get_method()
        if not hasattr(m, "get_code") or m.get_code() is None:
            continue
        all_ins = list(m.get_instructions())
        for idx, ins in enumerate(all_ins):
            op = ins.get_name()
            if "iput" not in op and "sput" not in op:
                continue
            operands = ins.get_output()
            if target_class in operands and f"->{field_name} " in operands:
                hits += 1
                print(f"WRITE #{hits}:  {m.get_class_name()}->{m.get_name()}  ({op})")
                print(f"        {operands}")
                start = max(0, idx - max_context)
                end = min(len(all_ins), idx + max_context + 1)
                for j in range(start, end):
                    marker = "   * " if j == idx else "     "
                    print(f"        {marker}{all_ins[j].get_name():16s}  {all_ins[j].get_output()}")
                print()

    print(f"\nTotal writes to {target_class}->{field_name}: {hits}")
    return hits


def parse_target(arg: str) -> tuple[str, str]:
    """Split 'com/suzuki/.../Cls.fieldName' into (class_path, field_name)."""
    if "." not in arg:
        raise SystemExit(f"expected '<class-path>.<FieldName>', got {arg!r}")
    last_dot = arg.rfind(".")
    return arg[:last_dot], arg[last_dot + 1 :]


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    class_path, field_name = parse_target(sys.argv[1])
    find_writes(class_path, field_name)
