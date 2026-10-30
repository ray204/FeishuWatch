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
  -o "$output_dir/FeishuWatch-deploy.tar.gz" server deploy .dockerignore README.md docs/SETUP.md
cp "$apk" "$output_dir/FeishuWatch-debug.apk"
python3 - "$output_dir" <<'PY'
import hashlib
import pathlib
import sys
folder = pathlib.Path(sys.argv[1])
names = ['FeishuWatch-source.zip', 'FeishuWatch-deploy.tar.gz', 'FeishuWatch-debug.apk']
with (folder / 'SHA256SUMS').open('w') as out:
    for name in names:
        out.write(f'{hashlib.sha256((folder / name).read_bytes()).hexdigest()}  {name}\n')
print(f'交付包已生成：{folder}')
PY
