# 部署、授权与安装

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
