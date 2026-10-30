# OPPO Watch 3 真机验收

设备基线（用户提供）：Android 11，ColorOS Watch V16.0.0，配对 Android 手机。

2026-10-30 USB 实测：厂商 OPPO、型号 OWW261、API 30，物理分辨率 **466×466**，与此前 372×430 模拟器验证的尺寸不同。

结果记录为“未执行 / 通过 / 失败”，不要用 APK 构建成功替代设备结果。测试使用自己创建的无敏感内容日程。

| 场景 | 操作与预期 | 当前结果 |
|---|---|---|
| 安装与布局 | 安装 APK，检查实际屏幕上的按钮、二维码、滚动和返回 | 通过：v0.2 安装、启动、滚动及真实二维码授权通过；v0.2.1 已缩小字体，确认首页只见日程、详情可查看全文及 5 位真实参会人 |
| 首次连接 | 手机扫码，核对码，授权后手表自动出现自己的主日历 | 通过：获得访问与刷新令牌，主日历同步 9 条；App 冷启动后保持连接并再次同步 |
| 息屏系统提醒 | 点击 15 秒测试后回到表盘、息屏，出现通知和震动 | 失败：平衡模式将唤醒闹钟降级为非唤醒；唤醒后投递，通知中心又拒绝展示 |
| App 进程不在前台 | 会前退出 App 等待提醒；勿用“强行停止”，它会主动禁用任务 | 未执行 |
| 离线提醒 | 成功同步未来日程后断开网络，仍在计划时间提醒 | 未执行 |
| 更改与取消 | 在飞书改期/取消，然后手动同步；旧提醒/旧通知消失 | 未执行 |
| 重复日程例外 | 修改一次周会的时间，手表展示修改后的具体实例 | 未执行 |
| 稍后提醒 | 通知点稍后 5 分钟，到时再次提示且不修改飞书 | 未执行 |
| 重启恢复 | 同步后重启手表，恢复本地提醒；已提醒事项不重复 | 未执行 |
| 切时区 | 改变显示时区，时刻显示变化但会议绝对时间不变 | 未执行 |
| 全天日程 | 列表正常显示，不在午夜震动 | 未执行 |
| 后台同步 | 多次息屏 30 分钟后观察同步时间与改期传递延迟 | 未执行 |
| 勿扰与渠道 | 关闭/打开渠道、开启勿扰，行为遵循系统设置 | 未执行 |
| 常驻摘要 | 打开摘要开关，通知中心能查看下一场和刷新 | 未执行 |
| 标准组件 | 检查是否支持添加，记录宿主；不支持不等于 App 故障 | 未执行 |
| 凭证续期 | 保持手表联网，初始 access token 到期后继续直连同步 | 未执行 |
| 断开连接 | 断开本表连接，本地凭证、缓存和所有通知提醒清除 | 未执行 |

只读诊断命令（多设备加 `-s`）：

```bash
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id
adb shell dumpsys alarm | rg io.github.ray204.feishuwatch
adb shell dumpsys jobscheduler | rg io.github.ray204.feishuwatch
adb shell dumpsys package io.github.ray204.feishuwatch
```

日志中不要公开真实日程或授权凭证。首先用测试提醒确认通知路径，再连接真实日程；若通知已投递但 OPPO 通知中心不显示，优先核实厂商适配接口，不通过私有 API 或强制弹窗绕过。

## 2026-10-30 真机证据

- 应用调用 `setExactAndAllowWhileIdle(RTC_WAKEUP, ...)`，实际记录变为 `RTC type 1`，息屏到期进入 `Past-due non-wakeup alarms`。
- 固件日志：`[BmPowerManager] convert thirdapp(io.github.ray204.feishuwatch) wakeup alarm(tag:null) to non-wakeup when balance!`。
- 唤醒后 NotificationManager 出现测试通知 id=12、importance=4；SystemUI 对该通知记录 `can not post!`。
- 渠道 vibrationEnabled=true 仅证明配置，不能证明实际震动；尚未得到触觉确认。
- 没有修改固件白名单、全局省电或勿扰。公开设置入口被充电界面覆盖，尚未找到可验证的解除设置。
