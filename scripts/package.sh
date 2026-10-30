#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
output_dir="${1:-$project_root/dist}"
apk="$project_root/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$apk" ]]; then
  echo '请先运行 ./gradlew assembleDebug' >&2
  exit 1
fi
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
git -C "$project_root" archive --format=zip --prefix=FeishuWatch/ HEAD -o "$output_dir/FeishuWatch-source.zip"
git -C "$project_root" archive --format=tar.gz --prefix=FeishuWatch/ HEAD \
  -o "$output_dir/FeishuWatch-deploy.tar.gz" server deploy .dockerignore README.md docs/SETUP.md LICENSE COPYRIGHT.md THIRD_PARTY_NOTICES.md docs/licenses
cp "$apk" "$output_dir/FeishuWatch-debug.apk"
# Provide the unmodified runtime dependency's source alongside our source/APK.
zxing_source="$output_dir/ZXing-core-3.5.3-sources.jar"
if [[ ! -f "$zxing_source" ]]; then
  curl --fail --location --silent --show-error \
    https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3-sources.jar \
    --output "$zxing_source"
fi
python3 - "$output_dir" <<'PY'
import hashlib
import pathlib
import sys
folder = pathlib.Path(sys.argv[1])
source = folder / 'ZXing-core-3.5.3-sources.jar'
if hashlib.sha256(source.read_bytes()).hexdigest() != 'f983454400d73652ad5236413ba7eba48072e042982a84b10e1468506f4b28ae':
    raise SystemExit('ZXing source checksum mismatch; packaging stopped')
names = ['FeishuWatch-source.zip', 'FeishuWatch-deploy.tar.gz', 'FeishuWatch-debug.apk', source.name]
with (folder / 'SHA256SUMS').open('w') as out:
    for name in names:
        out.write(f'{hashlib.sha256((folder / name).read_bytes()).hexdigest()}  {name}\n')
print(f'交付包已生成：{folder}')
PY
