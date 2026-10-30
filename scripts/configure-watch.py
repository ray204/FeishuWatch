#!/usr/bin/env python3
"""Copy this user's app credentials over authorized USB, never as adb arguments/logs."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='ADB serial of the watch')
parser.add_argument('--env', type=Path, default=Path(__file__).resolve().parents[1] / 'server/.env')
args = parser.parse_args()
adb = shutil.which('adb') or str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
values = {}
for line in args.env.read_text().splitlines():
    if '=' not in line or line.lstrip().startswith('#'):
        continue
    key, value = line.split('=', 1)
    if key.strip() in ('FEISHU_APP_ID', 'FEISHU_APP_SECRET'):
        values[key.strip()] = value.strip().strip('\"\'')
if not all(values.get(k) for k in ('FEISHU_APP_ID', 'FEISHU_APP_SECRET')):
    parser.error('请先在本地配置文件填写 FEISHU_APP_ID 和 FEISHU_APP_SECRET；不要发到聊天中')
os.chmod(args.env, 0o600)
prefix = [adb, '-s', args.serial]
# Check the actual target and require the installed debug app. Never pick another device.
r = subprocess.run(prefix + ['shell', 'getprop', 'ro.product.manufacturer'], check=True, capture_output=True, text=True)
if r.stdout.strip().lower() != 'oppo':
    parser.error('目标设备厂商不是 OPPO，请核对 --serial')
package = 'io.github.ray204.feishuwatch'
# Stop only this app so onCreate imports the configuration exactly once.
subprocess.run(prefix + ['shell', 'am', 'force-stop', package], check=True, stdout=subprocess.DEVNULL)
body = json.dumps({'appId': values['FEISHU_APP_ID'], 'appSecret': values['FEISHU_APP_SECRET']}).encode()
r = subprocess.run(prefix + ['shell', 'run-as', package, 'sh', '-c', "'umask 077; mkdir -p files; cat > files/direct-config.json'"], input=body, capture_output=True)
if r.returncode:
    raise SystemExit('写入失败：请确认安装的是 debug APK 且 USB 已授权。')
subprocess.run(prefix + ['shell', 'am', 'start', '-W', '-n', package + '/.MainActivity'], check=True, stdout=subprocess.DEVNULL)
# App imports to Keystore encryption and immediately removes the plaintext transfer file.
print('配置已传入手表。请确认页面显示“自建应用已配置”，再点击“扫码授权飞书”。')
