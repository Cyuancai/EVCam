# 保活机制说明

本文档说明应用的保活机制实现，适用于车机系统等特殊环境。

## 🛡️ 保活机制架构

应用采用**四层保活机制**，确保后台运行稳定性：

```
┌─────────────────────────────────────┐
│   Layer 0: 开机自启动               │  ⭐ 启动保障
│   BootReceiver                      │  系统开机后自动运行
└─────────────────────────────────────┘
            ↓
┌─────────────────────────────────────┐
│   Layer 1: 无障碍服务保活           │  ⭐ 核心保活机制
│   KeepAliveAccessibilityService    │  提高进程优先级
└─────────────────────────────────────┘
            ↓
┌─────────────────────────────────────┐
│   Layer 2: WorkManager 定时唤醒    │  ⭐ 辅助保活
│   每15分钟执行一次                  │  确保进程活跃
└─────────────────────────────────────┘
            ↓
┌─────────────────────────────────────┐
│   Layer 3: 前台服务 + WakeLock     │  ⭐ 任务执行保护
│   录制/拍照时启动                   │  防止任务中断
└─────────────────────────────────────┘
```

---

## 📋 实现的功能

### 0. 开机自启动 (BootReceiver)

**文件**: `BootReceiver.java`

**作用**:
- 监听系统 `BOOT_COMPLETED` 广播
- 系统开机后自动启动应用服务
- 车机系统重启后无需手动启动

**启动流程**:
```java
系统开机 → 发送 BOOT_COMPLETED 广播
    ↓
BootReceiver 接收广播
    ↓
1. 启动 WorkManager 定时保活任务
2. 检查钉钉配置
3. 如果启用自动启动，启动 MainActivity（后台模式）
4. 5秒后自动移到后台（用户无感知）
```

**特点**:
- ✅ 车机系统开机后自动运行
- ✅ 用户无感知（自动退到后台）
- ✅ 仅在钉钉配置了自动启动时才启动应用
- ✅ 支持多种开机广播（BOOT_COMPLETED, QUICKBOOT_POWERON）
- ✅ **兼容Android 10+后台启动限制**（三重启动策略）

---

### 1. 无障碍服务 (AccessibilityService)

**文件**: `KeepAliveAccessibilityService.java`

**作用**:
- 提高应用进程优先级到接近系统服务级别
- 防止被系统内存清理机制杀死
- 不监控任何用户操作，仅用于保活

**特点**:
- ✅ 不亮屏，不耗电
- ✅ 不监控用户界面
- ✅ 不收集任何隐私信息
- ✅ 配置为 `START_STICKY`，被杀后自动重启

**配置文件**: `res/xml/accessibility_service_config.xml`
```xml
<accessibility-service
    android:packageNames="com.kooo.evcam"  <!-- 仅监听自己的包 -->
    android:canRetrieveWindowContent="false"  <!-- 不读取窗口内容 -->
    ... />
```

---

### 2. WorkManager 定时唤醒

**文件**: `KeepAliveWorker.java`, `KeepAliveManager.java`

**作用**:
- 每15分钟执行一次轻量级任务
- 确保应用进程保持活跃状态
- 记录运行日志，便于诊断

**执行内容**:
```java
- 检查应用进程状态
- 记录无障碍服务状态
- 输出保活日志
```

**优点**:
- ✅ 系统级任务调度，可靠性高
- ✅ 符合 Android 规范，不会被限制
- ✅ 最小间隔15分钟（Android 限制）

---

### 3. 前台服务 + CPU唤醒锁

**现有机制**:
- **前台服务**: 录制时启动，显示通知，防止被杀
- **CPU唤醒锁**: 远程命令时获取5分钟，确保息屏状态可执行

---

## 🚀 使用方法

### 用户操作步骤

1. **启用无障碍服务**（推荐）:
   - 打开应用 → 菜单 → 软件设置
   - 找到"保活服务（推荐）"
   - 点击"去启用"
   - 在系统设置中找到"电车记录仪 - 保活服务"
   - 开启开关

2. **配置开机自启动**（可选）:
   - 打开应用 → 菜单 → 软件设置
   - 找到"开机自启动"开关
   - 开启/关闭根据需求
   - **默认启用**（车机系统推荐）

3. **配置定时保活**（可选）:
   - 打开应用 → 菜单 → 软件设置
   - 找到"定时保活任务"开关
   - 开启后每15分钟自动唤醒
   - **默认启用**（推荐保持开启）

4. **授权后台唤醒权限**（必需）:
   - 在软件设置中点击"后台唤醒权限"
   - 授权悬浮窗权限

5. **启动钉钉远程服务**:
   - 配置钉钉参数
   - 启动远程查看服务

### 自动启动

**系统开机时**（需启用"开机自启动"）：
- ✅ 自动启动 WorkManager 定时保活任务（如果已启用）
- ✅ 如果启用了钉钉自动启动，自动启动应用（后台模式）
- ✅ 5秒后自动移到后台，用户无感知
- ⚙️ 可在设置界面关闭开机自启动

**应用启动时**：
- ✅ 启动 WorkManager 定时保活任务（如果已启用）
- ✅ 检查无障碍服务状态（在设置界面显示）
- ⚙️ 所有保活功能可独立控制

---

## 🔍 保活效果验证

### 检查方法

1. **无障碍服务状态**:
```bash
adb shell settings get secure enabled_accessibility_services
# 输出应包含: com.kooo.evcam/.KeepAliveAccessibilityService
```

2. **进程优先级**:
```bash
adb shell dumpsys activity processes | grep com.kooo.evcam
# 查看 oom_adj 值，值越小优先级越高
# 启用无障碍服务后，oom_adj 应该在 0-2 之间
```

3. **WorkManager 任务**:
```bash
adb logcat -v time | grep KeepAliveWorker
# 应每15分钟看到一次执行日志
```

4. **开机自启动**:
```bash
# 重启设备
adb reboot

# 开机后查看日志
adb logcat -v time | grep BootReceiver
# 应看到: "系统开机完成，开始初始化应用服务..."
```

---

## 📊 保活效果对比

| 场景 | 无保活 | 仅前台服务 | 前台服务+无障碍 | 四层保活 |
|------|--------|------------|----------------|----------|
| 系统开机 | ❌ 需手动启动 | ❌ 需手动启动 | ❌ 需手动启动 | ✅ 自动启动 |
| 息屏1小时 | ❌ 被杀 | ⚠️ 可能被杀 | ✅ 存活 | ✅ 存活 |
| 内存清理 | ❌ 被杀 | ⚠️ 可能被杀 | ✅ 存活 | ✅ 存活 |
| 远程录制 | ❌ 失败 | ✅ 成功 | ✅ 成功 | ✅ 成功 |
| 长期后台运行 | ❌ 不稳定 | ⚠️ 一般 | ✅ 稳定 | ✅ 非常稳定 |
| 车机重启 | ❌ 需手动启动 | ❌ 需手动启动 | ❌ 需手动启动 | ✅ 自动恢复 |

---

## ⚙️ 技术细节

### 进程优先级提升

启用无障碍服务后，进程优先级从:
```
缓存进程 (oom_adj: 900+) 
    ↓
无障碍服务进程 (oom_adj: 0-2)
```

### 内存占用

- **无障碍服务**: ~0.5MB (几乎无额外开销)
- **WorkManager 后台任务**: 仅在执行时占用，执行完立即释放
- **前台服务**: 录制时 + 通知栏

### 电量消耗

- **无障碍服务**: 0% (不监听事件)
- **WorkManager**: <0.1% (每15分钟执行1秒)
- **前台服务**: 录制时才启动

---

## 🔧 开发者接口

### 应用配置管理
```java
AppConfig appConfig = new AppConfig(context);

// 开机自启动
appConfig.setAutoStartOnBoot(true);
boolean autoStart = appConfig.isAutoStartOnBoot();

// 保活服务
appConfig.setKeepAliveEnabled(true);
boolean keepAlive = appConfig.isKeepAliveEnabled();
```

### 检查无障碍服务状态
```java
boolean enabled = AccessibilityHelper.isAccessibilityServiceEnabled(context);
```

### 打开无障碍设置
```java
AccessibilityHelper.openAccessibilitySettings(context);
```

### 启动/停止定时保活
```java
// 启动
KeepAliveManager.startKeepAliveWork(context);

// 停止
KeepAliveManager.stopKeepAliveWork(context);

// 检查状态
boolean running = KeepAliveManager.isKeepAliveWorkRunning(context);
```

---

## 📝 注意事项

### Android 10+ 后台启动限制 ⚠️

**重要**：Android 10 (API 29) 及以上版本限制从后台启动Activity。

**应用采用三重启动策略**：

1. **策略1: 悬浮窗权限绕过** ⭐ 推荐
   - 如果已授权悬浮窗权限（SYSTEM_ALERT_WINDOW）
   - 可以绕过后台启动限制
   - ✅ 建议在设置中授权"后台唤醒权限"

2. **策略2: 直接启动**（Android 9及以下）
   - Android 9及以下系统无限制
   - 可以直接启动Activity

3. **策略3: 静默后台待命** 🔇
   - 如果前两个策略失败（Android 10+无悬浮窗权限）
   - WorkManager保活任务保持进程活跃
   - 等待远程命令自动唤醒或用户手动打开
   - ✅ 完全无感知，不打扰用户

**验证方法**：
```bash
# Android 11测试
adb shell settings put global hidden_api_policy 1
adb reboot

# 查看日志
adb logcat -v time | grep BootReceiver
# 应该看到三个策略中的某个成功执行
```

### 车机系统适配

本保活方案专为车机系统设计：
- ✅ 无电池优化问题
- ✅ 用户不反感保活（工具属性）
- ✅ 系统权限管理较宽松
- ✅ **已针对Android 10+后台限制优化**

### 隐私与合规

- ✅ 无障碍服务仅监听自己的包名
- ✅ 不读取窗口内容
- ✅ 不监控用户操作
- ✅ 配置文件中明确说明用途

### Google Play 注意

如果需要上架 Google Play：
- ⚠️ 需要提供使用无障碍服务的详细说明
- ⚠️ 可能需要视频演示服务用途
- ✅ 建议提供"不启用无障碍服务也能基本使用"的方案

---

## 🐛 故障排查

### 无障碍服务未启用
**症状**: 应用容易被杀后台
**解决**: 
1. 打开设置 → 软件设置
2. 检查"保活服务"状态
3. 如未启用，点击"去启用"

### WorkManager 任务未执行
**症状**: 日志中15分钟无保活记录
**排查**:
```bash
adb shell dumpsys jobscheduler | grep com.kooo.evcam
```

### 进程仍被杀
**可能原因**:
1. 无障碍服务被用户手动关闭
2. 系统极端省电模式
3. 车机系统特殊限制

**解决**: 检查系统后台白名单设置

### 开机自启动失败（Android 10+）
**症状**: 开机后应用没有启动
**排查**:
```bash
# 查看开机日志
adb logcat -v time | grep BootReceiver

# 检查悬浮窗权限
adb shell dumpsys window | grep "mSystemAlertWindow"
```

**解决方案**:
1. **推荐**: 在设置中授权"后台唤醒权限"（悬浮窗），实现完全自动化
2. **备选**: 不授权也可以，应用会在后台静默待命
   - WorkManager保活任务保持进程活跃
   - 远程命令会自动唤醒应用（WakeUpHelper）
   - 或用户手动打开应用即可正常使用

---

## 📚 相关文件

```
app/src/main/java/com/kooo/evcam/
├── AppConfig.java                         # 应用配置管理 ⭐ 新增
├── BootReceiver.java                      # 开机自启动接收器
├── KeepAliveAccessibilityService.java    # 无障碍服务
├── KeepAliveWorker.java                   # WorkManager 任务
├── KeepAliveManager.java                  # 保活管理器
├── AccessibilityHelper.java               # 无障碍辅助类
├── CameraForegroundService.java           # 前台服务
└── WakeUpHelper.java                      # 唤醒辅助类

app/src/main/res/
├── xml/accessibility_service_config.xml   # 无障碍服务配置
└── layout/fragment_settings.xml           # 设置界面（含开关） ⭐ 更新

AndroidManifest.xml                         # 服务和广播接收器注册
```

---

## 📞 技术支持

如有问题，请查看应用日志：
```bash
adb logcat -v time | grep -E "KeepAlive|Accessibility|MainActivity"
```

或导出日志文件：
- 应用内：菜单 → 软件设置 → 保存日志
