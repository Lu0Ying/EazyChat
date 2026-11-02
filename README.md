# EazyChat

Android 原生 **BLE Mesh P2P 聊天客户端**：设备间通过蓝牙低功耗自行组网与转发，无服务端、无云端账号、无互联网依赖，适合无网络/应急场景的近距离通信演示。

> 诚实口径：**私聊端到端加密；群聊与频道是明文广播 + Ed25519 签名**（签名保证来源与完整性，不提供保密性）。

## 技术栈与版本

| 项 | 版本 / 说明 |
|---|---|
| 语言 | Kotlin 2.2.0 |
| UI | Jetpack Compose（BOM 2025.06.01） |
| 构建 | AGP 8.10.1 + Gradle 8.13（JDK 17） |
| SDK | compileSdk 35 / targetSdk 34 / minSdk 26 |
| 加密库 | BouncyCastle（Ed25519 / Curve25519 / ChaCha20-Poly1305） |
| 应用版本 | versionName 1.5.1（versionCode 26） |

## 已实现功能

- **BLE 发现与自动连接**：广播/扫描发现邻近节点，建立 GATT 直连并自动重试；Android 12+ 使用 `BLUETOOTH_SCAN/CONNECT/ADVERTISE`，旧版本回退到定位权限。
- **群聊与频道**：广播消息经多跳扩散；频道支持加入/离开、成员列表与频道密码（明文内容 + Ed25519 签名，仅频道创建者可设密码）。
- **私聊**：Noise **XX**（`Noise_XX_25519_ChaChaPoly_SHA256`）握手建立会话，消息端到端加密。
- **多跳中继**：默认 TTL 7 跳，逐跳递减；按“发送者 + 报文内容（不含 TTL）”去重，避免环路重复扩散。
- **一键 SOS**：广播带备注的 SOS，接收端以最高优先级通知强提醒，并自动回执；回执不进入聊天时间线。
- **在线人数与 RSSI**：在线人数按最近公告的活跃节点统计（含多跳可见节点）；RSSI 来自直连 GATT，5 秒采样、60 秒过期后不再展示。
- **文件 / 图片 / 语音**：单文件上限 **1 MB**，超限在发送端拦截，对端落盘前再次校验并给出可见提示；大报文自动分片与重组（保留原始签名）。
- **会话内补发**：私聊目标暂时离线时进入内存补发队列，按 messageID 去重；节点重新可见后自动补发。

## 构建与运行

前置：Android Studio（含 SDK Platform 35）、**JDK 17**。

1. 在仓库根创建 `local.properties`，写入本机 SDK 路径（Windows 需转义反斜杠）：

   ```properties
   sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
   ```

2. 设置 `JAVA_HOME` 指向 JDK 17。AGP 8.10.1 / Gradle 8.13 **要求用 JDK 17 运行构建本身**；项目字节码目标仍为 Java 1.8，因此不能用 JDK 21+ 或 JDK 11 代替：

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.1'
   ```

3. 构建与测试：

   ```powershell
   .\gradlew.bat --console=plain :app:assembleDebug :app:testDebugUnitTest
   ```

   APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。当前单元测试共 106 例，0 失败（其中 3 例跳过）。

4. 安装到两台（或多台）Android 8.0+ 设备，授予“附近的设备”与通知权限，打开应用即可自动发现；进入同一频道开始群聊，点选在线节点进入私聊。

## 能力边界（务必如实对外说明）

- 群聊/频道：**明文广播 + 签名**，不是密文；私聊才是端到端加密。
- **无智能路由 / AODV**：转发为按 TTL 的泛洪 + 去重，不做路径发现或按需路由。
- **无边缘 AI、无 Socket-UDP 通道、无地理哈希、无 Nostr、无 Tor**：这些相关资源与依赖已移除或从未实现。
- 文件上限 **1 MB**（发送端与接收端双向校验）。
- 补发**仅会话内有效，进程重启即丢**；不可表述为“离线消息不丢”。
- 更多安全与行为限制见 [docs/known-limitations.md](docs/known-limitations.md)。
