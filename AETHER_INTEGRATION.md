# ACS 侧实施文档：Aether Build Service

## 背景

Aether 是一个 AI agent APP（包名待确认），通过 AIDL Bound Service 调用 ACS 的 Gradle 构建能力。
ACS 需要暴露一个 exported Service，让 Aether 可以：初始化项目、执行 Gradle task、运行 shell 命令、查询项目模型。

**ACS 的角色：** 构建后端 + 环境提供者。不需要关心 Aether 的 AI 逻辑。
**文件共享：** 两个 APP 通过 `/sdcard` 上的项目目录共享文件。

---

## AIDL 接口定义

在 `core/app/src/main/aidl/com/tom/rv2ide/services/builder/` 下创建两个文件：

### `IAetherBuildService.aidl`

```aidl
package com.tom.rv2ide.services.builder;

import com.tom.rv2ide.services.builder.IAetherBuildCallback;

interface IAetherBuildService {
    // 状态查询
    boolean isToolingServerStarted();
    boolean isBuildInProgress();
    String getSdkPath();
    String getJavaHome();

    // 构建操作（异步，通过 callback 返回进度和结果）
    void initializeProject(String projectDir, IAetherBuildCallback callback);
    void executeTasks(in List<String> tasks, IAetherBuildCallback callback);
    void executeShell(String command, IAetherBuildCallback callback);
    void cancelBuild();

    // 项目模型（JSON 字符串传递，避免 Parcelable 复杂度）
    String getProjectTree(String rootDir);
    String getModulesJson();
    String getVariantsJson(String modulePath);
    String getAvailableTasksJson(String modulePath);

    // 回调管理
    void registerCallback(IAetherBuildCallback callback);
    void unregisterCallback(IAetherBuildCallback callback);
}
```

### `IAetherBuildCallback.aidl`

```aidl
package com.tom.rv2ide.services.builder;

oneway interface IAetherBuildCallback {
    void onBuildStarted(String buildInfoJson);
    void onBuildSuccessful(in List<String> tasks);
    void onBuildFailed(in List<String> tasks, String errorMessage);
    void onOutput(String line);
    void onProgressEvent(String eventJson);
    void onProjectInitialized(boolean success, String message);
}
```

---

## 新建 `AetherBuildAidlService`

**文件：** `core/app/src/main/java/com/tom/rv2ide/services/builder/AetherBuildAidlService.kt`

### 职责

- 继承 `Service`，`onBind()` 返回 `IAetherBuildService.Stub()` 实现
- 通过 `Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)` 获取已有的 `GradleBuildService` 实例
- 将 `CompletableFuture<T>` 结果桥接到 `IAetherBuildCallback` 回调
- 用 `RemoteCallbackList<IAetherBuildCallback>` 管理多个外部监听者
- 项目模型查询：调用 `GradleBuildService` 内部的 `IProject` 代理，序列化为 JSON 返回

### 关键设计

- **不修改 `GradleBuildService` 本身**，AIDL Service 是独立的外观层（Facade）
- `initializeProject` 参数简化为 `String projectDir`（内部构造 `InitializeProjectParams`）
- `getModulesJson()` 等返回 JSON 字符串而非 Parcelable（降低跨 APP 耦合）
- `executeShell` 委托给 ACS 的 Termux 环境（`TermuxTaskExecutor` 或类似机制）
- 参考项目已有的 AIDL 模式：`ILogSender.aidl`（双向回调）和 `ILogWireService.aidl`（简单接口）

### Aether 的调用方式（供理解上下文）

Aether 通过以下方式连接：
```kotlin
val intent = Intent("com.tom.rv2ide.action.AETHER_BUILD_SERVICE").apply {
    setPackage("com.tom.rv2ide")
}
context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

- 使用 `BIND_AUTO_CREATE`，ACS 的 Service 会被自动拉起
- Aether 首次调用时如果 Tooling Server 未启动，需要 ACS 侧自动启动
- 每次 `executeTasks`/`executeShell` 传入一个独立的 callback 实例（Aether 内部绑定 run_id）

### 日志截断

Binder 事务缓冲限制 ~1MB。`onOutput` 回调的单条消息不应超过此限制。
建议 ACS 侧对单行输出不做截断（通常不会超），但 `getProjectTree` 等返回值如果超大，应截断到合理大小（如 256KB）。

---

## Manifest 声明

**文件：** `core/app/src/main/AndroidManifest.xml`

添加：

```xml
<permission
    android:name="com.tom.rv2ide.permission.BIND_BUILD_SERVICE"
    android:protectionLevel="normal" />

<service
    android:name=".services.builder.AetherBuildAidlService"
    android:exported="true"
    android:permission="com.tom.rv2ide.permission.BIND_BUILD_SERVICE">
    <intent-filter>
        <action android:name="com.tom.rv2ide.action.AETHER_BUILD_SERVICE" />
    </intent-filter>
</service>
```

---

## 涉及的文件

| 文件 | 操作 |
|------|------|
| `core/app/src/main/aidl/com/tom/rv2ide/services/builder/IAetherBuildService.aidl` | 新建 |
| `core/app/src/main/aidl/com/tom/rv2ide/services/builder/IAetherBuildCallback.aidl` | 新建 |
| `core/app/src/main/java/com/tom/rv2ide/services/builder/AetherBuildAidlService.kt` | 新建 |
| `core/app/src/main/AndroidManifest.xml` | 添加 service + permission 声明 |
| `version.properties` 或 `settings.gradle.kts` | 版本号 bump |

---

## 验证方式

1. 安装 ACS 后，用 `adb shell am startservice` 或测试 APP bind `AetherBuildAidlService`，验证能获取到 binder
2. 调用 `isToolingServerStarted()` 验证状态查询正常
3. 调用 `initializeProject("/sdcard/projects/TestApp", callback)` 验证项目同步
4. 调用 `executeTasks([":app:assembleDebug"], callback)` 验证构建 + 回调流
5. 调用 `executeShell("gradle --version", callback)` 验证 shell 执行
6. 调用 `getModulesJson()` 验证项目模型返回

---

## 现有架构参考

### GradleBuildService 关键接口

```kotlin
// 已有的 BuildService 接口方法（可直接委托）
fun isToolingServerStarted(): Boolean
fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult>
fun executeTasks(vararg tasks: String): CompletableFuture<TaskExecutionResult>
fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult>
```

### EventListener 模式（AIDL callback 对应）

```kotlin
// 已有的 UI 回调接口，AIDL callback 镜像此模式
interface EventListener {
    fun prepareBuild(buildInfo: BuildInfo)
    fun onBuildSuccessful(tasks: List<String?>)
    fun onProgressEvent(event: ProgressEvent)
    fun onBuildFailed(tasks: List<String?>)
    fun onOutput(line: String?)
}
```

### 获取 GradleBuildService 实例

```kotlin
val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE) as? GradleBuildService
```

### 启动 Tooling Server（如果未启动）

```kotlin
if (!buildService.isToolingServerStarted()) {
    buildService.startToolingServer { pid -> /* server ready */ }
}
```
