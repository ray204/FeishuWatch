# 腕上日程 · FeishuWatch

为 OPPO Watch 显示飞书日程，并通过系统通知和震动提醒赴约。目标设备：**OPPO Watch 3 / Android 11 / ColorOS Watch V16.0.0**。

日常无需打开 App。首次用手机扫码授权，随后服务端自动续期；授权撤销或凭证失效时需要重新扫码。支持个人自建飞书应用。

> 开发版。Android 标准能力已经实现，**OPPO 真机的通知呈现、息屏震动、后台策略和桌面组件尚待验证**。标准 AppWidget 不等于 OPPO 原生卡片，应用也不依赖 Wear OS。

## 功能

- 提前 0 / 5 / 10 / 15 分钟系统提醒，默认 10 分钟；通知可“稍后 5 分钟”。
- 可关闭的下一场常驻摘要；桌面支持时添加标准下一场日程小组件。
- 7 天日程缓存，24 小时有效；有效缓存中的提醒不依赖网络或 App 页面。
- 同步取消/拒绝/移除的日程后清除旧提醒；改期重排；重启和时区变化后恢复。
- 扫码绑定、服务端加密保存飞书凭证、手表 Keystore 保存设备凭证。
- 一键 15 秒测试提醒。debug APK 另有明确标注的虚构日程体验模式。

## 项目结构

```text
app/                 Android 原生应用（Java，API 26+，目标 35）
server/              Node.js 22+ 授权与日程同步服务（无 npm 运行时依赖）
deploy/              Docker Compose + Caddy HTTPS 部署
docs/DESIGN.md       产品、接口、权限、安全和提醒一致性详细设计
docs/SETUP.md        飞书配置、构建、部署及安装
docs/DEVICE_TEST.md  OPPO 真机验收清单
```

## 本地验证

```bash
npm test --prefix server
# 需要 JDK 17、Android SDK 35；local.properties 配置 sdk.dir
./gradlew testDebugUnitTest assembleDebug lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。安装方式：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。首次安装后打开一次应用，完成连接和提醒自检。

构建时可预置服务地址，免去在小屏输入：

```bash
./gradlew assembleDebug -PserverUrl=https://watch.example.com
```

后端配置见 [SETUP](docs/SETUP.md)。不将飞书 App Secret 打入 APK。测试不使用真实日程或真实用户凭证。

## 当前边界

周期同步由 JobScheduler 尝试执行（15 分钟最小周期），系统休眠可能推迟；不会承诺临时改期实时到达。已同步的提醒使用 AlarmManager，仍受系统勿扰、用户禁用通知、强行停止和 OEM 节电策略影响。离线超过 24 小时停止使用过期缓存提醒。

仅同步用户主日历。全天日程只展示，不在午夜震动。暂未接入 OPPO 私有表盘/卡片 SDK、原生日历写入或手机蓝牙同步；这些能力必须先验证设备支持。

后端为单实例部署设计，请保留同一数据卷和 STORE_KEY。不要运行多个副本，否则可能并发消耗一次性刷新凭证。

详细说明：[设计](docs/DESIGN.md) · [部署与连接](docs/SETUP.md) · [真机验收](docs/DEVICE_TEST.md) · [验证记录](docs/VALIDATION.md)

构建后运行 `bash scripts/package.sh`，在 `dist/` 得到 APK、源码 ZIP、部署 tar.gz 和 SHA256SUMS。发布包见 [Releases](https://github.com/ray204/FeishuWatch/releases)。
