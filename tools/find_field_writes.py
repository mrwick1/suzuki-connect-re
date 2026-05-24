"""
Find every Dalvik instruction that WRITES to a specific instance field.

Used to crack the mystery of where com/suzuki/application/fragment/C.G
gets assigned at runtime (Java source decompile shows no assignment
beyond the default "1N", but captures show "3Y" at the corresponding
byte position).
"""

from androguard.misc import AnalyzeAPK

APK = "apk/base.apk"
TARGET_CLASS = "Lcom/suzuki/application/fragment/C;"
TARGET_FIELD = "G"

print(f"Loading {APK} ...")
a, d_list, dx = AnalyzeAPK(APK)
print(f"Loaded. Searching for writes to {TARGET_CLASS}->{TARGET_FIELD}\n")

hits = 0
for method in dx.get_methods():
    m = method.get_method()
    if not hasattr(m, "get_code") or m.get_code() is None:
        continue
    for idx, ins in enumerate(m.get_instructions()):
        op = ins.get_name()
        if "iput" not in op and "sput" not in op:
            continue
        operands = ins.get_output()
        # iput-object v0, v1, Lcom/suzuki/.../C;->G Ljava/lang/String;
        if TARGET_CLASS in operands and f"->{TARGET_FIELD} " in operands:
            hits += 1
            class_name = m.get_class_name()
            method_name = m.get_name()
            print(f"WRITE #{hits}:  {class_name}->{method_name}  ({op})")
            print(f"        {operands}")
            # print a few neighboring instructions for context
            all_ins = list(m.get_instructions())
            start = max(0, idx - 2)
            end = min(len(all_ins), idx + 2)
            for j in range(start, end):
                marker = "   * " if j == idx else "     "
                print(f"        {marker}{all_ins[j].get_name():16s}  {all_ins[j].get_output()}")
            print()

print(f"\nTotal writes to {TARGET_CLASS}->{TARGET_FIELD}: {hits}")
