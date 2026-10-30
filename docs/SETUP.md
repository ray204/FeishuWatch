# 配置、授权与安装

## v0.2 个人手表直连（默认）

无需部署服务器、域名或内网穿透。你自己的 App ID / App Secret 仅在首次配置时传入手表，不写入 APK、Git 或聊天。凭证由 Android Keystore AES-GCM 加密保存，但设备或应用运行环境被攻破仍有泄露风险。

1. 在飞书自建应用中开通**用户身份**的 `calendar:calendar:read`、`calendar:calendar.event:read`，以及 `offline_access`。完成后台要求的审批与发布，应用可用范围包含自己。
   在「安全设置」中，若存在「刷新 user_access_token」开关，需开启并发布新版本。仅开通 offline_access 并不保证该开关也已开启；关闭时可能登录成功但没有 refresh_token。
2. 在本地 `server/.env` 填写 `FEISHU_APP_ID` 和 `FEISHU_APP_SECRET`。直连模式忽略该文件中的其他服务器字段。也可使用独立本地文件，通过 `--env` 指定。
3. 安装 debug APK 并完成手表 USB 调试授权，运行以下命令。也可在手表「配置自建应用」手动填写。

```bash
python3 scripts/configure-watch.py --serial 手表的ADB序列号
```

工具只向指定 OPPO 设备写入；密钥通过标准输入传递，不进入进程参数或命令输出。应用读取私有目录的临时配置后加密保存并删除明文文件。已连接的应用需先在手表断开后才能更换配置。

4. 点击「扫码授权飞书」，手机飞书扫描二维码并核对连接码。授权页由飞书提供，不需配置自建 OAuth 回调地址；应用是否允许设备授权以真实接口结果为准。
5. 授权后手表联网直接读取主日历并自动续期。企业策略可能限制设备授权，需要用真实应用验证。
6. 默认仅查看日程。首屏点击日程卡片可查看全文及最多十位参会人；向下滑动进入操作区，再点「设置」可开启或关闭「日程提醒」。关闭时不通知、不震动、不显示常驻摘要，日程仍同步。
7. 「断开飞书连接」清除此表的应用密钥、令牌、缓存和提醒；飞书端授权请在飞书应用授权管理中撤销。

失效或结果不明的刷新请求会要求重新扫码，不能保证永久免登录。OPPO 固件的通知和息屏限制与连接方式无关，见 [真机验收](DEVICE_TEST.md)。

**以下章节仅适用于可选的旧版服务器模式。**

## 飞书应用（一次性配置）

1. 在飞书开放平台创建企业自建应用，获取 App ID / App Secret。
2. 配置用户身份权限：`calendar:calendar:read`、`calendar:calendar.event:read`、`offline_access`。开发者后台如要求审批，完成审批与发布，让你的账户在可用范围内。
3. 将安全设置中的重定向 URL 设为 `https://你的域名/oauth/callback`，必须与 PUBLIC_URL 对应地址完全一致。
4. 按需开通网页应用能力；本服务使用浏览器 OAuth 授权码流程，无需机器人聊天能力。
5. 只在服务端 `.env` 保存密钥，不提交 Git、不发给手表、不发到聊天中。

用户凭证用于读取**自己的主日历**。机器人凭证不是用户日程凭证，不能替代本流程。使用飞书 `instance_view`，由平台展开重复日程。

## Node 本机服务

```bash
cd server
cp .env.example .env
chmod 600 .env
openssl rand -hex 32
# 将上一步随机值填写到 STORE_KEY，填写飞书凭证和 HTTPS PUBLIC_URL
npm start
```

Node 22+；没有运行时依赖，无需 npm install。默认只监听 127.0.0.1:8787；HTTPS 反向代理负责公开访问。服务端 `/healthz` 是无敏感信息的健康检查。

API 鉴权和 Cookie 不依赖代理传来的 X-Forwarded-*；同一个代理后按代理 IP 限流，适合个人部署。请关闭代理的完整请求 URL 日志，避免记录 OAuth code。

## Docker + HTTPS

需要一台可被手表与手机访问的服务器、域名及 80/443 端口。将域名 DNS 指向该服务器。

在 `server/.env` 中填写：

```dotenv
PUBLIC_HOST=watch.example.com
PUBLIC_URL=https://watch.example.com
FEISHU_APP_ID=填写自己的应用ID
FEISHU_APP_SECRET=仅在服务器填写
STORE_KEY=填写64位十六进制随机密钥
```

从仓库根目录启动：

```bash
docker compose --env-file server/.env -f deploy/compose.yaml up --build -d
curl --fail https://watch.example.com/healthz
```

Caddy 自动申请 HTTPS 证书。Node 不对宿主机公开端口。需要备份 `bridge_data` 卷和 STORE_KEY；二者必须配套。升级时不要 `down -v`。后端不具备跨进程锁，因此不要水平扩容。

## 构建 APK

安装 JDK 17、Android SDK Platform 35 和 Build Tools 35（AGP 可能自动补充默认工具版本）。在 `local.properties` 设置本机 SDK 目录，该文件不会提交。

```properties
sdk.dir=/你的/Android/sdk
```

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug -PserverUrl=https://watch.example.com
```

本机开发用 debug APK；正式长期使用建议创建自己的 release 签名并妥善保存，不把签名文件与密码入库。debug 构建包含示例模式，release 不包含示例入口。

## 手表连接

1. 在手表开发者设置中开启设备提供的 ADB 调试方式，将电脑与手表连接；无线端口/配对流程以固件界面显示为准，不假定 5555。
2. `adb devices` 确认设备后安装 APK。多设备时使用 `adb -s 序列号 install -r ...`。
3. 打开“腕上日程”，点击连接，用手机扫描二维码，核对连接码，点击确认并授权飞书。
4. 手机页面提示已连接后回到手表，手表会自动轮询、下载日程、安排提醒。
5. 在“提醒与组件”中执行 15 秒测试，回到表盘并关闭屏幕，确认系统通知与震动。
6. 正常使用不需重复登录或打开 App；每次授权需要 `offline_access`。服务重启依赖加密数据卷保持连接。

## 常见问题

- **需要重新连接**：先断开旧连接，再扫码。授权被撤销、refresh token 失效或一次性刷新请求的响应丢失会需要重授权。
- **通知未出现**：检查日程提醒渠道、勿扰、系统对 App 的后台限制，并完成真机测试。`areNotificationsEnabled()` 为 true 并不能证明 OPPO 固件一定显示。
- **组件无法添加**：系统没有公开标准 AppWidgetHost 时，不显示添加按钮。此时仍可测试通知摘要；OPPO 原生卡片另行适配。
- **二维码过期**：10 分钟内未完成授权，返回重新生成。
- **空日历**：确认授权的是本人且主日历有日程；订阅的其他日历暂不聚合。检查权限，不能用忙闲接口代替标题等详情。
- **缓存过期**：检查网络和服务，手动刷新。缓存过期后保留列表供查看但不继续提醒。
- **临时改期仍出现旧通知**：离线或周期同步被系统延迟时无法得知远端改动。成功同步后会撤销旧通知和提醒。

此处的部署命令尚不代表已部署服务；实际域名、应用凭证和真机连接需由使用者配置。
