# 验证记录

## 范围

用户已选择“先交付本地项目和部署包”。真实飞书授权、服务器部署和 OPPO Watch 3 真机验收不在本次已完成验证中；分别跟踪于 [Issue 1](https://github.com/ray204/FeishuWatch/issues/1)、[Issue 2](https://github.com/ray204/FeishuWatch/issues/2)、[Issue 3](https://github.com/ray204/FeishuWatch/issues/3)。

测试数据均为虚构数据。没有读取真实日程，没有向已连接的物理手机安装应用。

## 本地与 CI 检查

- `npm test --prefix server`：14 项通过，0 失败、0 跳过。
- `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`：构建成功；JUnit XML 确认 6 项通过，0 失败、0 错误、0 跳过。
- lint：0 错误、18 条警告（同步持久化 SharedPreferences、依赖版本建议、备份规则和组件硬编码文案）。为进程重建保留的状态采用同步写入，未为清除警告改为可能丢失写入的异步操作。
- APK：包名 `io.github.ray204.feishuwatch`，版本 `0.1.0`，minSdk 26、targetSdk 35；`apksigner verify --verbose` 通过，使用 v2 签名。
- GitHub Actions 的 Docker 镜像构建和 `/healthz` 启动检查通过。本机未启动 Docker daemon，容器检查在 CI 完成。
- 本地与 CI 应用 DEX 的反汇编内容一致（APK 签名、构建元数据可能不同）。
- Android 11 模拟器：5 项通过，0 失败、0 错误、0 跳过，已解析运行产出的 JUnit XML。[完整 CI 运行](https://github.com/ray204/FeishuWatch/actions/runs/41054808817)（验证源码提交 `0cde6c5`）。

## Requirement | Evidence

| Requirement | Evidence |
|---|---|
| 首次扫码绑定、浏览器状态校验、绑定结果可安全重试 | `pairing_requires_browser_state_and_poll_secret_then_retries_safely` |
| 过期或取消授权不能绑定设备 | `expired_pair_and_oauth_state_cannot_be_used`、`oauth_cancel_and_rebinding_do_not_leak_or_replace_connected_credentials` |
| 后台续期与轮换凭证持久化 | `refresh_is_serialized_and_rotated_credentials_survive_restart` |
| 一次性刷新响应不明时不盲目重放 | `ambiguous_refresh_is_not_replayed_and_previous_snapshot_survives` |
| 服务端凭证加密及篡改检测 | `store_encrypts_credentials_and_detects_tampering` |
| 用户主日历、重复日程实例及全天日期 | `feishu_client_uses_user_primary_calendar_and_instance_view`、`normalization_preserves_all_day_dates_and_instance_identity` |
| 取消/拒绝/移除过滤；失败不清空有效缓存 | `normalization_filters_cancelled_declined_and_removed_instances`、`failed_sync_keeps_snapshot_and_successful_empty_sync_removes_events` |
| 会前/准点边界、调度抖动、重排与去重 | `reminderBeforeAtAndAfterBoundary`、`zeroOffsetToleratesAlarmDispatchJitterButNotHistoricalCatchup`、`deliveredStateSurvivesRecomputationAndRescheduledInstanceGetsNewKey` |
| 过期缓存和全天日程不触发提醒 | `expiredCacheAndAllDayEventsNeverAlert` |
| 同时日程及稍后提醒不改变日程时间 | `simultaneousEventsBothAlertAndSnoozeDoesNotChangeEventTime` |
| 无需打开 Activity，由系统闹钟投递通知 | `systemAlarmDeliversAfterActivityLeavesForeground` |
| 通知上的稍后提醒操作与完整五分钟时间范围 | `cachedReminderPostsWithoutActivityAndSnoozesThroughSystemAction` |
| 同步取消后撤销系统通知、持久化替换缓存 | `cancellationRemovesDeliveredNotificationAndSnapshotReplacementPersists` |
| Android Keystore 加密及重建后读取 | `deviceSecretUsesKeystoreAndSurvivesStoreRecreation` |
| 手表尺寸界面渲染 | `watchSizedDashboardRendersWithFixtureData`，372×430 模拟器截图；非 OPPO 系统截图 |
| 标准小组件 | `AgendaWidget.java`、布局与 provider 注册已构建；OPPO 宿主支持待真机验证 |
| 重启恢复 | `ReminderReceiver` 的 BOOT_COMPLETED 路径已实现；实机重启场景待验收，不声称自动测试已覆盖 |
| 源码、APK、部署包及 GitHub 管理 | `scripts/package.sh`、仓库 Actions 与 Issues；交付包附 SHA256SUMS |

## 实际边界

模拟器验证 Android 标准系统服务，不验证 OPPO 厂商通知中心、息屏震动、轻智能模式或 OPPO 私有卡片。没有将标准 AppWidget 当作已适配的 OPPO 原生卡片。

周期同步受系统调度限制；服务端另有 60 秒短缓存，手动刷新也可能返回最近一分钟的快照。离线期间无法得知临时改期或取消，24 小时有效期过后暂停缓存提醒。未实施跨设备蓝牙同步或原生系统日历写入。

## v0.2 个人直连验证

- 本地 Java 单元测试：17 项（原提醒策略 6 项 + 直连协议 11 项），已解析 XML，无失败/错误/跳过。
- 原服务端测试：14 项通过。直连模式不使用该服务，但保留旧模式回归。
- 构建成功，lint 0 错误、19 条警告。Android 系统级测试新增两项凭证导入/加密/清除验证，最终结果以本版本 CI 为准。
- 未取得真实应用凭证前，授权协议使用虚构响应验证；不声称真实飞书授权已成功。
- 真机已有息屏提醒失败证据，见 DEVICE_TEST.md 与 Issue 1。该限制不由直连模式解决。

| Requirement | Evidence |
|---|---|
| 无自建回调服务器的设备授权 | `deviceAuthorizationPollsFeishuWithoutCallbackServer` |
| 轮询限速、重建恢复与网络失败退避 | `slowDownSurvivesRecreationAndPreventsEarlyPoll`、`networkFailurePreservesPollThrottle` |
| 取消、拒绝与过期不能继续消费设备码 | `cancelRemovesPendingAuthorizationButKeepsAppCredentials`、`deniedAndExpiredAuthorizationsCannotBePolledAgain` |
| 拒绝未知授权域名与不完整令牌 | `unsafeVerificationAddressAndIncompleteTokensAreRejected` |
| 续期轮换、重建恢复及不确定结果不重放 | `refreshRotatesTokensBeforeCalendarReadAndSurvivesRestart`、`ambiguousRefreshIsNotReplayedAfterRestart` |
| 失效身份要求重授权；权限或数据错误保留凭证 | `revokedUserTokenRequiresReauthorizationWithoutLeakingProviderError`、`permissionFailureKeepsCredentialsAndMalformedCalendarIsRejected` |
| 日程实例、全天日期及取消过滤 | `normalizationPreservesInstancesAllDayDatesAndFiltersRemovedEvents` |
| 本机密钥加密、临时文件删除及断开清除 | `directCredentialsAreEncryptedImportedOnceAndClearedOnDisconnect`（Android 系统测试） |
| 错误配置不能覆盖既有凭证 | `malformedImportIsDeletedWithoutReplacingExistingCredentials`（Android 系统测试） |
