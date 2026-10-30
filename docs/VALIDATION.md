# 验证记录

## 范围

初版按“先交付本地项目和部署包”验收；下方 v0.2 记录包含后续个人直连与真机测试。原服务模式未部署，OPPO 提醒与组件尚未通过验收，跟踪于 [Issue 1](https://github.com/ray204/FeishuWatch/issues/1)、[Issue 2](https://github.com/ray204/FeishuWatch/issues/2)、[Issue 3](https://github.com/ray204/FeishuWatch/issues/3)。

自动化测试数据均为虚构数据。后续个人直连经用户授权在手表读取真实日程，仅记录条数和脱敏状态；不提交日程内容、截图或令牌。没有向物理手机安装应用。

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

- 本地 Java 单元测试：19 项（原提醒策略 6 项 + 直连协议 13 项），已解析 XML，无失败/错误/跳过。
- 原服务端测试：14 项通过。直连模式不使用该服务，但保留旧模式回归。
- 构建成功，lint 0 错误、20 条警告。Android 系统级测试新增两项凭证导入/加密/清除验证，最终结果以本版本 CI 为准。
- 真实授权、主日历同步和 App 进程重启后的登录保留已通过，见下方实测记录；自动续期仍仅由自动化测试覆盖，尚未等待真实令牌到期。
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

- 直连模式首轮 CI 通过：[41060200799](https://github.com/ray204/FeishuWatch/actions/runs/41060200799)，已解析 7 项 Android 系统测试，0 失败/错误/跳过。
- 后续实测发现设备码被消费后仍重试会掩盖原始令牌解析错误，已增加成功响应信封兼容与停止重放：`wrappedOAuthSuccessIsAcceptedForLoginAndTokenRefresh`、`unsafeVerificationAddressAndIncompleteTokensAreRejected`。
- 错误诊断脱敏：`authorizationDiagnosticsRedactAppSecretAndDeviceCode`。

### 真实授权定位结果

2026-10-30 初次真机成功响应为平铺格式，存在 access_token（7200 秒）但缺少 refresh_token。用户启用并发布「安全设置 → 刷新 user_access_token」后，再次扫码得到完整访问令牌与刷新令牌，主日历同步成功，共 9 条。强制结束并冷启动 App 后，授权状态保留，自动同步时间更新且仍为 9 条，无错误。未记录任何令牌或日程内容；自动续期尚未等待两小时后实测。

本次并非 code/data 信封问题；信封支持仅是兼容性补充。实际代码缺陷是收到成功但缺失刷新令牌的响应后继续轮询已消费的设备码，导致原始原因被 invalid_grant 覆盖，已修复。


## v0.2.1 手表界面调整

按真机 466×466、density 320 的圆屏调整：页面标题 24sp → 13sp，日程标题 20/17sp → 13/12sp，辅助信息 13sp → 9–10sp。首页卡片区分日期、时间、标题与地点；标题最多两行并可点击查看全文。首屏只展示日程，操作区位于下一屏；日程列表按日期分组，操作区双列按钮 40dp，其余按钮 44dp 高。二维码缩为 132dp，首屏可完整展示。


详情页按需读取参会人，10sp 小字、最多 10 人，标记组织者；群组展开并去重，隐藏会议室和已移除的参与人。名单只在内存缓存五分钟，不写入仓库或日志；权限不足提示名单不可见，不覆盖日程快照。普通重复实例使用所属系列的名单，单次修改的例外日程保持独立名单。接口依据：[参与人列表](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/calendar-v4/calendar-event-attendee/list)、[群成员列表](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/calendar-v4/calendar-event-attendee-chat_member/list)。

真机检查：v0.2.1 覆盖安装保留登录和 9 条日程；首页无操作按钮，详情可阅读全文。首次周会实例名单接口返回 193001；区分普通实例与例外后，同一周会读取到 5 位参会人且无错误。真实姓名与日程截图仅在本地 Git 元数据目录留存，不随源码或安装包发布。

| Requirement | Evidence |
|---|---|
| 参会人分页过滤与前 10 人上限 | `attendeesPaginateFilterResourcesAndShowFirstTenPeople` |
| 群成员去重及部分名单不可见 | `attendeeGroupsDeduplicatePeopleAndPreserveVisibleNamesOnPartialAccess` |
| 参会人读取自动续期及权限不足保留连接 | `attendeeReadRefreshesCredentialsAndPermissionDenialKeepsConnection` |
| 授权失效不重放 | `attendeeRevokedTokenRequiresReauthorizationWithoutReplayingRefresh` |
| 普通重复实例与例外使用各自名单 | `ordinaryOccurrencesUseSeriesAttendeesButExceptionsKeepTheirOwnList` |
| 首屏不显示操作按钮，下滑可操作 | `dashboardActionsStayBelowFirstScreenAndRemainReachableByScrolling`（等待本提交 CI 执行） |


v0.2.1 新增「日程提醒」总开关，默认关闭（仅查看）。关闭时取消应用的日程闹钟、测试闹钟、稍后提醒和常驻摘要，不清除授权、日程缓存或同步任务。真机升级后确认开关为关闭、9 条日程仍同步，AlarmManager 无本应用待触发闹钟。

- 验证用例：`viewOnlyModeClearsNotificationsAndKeepsCalendarAndCredentials`（默认关闭、清除通知、旧提醒不再触发、日程与凭证保留）。
