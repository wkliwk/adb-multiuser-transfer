#!/bin/bash
# Smoke test for the installed adb-multiuser-transfer app. Usage: ./test.sh <android-user-id>
set -uo pipefail
U="${1:?usage: ./test.sh <android-user-id>}"
A=content://io.github.wkliwk.adbtransfer
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0
ok()  { echo "PASS  $1"; }
bad() { echo "FAIL  $1"; fail=1; }

head -c 100000 /dev/urandom > "$T/a.bin"
SUM="$(shasum -a 256 "$T/a.bin" | cut -d' ' -f1)"

adb shell content write --user "$U" --uri "$A/selftest/a.bin" < "$T/a.bin" >/dev/null 2>&1 && ok "write" || bad "write"
OUT="$(adb shell content call --user "$U" --uri "$A" --method stat --arg selftest/a.bin 2>&1)"
echo "$OUT" | grep -q "exists=true" && echo "$OUT" | grep -q "size=100000" && ok "stat size" || bad "stat size: $OUT"
echo "$OUT" | grep -q "$SUM" && ok "stat sha256 matches the source" || bad "stat sha256"
adb shell content read --user "$U" --uri "$A/selftest/a.bin" > "$T/back.bin" 2>/dev/null
cmp -s "$T/a.bin" "$T/back.bin" && ok "read-back identical" || bad "read-back"
adb shell content call --user "$U" --uri "$A" --method ls --arg selftest 2>&1 | grep -q "a.bin" && ok "ls" || bad "ls"
for p in "../Android/x" "/../../data/x" "Pictures/../Android/x"; do
  R="$(adb shell content write --user "$U" --uri "$A/$p" < "$T/a.bin" 2>&1)"
  echo "$R" | grep -qiE "outside|Exception|denied" && ok "refused: $p" || bad "not refused: $p ($R)"
done
# a name without a Pictures/ or DCIM/ prefix is taken below Pictures/ (documented), so it stays inside the allowed root
adb shell content write --user "$U" --uri "$A/selftest/Android/x" < "$T/a.bin" >/dev/null 2>&1 \
  && adb shell content call --user "$U" --uri "$A" --method stat --arg Pictures/selftest/Android/x 2>&1 | grep -q "exists=true" \
  && ok "unprefixed path lands below Pictures/" || bad "unprefixed path"
adb shell content call --user "$U" --uri "$A" --method rm --arg selftest/Android/x >/dev/null 2>&1
adb shell content call --user "$U" --uri "$A" --method rm --arg selftest/a.bin 2>&1 | grep -q "deleted=true" && ok "rm" || bad "rm"
adb shell content call --user "$U" --uri "$A" --method stat --arg selftest/a.bin 2>&1 | grep -q "exists=false" && ok "gone after rm" || bad "gone after rm"
[ "$fail" = 0 ] && echo "ALL PASS" || { echo "SOME FAILED"; exit 1; }
