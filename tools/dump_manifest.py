"""
Dump the AndroidManifest.xml of an APK using androguard.

Used to check what services / receivers / permissions / network paths
the Suzuki Connect app actually declares. Specifically: is MqttService
registered as a real service the app uses, or is it dead code from
the Paho dependency?
"""

import sys
from androguard.misc import AnalyzeAPK
from lxml import etree

APK = sys.argv[1] if len(sys.argv) > 1 else "apk/base.apk"

a, _, _ = AnalyzeAPK(APK)
print(etree.tostring(a.get_android_manifest_xml(), pretty_print=True).decode("utf-8"))
