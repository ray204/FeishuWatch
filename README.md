# 腕上日程 · FeishuWatch

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

在 OPPO Watch 上查看飞书日程和参会人。**默认仅查看，不通知、不震动**；需要时可在设置中开启提醒。目标设备：**OPPO Watch 3 / Android 11 / ColorOS Watch V16.0.0**。

v0.2 默认采用个人直连：首次配置自己的 App ID / App Secret，用手机扫码授权，随后手表直接访问飞书并自动续期。无需自建服务器、域名或内网穿透。凭证运行时配置并使用 Android Keystore 加密，不写入 APK。旧版服务模式保留兼容。

> 开发版。已在连接的 OPPO OWW261 / Android 11 上安装与操作。**当前固件平衡模式把本应用的唤醒闹钟降级为非唤醒，通知中心拒绝展示测试通知，息屏提醒尚未可用。** 标准 AppWidget 不等于 OPPO 原生卡片。详见真机记录。

第一次使用可直接按下方的 [安装与首次连接](#安装与首次连接) 操作；已连接用户可看 [日常使用](#日常使用)。遇到问题先查 [常见问题](#常见问题)。

## 界面预览

![腕上日程：首页、参会人详情和仅查看设置](docs/images/ui-overview.jpg)

按 v0.2.1 的真实布局、字号与颜色制作的 **HTML 重现**，不是原生系统截图。全部日程和姓名均为虚构示例，不包含真实账号信息。

- 首页只展示日程，向下滑动后操作；点击卡片查看全文。
- 详情以小字显示最多 10 位参会人，并标记组织者。
- 「日程提醒」默认关闭；关闭时仍保留登录、缓存和自动同步。

HTML 源码：[docs/preview/index.html](docs/preview/index.html)。下载后直接用浏览器打开，或在仓库目录运行：

```bash
python3 -m http.server 8765 --bind 127.0.0.1 --directory docs/preview
```

访问 `http://127.0.0.1:8765`，左侧手表可点击和滚动体验。预览不会连接飞书，不需要凭证。单页图片：[首页](docs/images/ui-home.jpg) · [详情](docs/images/ui-detail.jpg) · [设置](docs/images/ui-settings.jpg)。

## 功能

- 小字号日程卡片、按日期分组、日程全文与参会人详情。
- 可选的提前 0 / 5 / 10 / 15 分钟系统提醒；开启后默认提前 10 分钟，通知可“稍后 5 分钟”。
- 可关闭的下一场常驻摘要；桌面支持时添加标准下一场日程小组件。
- 7 天日程缓存，24 小时有效；有效缓存中的提醒不依赖网络或 App 页面。
- 同步取消/拒绝/移除的日程后清除旧提醒；改期重排；重启和时区变化后恢复。
- 飞书设备授权扫码，无需回调服务器；手表 Keystore 加密保存个人应用密钥及用户令牌。
- 一键 15 秒测试提醒。debug APK 另有明确标注的虚构日程体验模式。

## 安装与首次连接

以下是默认的**个人直连模式**：创建飞书应用 → 开通权限并发布 → 安装手表 APK → 配置自己的应用凭证 → 手机扫码一次。日常同步由手表联网完成，不依赖电脑持续运行。

### 1. 准备设备和账号

- 一块可安装 Android APK、可联网的 OPPO Watch；当前真机验证环境为 Android 11 / ColorOS Watch，其他固件的调试入口与后台能力可能不同。
- 手机安装飞书，登录你要查看日程的账号。
- 该账号能创建企业自建应用，或由所在组织的管理员协助创建、审批并将应用开放给你。
- 电脑安装 [Android SDK Platform-Tools（含 adb）](https://developer.android.com/tools/releases/platform-tools)，把解压后的 `platform-tools` 目录加入 PATH，确认 `adb version` 可运行。使用电脑导入凭证时还需 Python 3 和项目源码；直接安装发布 APK 不需要 JDK、Gradle 或 Node.js。

### 2. 创建飞书企业自建应用

1. 打开 [飞书开发者后台](https://open.feishu.cn/app)，确认登录的是日程所在组织的账号。
2. 选择「创建企业自建应用」，填写名称、描述和图标，例如名称填「腕上日程」。不要创建群聊 Webhook 自定义机器人。
3. 进入应用的「凭证与基础信息」，找到 **App ID** 和 **App Secret**，稍后填写到自己的本地配置或手表中。

App ID / App Secret 用来识别你创建的应用；后续手机扫码取得的 `user_access_token` 才用于读取你的日历。**无需手动获取或填写用户令牌。** 本仓库不提供共享应用凭证，也不内置作者的账号。

### 3. 开通权限、允许续期并发布

在应用的「权限管理」中点击「开通权限」，选择 **用户身份（user_access_token）**，按下列完整权限标识搜索并开通。只开通「应用身份（tenant_access_token）」不能替代本项目的用户授权。

| 权限标识 | 在本应用中的用途 | 身份 |
| --- | --- | --- |
| `calendar:calendar:read` | 读取本人主日历 | 用户身份 |
| `calendar:calendar.event:read` | 读取日程和接口允许返回的参会人 | 用户身份 |
| `offline_access` | 获取刷新凭证，让手表自动续期登录 | 用户授权 |

随后完成以下配置：

1. 打开「安全设置」，找到 **刷新 user_access_token** 开关并开启。如果后台没有这个开关，飞书文档说明其默认为开启，无需另找同名权限。
2. 在「版本管理与发布」中创建版本，填写版本号和更新说明，确认应用**可用范围包含自己**，提交发布申请。
3. 如组织要求审核，请管理员通过申请；确认版本已发布、应用已启用。以后新增权限或修改上述安全设置，也要按后台提示发布使其生效。

**开通 `offline_access` 和允许刷新令牌是两项检查。** 手机显示授权成功、手表却提示「未获得完整授权」，应同时检查这两处及版本发布状态。应用会自动在扫码请求中带上这三个 scope，无需自己拼接授权链接。

个人直连使用飞书设备授权页，**不需要填写 OAuth 重定向 URL、服务器域名、回调端口，也不需要开启机器人、消息或日历写入权限**。如果组织策略禁止设备授权，需要管理员确认允许的接入方式；项目不会绕过组织策略。

后台菜单可能随平台更新调整。官方参考：[自建应用开发与发布流程](https://open.feishu.cn/document/home/introduction-to-custom-app-development/self-built-application-development-process) · [刷新凭证的前置配置](https://open.feishu.cn/document/authentication-management/access-token/refresh-user-access-token)。

### 4. 通过 USB 安装到手表

1. 从 [v0.2.2 发布页](https://github.com/ray204/FeishuWatch/releases/tag/v0.2.2) 下载 `FeishuWatch-debug.apk`，这是目前提供的开发版安装包。
2. 在手表系统设置中进入开发者选项，开启 **USB 调试**；如未显示开发者选项，按设备固件的说明启用。使用支持数据连接的线缆／底座连接电脑。只有 USB 调试也可以，不要求无线调试。
3. 保持手表亮屏，在手表出现的 USB 调试授权提示中允许这台可信电脑。电脑执行：

```bash
adb devices -l
```

找到手表对应行。状态必须为 `device`；`unauthorized` 表示仍未授权。记录第一列序列号，以下命令中的 `WATCH_SERIAL` 都要替换成它；APK 路径也要换成实际下载位置。

```bash
adb -s WATCH_SERIAL install -r "/path/to/FeishuWatch-debug.apk"
adb -s WATCH_SERIAL shell am start -n io.github.ray204.feishuwatch/.MainActivity
```

安装输出 `Success`，手表打开「腕上日程」后继续下一步。若 `unauthorized` 且未见弹窗，先解锁手表、退出充电界面并重新插拔；仍无提示时，可在手表开发者选项中撤销 USB 调试授权后重新连接。设备完全不出现时，检查线缆是否支持数据、底座连接及电脑驱动。

### 5. 首次配置 App ID / App Secret

任选一种方式。凭证必须来自**同一个自建应用**，不要填 `user_access_token`，不要把真实凭证提交 Git 或发到 Issue。

**方式 A：在手表上输入。** 打开「配置自建应用」，填写 App ID / App Secret，点击「保存应用配置」。看到「自建应用已配置」即可。

**方式 B：从电脑通过 USB 导入。** 适合已安装本项目 debug APK 的 OPPO 手表，免去小屏输入。先下载源码 ZIP 并解压，或克隆项目：

```bash
git clone https://github.com/ray204/FeishuWatch.git
cd FeishuWatch
```

在仓库的 `server/` 目录下创建 `.env` 文件；如已有此文件，直接编辑，勿覆盖原配置。用本地文本编辑器填写以下两行，将占位文字替换成真实值：

```dotenv
FEISHU_APP_ID=填写自己的AppID
FEISHU_APP_SECRET=填写自己的AppSecret
```

文件名是 `.env`，不是 `.env.txt`，保存为 UTF-8。虽然放在 `server/` 中，**直连模式只是让导入工具读取这两个字段，并不启动服务器**。已有文件中的 `PUBLIC_URL`、`PORT`、`STORE_KEY` 等服务器字段在此模式下不使用，保持原样即可。

在仓库根目录运行（macOS / Linux）：

```bash
chmod 600 server/.env
python3 scripts/configure-watch.py --serial WATCH_SERIAL
```

Windows 安装 Python 3 后可用 `py -3 scripts/configure-watch.py --serial WATCH_SERIAL`，并确保 `adb` 已加入 PATH。不想使用默认文件位置，也可以传入 `--env "/path/to/private.env"`。

工具仅向指定 OPPO 设备写入，不打印密钥，也不把密钥放入命令行参数。手表导入后使用 Android Keystore 加密保存并删除临时明文文件；电脑上的 `.env` 仍由你保管。看到「自建应用已配置」再继续。若应用已连接，需要先在设置中断开连接，才能更换应用凭证。

### 6. 手机扫码，确认首次同步

1. 确保手表自己可以访问互联网，点击「扫码授权飞书」。
2. 用手机飞书扫描**手表当前显示的二维码**，核对连接码和应用名称，使用有该应用使用权限的账号确认授权。
3. 按手表显示的有效时间完成操作，等待手表自动领取令牌并同步。过期后返回生成新二维码，旧码不可继续使用。
4. **以手表进入日程首页、显示「已同步」时间为连接成功的判断依据**。手机授权成功不等于手表一定已完成领取令牌；如手表报错，按下方常见问题处理。

可在飞书自己的主日历中确认有一条未来七天的日程，再在手表首页向下滑动点「立即同步」，核对标题和时间。首次完成后可断开 USB，电脑无需继续运行。手表重启或重新打开 App 会保留登录并尝试同步；授权撤销、刷新凭证失效等情况仍需重新扫码，不保证永久免登录。

## 日常使用

| 想做什么 | 手表上的操作 |
| --- | --- |
| 看下一场日程 | 打开「腕上日程」，首页先显示日程卡片 |
| 看完整标题、地点和参会人 | 点击日程卡片；名单在打开详情时联网读取，最多显示前 10 位 |
| 看未来七天或全天日程 | 首页向下滑动到操作区，点「日程列表」 |
| 同步刚修改的日程 | 首页向下滑动，点「立即同步」 |
| 只看日程，不接受提醒 | 保持「设置 → 日程提醒 · 已关闭」；默认就是此模式，自动同步继续 |
| 尝试开启提醒 | 「设置 → 日程提醒」开启后设置提前时间，并执行「15 秒测试提醒」 |
| 添加组件 | 系统支持时，设置中显示「添加下一场组件」；没有入口表示当前系统不支持该标准组件 |
| 退出或更换账号／应用 | 「设置 → 断开飞书连接」，再配置和扫码 |

**仅查看模式无需开启通知或精确闹钟权限。** 若需要提醒，开启后通过「系统通知设置」允许日程提醒渠道；Android 13+ 还会申请通知权限，Android 12+ 缺少精确闹钟权限时会显示「允许精确提醒」。本项目的 Android 11 目标手表不会有后两种版本专属授权弹窗。可通过「电池与后台设置」检查系统允许的后台运行选项。

开启提醒后应返回表盘、关闭屏幕，实际检查 15 秒测试通知和震动；设置显示「已开启」不代表固件一定会送达。**目前已测 OPPO 固件仍有通知／息屏限制，不应把此版本作为唯一的会议提醒工具。** 关闭日程提醒会清除已安排提醒及常驻摘要，保留登录和日程同步。

「断开飞书连接」会删除这块手表上保存的应用凭证、用户令牌、日程缓存与提醒。如果还要撤销飞书侧的授权，请在飞书的应用授权管理中处理。日常只想静音时，关闭提醒即可，无需断开。

## 常见问题

| 现象 | 检查与处理 |
| --- | --- |
| 应用身份还是用户身份？ | 选择用户身份，按上方表格开通三个 scope；本项目读取扫码用户自己的主日历 |
| 找不到创建应用入口／无法开通权限 | 确认组织和开发者权限，联系组织管理员创建、审批并开放应用给自己 |
| 手机提示无权使用应用 | 检查应用已发布、已启用，可用范围包含扫码账号，且手机选择了正确组织 |
| 手机授权成功，手表提示「未获得完整授权」 | 检查 `offline_access`、「刷新 user_access_token」开关及发布状态；生效后在手表重新生成二维码授权 |
| 没有「刷新 user_access_token」开关 | 官方文档说明未显示时默认开启；仍需开通 `offline_access` 并完成发布和扫码授权 |
| 手表提示设备授权失败／请求失败 | 记录手表的错误码，检查网络、App ID / Secret 是否配套、权限和发布状态；若设备授权被组织限制，联系管理员 |
| 没有日程 | 当前只聚合本人的主日历，检查未来七天内是否有日程，确认扫码账号，点「立即同步」；其他订阅日历暂不聚合 |
| 参会人为空或只有部分名单 | 详情需要联网，名单以飞书允许返回的可见成员为准；群成员可能不完全可见，超过十位只展示前十位 |
| 是否需要服务器？`PUBLIC_URL` 和端口怎么填？ | 默认直连不需要，不使用这些字段；只有主动选择「旧版服务器连接」时才按 [部署指南](docs/SETUP.md) 配置 |
| 导入时提示写入失败／run-as 不可用 | 确认目标序列号、USB 已授权且安装的是本项目 debug APK；自行构建的非 debug 包请在手表手动配置 |
| 安装提示签名不兼容 | 应用覆盖安装要求签名一致；优先使用同一签名来源。确需卸载旧包再装时，先确认可重新配置和授权，卸载会清除本机数据 |
| 关闭 App 后同步变慢 | 后台周期最短 15 分钟，可能被系统休眠推迟；需要当前结果时手动「立即同步」 |
| 提醒不响／无法添加组件 | 参考上方系统权限说明及 [真机记录](docs/DEVICE_TEST.md)；这些能力受固件限制，与飞书授权是否成功是两回事 |

## 项目结构

```text
app/                 Android 原生应用（Java，API 26+，目标 35）
server/              Node.js 22+ 授权与日程同步服务（无 npm 运行时依赖）
deploy/              Docker Compose + Caddy HTTPS 部署
docs/DESIGN.md       产品、接口、权限、安全和提醒一致性详细设计
docs/SETUP.md        飞书配置、构建、部署及安装
docs/DEVICE_TEST.md  OPPO 真机验收清单
docs/preview/       使用虚构数据的 HTML 界面预览
docs/images/        从 HTML 导出的 README 图片
```

## 本地验证

```bash
npm test --prefix server
# 需要 JDK 17、Android SDK 35；local.properties 配置 sdk.dir
./gradlew testDebugUnitTest assembleDebug lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。安装方式：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。首次安装后打开一次应用，完成连接；仅在需要提醒时开启并做真机自检。

构建时可预置服务地址，免去在小屏输入：

```bash
./gradlew assembleDebug -PserverUrl=https://watch.example.com
```

默认仅查看日程；首页下滑进入设置，可按需开启「日程提醒」。关闭时不发通知、不震动、不显示常驻摘要，同步继续。详情页可查看最多十位参会人。

个人直连首次配置见 [SETUP](docs/SETUP.md)。自动化测试使用虚构数据；个人直连已通过真机扫码、主日历同步和 App 冷启动保留登录验证。`server/` 和 `deploy/` 仅供可选的旧版服务模式使用。

## 当前边界

周期同步由 JobScheduler 尝试执行（15 分钟最小周期），系统休眠可能推迟；不会承诺临时改期实时到达。已同步的提醒使用 AlarmManager，仍受系统勿扰、用户禁用通知、强行停止和 OEM 节电策略影响。离线超过 24 小时停止使用过期缓存提醒。

仅同步用户主日历。全天日程只展示，不在午夜震动。暂未接入 OPPO 私有表盘/卡片 SDK、原生日历写入或手机蓝牙同步；这些能力必须先验证设备支持。

后端为单实例部署设计，请保留同一数据卷和 STORE_KEY。不要运行多个副本，否则可能并发消耗一次性刷新凭证。

详细说明：[设计](docs/DESIGN.md) · [部署与连接](docs/SETUP.md) · [真机验收](docs/DEVICE_TEST.md) · [验证记录](docs/VALIDATION.md)

公开前检查范围与结果见 [安全审计记录](docs/SECURITY_REVIEW.md)。不要在 Issue、截图或提交中上传 `.env`、应用密钥、用户令牌或真实日程。

构建后运行 `bash scripts/package.sh`，在 `dist/` 得到 APK、源码 ZIP、部署 tar.gz、ZXing Core 对应源码和 SHA256SUMS。首次打包需要联网下载固定版本的 ZXing 源码，并校验 SHA-256。发布包见 [Releases](https://github.com/ray204/FeishuWatch/releases)。

## 开源协议

项目原创代码、文档和预览资源采用 **AGPL-3.0-only**，完整条款见 [LICENSE](LICENSE)，适用范围见 [COPYRIGHT.md](COPYRIGHT.md)。分发衍生版本时需要遵循相同协议并提供对应源码；将修改版作为网络服务提供时，也须向与之交互的用户提供对应源码。单纯使用软件不会要求公开无关项目。

第三方组件保留原协议，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
