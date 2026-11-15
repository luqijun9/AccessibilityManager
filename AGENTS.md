# 项目协作与代码规范 (AGENTS.md)

本项目为 **无障碍管理器 (AccessibilityManager)** 及其基于 Modern Xposed (LibXposed API 102) 运行在 `system_server` 内部的高可靠无障碍自动恢复探针模块。

由于 Android 系统的 `system_server` 内部环境严苛且各 OEM 厂商（ColorOS/HyperOS/OriginOS/MIUI/EMUI 等）后台查杀机制多变，所有协同开发 Agent 必须严格遵守以下规则：

---

## 1. 关键防御性逻辑详尽注释规范 (Mandatory Commenting Rules)

所有涉及无障碍服务保活、防抖、抑制期、重试队列、跨进程通信以及应对 OEM 系统特性的代码，**必须编写清晰、详尽的中文场景注释**，杜绝无说明的魔法数字和直接吞掉事件的裸防御分支。

### 必须包含的三要素：
1. **解决的真实场景与 OEM 行为**：
   - 明确指出防御的具体系统痛点（例如：ColorOS `o-kill(4008)` 在服务刚启动 400ms 内二次击杀、AMS 旧连接解绑延迟 `binderDied` 到达、同应用多服务同进程被杀引发级联死亡、开机/解锁大量服务并发冷启动等）。
2. **前置假设与判断边界**：
   - 明确说明当前分支成立的物理前提（例如：当前是否正在进行 `repair()` 主流程？当前 AMS 中是否还存在存活连接？）。
3. **兜底补偿策略**：
   - **严禁裸 `return;` 彻底吞掉无连接的真正崩溃事件！** 若处于抑制期/冷却期内，必须评估是否需要排期延后检查（`scheduleCheck`）或保留心跳验证（`scheduleVerify`），确保服务在抑制期结束时得到自动补偿恢复。

---

## 2. 状态机与保护机制核心定义

| 机制 / 变量 | 设计初衷 | 应对场景 | 兜底与边界策略 |
| :--- | :--- | :--- | :--- |
| `sSuppressUntil` (抑制期) | 防止关开修复后旧连接延迟的 `binderDied` 造成假崩溃与死循环 | AMS 解绑/强杀异步回调 | **若无存活连接（真死）**：严禁丢弃，必须排期延后检查并在抑制期结束后自动二次修复。 |
| `BOOT_GRACE_MS` (开机冷却) | 防止开机阶段与用户首次解锁时服务冷启动慢被误杀 | 开机 CPU 占满导致启动超时 | 冷却期内跳过修复，但必须排期在开机冷却期结束后执行兜底扫描。 |
| `PACKAGE_KILL_COOLDOWN_MS` | 避免同包多服务同时崩溃时频繁 `killAppProcesses` | 多服务应用（如 MacroDroid） | 10 秒内同包名只执行一次强杀，后续服务复用关开设置。 |
| `sCheckRetries` (启动宽限) | 避免应用正在被系统冷启动拉起时直接强杀修复 | 慢启动应用进程已在但 Binder 未绑 | 检测到应用进程存活时宽限 2 次（每次 2 秒），超时才发起修复。 |

---

## 3. 单元测试与验证规范

- 每次调整策略计算、时间窗口、防抖逻辑或数据解析时，必须在 `RepairPolicy.java` 中实现无系统依赖的纯逻辑方法，并在 `AutoRepairEntryTest.java` 中编写完备的单元测试。
- 代码修改完成后，必须先执行 `./gradlew testDebugUnitTest` 验证通过。

---

## 4. 构建、部署与提交流程 (Strict Execution Order)

按照全局规范，工作流必须严格按顺序执行：
1. **测试验证**：单元测试全部通过。
2. **安装先行**：若修改了 Android 逻辑，必须优先调用全局智能安装脚本：
   ```powershell
   powershell -ExecutionPolicy Bypass -File "C:\Users\LP\.gemini\config\scripts\smart_adb_install.ps1" -ApkPath <apk-path-or-dir>
   ```
3. **Git 提交**：测试与安装流程完成后，立即执行 Git 提交并推送到远程仓库。
