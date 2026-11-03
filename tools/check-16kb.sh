#!/usr/bin/env bash
# Verifies an APK is compatible with 16 KB memory-page devices (Android 15+ Pixels etc.):
#  1. every .so has ELF LOAD segments aligned to >= 16 KB (2**14)
#  2. the APK zip entries for .so files are 16 KB-aligned (zipalign -P 16)
set -euo pipefail
APK="$1"; TMP=$(mktemp -d); fail=0
unzip -q -o "$APK" 'lib/*' -d "$TMP" 2>/dev/null || { echo "no native libs"; exit 0; }
for so in $(find "$TMP/lib" -name '*.so'); do
  abi=$(basename "$(dirname "$so")")
  [[ "$abi" == arm64-v8a || "$abi" == x86_64 ]] || continue
  min=$(objdump -p "$so" | awk '/LOAD/ {for(i=1;i<=NF;i++) if($i=="align") print $(i+1)}' | sed 's/2\*\*//' | sort -n | head -1)
  if [[ -z "$min" || "$min" -lt 14 ]]; then echo "NOT 16KB-aligned: $abi/$(basename "$so") (2**$min)"; fail=1
  else echo "ok  $abi/$(basename "$so") (2**$min)"; fi
done
ZA=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)zipalign
if "$ZA" -c -P 16 -v 4 "$APK" > "$TMP/za.txt" 2>&1; then echo "zipalign -P 16: ok"; else grep -i "lib/" "$TMP/za.txt" | grep -iv "OK" | head; echo "zipalign -P 16: FAILED"; fail=1; fi
exit $fail
