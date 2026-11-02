# 已知限制与风险

本文记录当前实现中**已核实**的限制与安全风险，供答辩与后续迭代参考。未验证的内容不在此列。

## 安全模型

### 1. 无签名旧报文放行（兼容性缺口）

`SecurityManager.verifySignature` 在 `packet.signature == null` 时直接返回 `true`，用于兼容未签名的旧客户端。后果：攻击者可发送**无签名的广播报文**绕过验签；接收端只能保证“带签名的报文必须验签通过”，无法保证所有报文都可信。

- 代码位置：`app/src/main/java/com/eazychat/app/mesh/SecurityManager.kt`
- 影响：群聊/频道的来源与完整性保证可被降级绕过。

### 2. 公钥信任根缺失（TOFU）

验签使用的 `signingPublicKey` 直接取自节点 `ANNOUNCE` 中的公钥，没有证书链、指纹校验或用户确认流程（Trust On First Use，且首次即信任）。任何能广播公告的节点都可以声明任意公钥，替换公告即可替换信任根。

- 代码位置：`PeerManager` / `SecurityManager.verifySignature`
- 影响：无法抵抗中间人替换公钥，也无法追溯“某个昵称对应哪把密钥”。

### 3. 无时间戳重放窗口

报文时间戳不参与有效性判定；防重放仅依赖 messageID 的**5 分钟**重复缓存（`AppConstants.Security.MESSAGE_TIMEOUT_MS = 300_000`，清理间隔同值）。超过该窗口后，旧报文可被再次注入并当作新消息处理。

- 影响：无严格防重放保证；演示环境可接受，生产环境需引入时间窗校验。

### 4. 多跳转发依赖发送者公告可达

转发节点验证签名时，需要已缓存原始发送者的 `signingPublicKey`。若该节点从未收到发送者的 `ANNOUNCE`（例如发送者不在其一跳范围内），带签名的报文会被判定为“无公告公钥”并丢弃，而不是继续转发。

- 代码位置：`SecurityManager.verifySignature`（`signingPublicKey == null` → reject）
- 影响：多跳可达性依赖公告先于数据扩散；新加入或分区恢复后的转发成功率可能下降。

## 行为与状态

### 5. 补发队列仅内存

`OfflineMessageQueue` 只存在于进程内存中，按 messageID 去重与线程安全保护；**进程重启或应用被杀后队列清空**。因此补发是“会话内补发”，不是持久化离线消息。

- 代码位置：`app/src/main/java/com/eazychat/app/services/OfflineMessageQueue.kt`、`MessageRouter.kt`
- 对外口径：可以说“会话内补发”，**不可**说“离线消息不丢”。

### 6. UI 状态刷新最多延迟约 4 秒

`ChatViewModel.updateReactiveStates` 每 **4 秒**全量刷新一次（此前为 1 秒）。RSSI 本身仍为 5 秒采样、60 秒过期；在线状态、RSSI 变化与过期感知最多延迟约 4 秒才反映到界面。

- 影响：快速移动或刚断开时，界面数字可能短暂滞后。

## 密码与加密语义

### 7. 私聊与频道

- **私聊**：Noise XX 建立会话密钥，消息端到端加密；不提供独立“私聊口令”。
- **频道密码**：仅频道创建者可通过 `/pass <password>` 设定。首次设定会写入**密码承诺（commitment）**并持久化，作为后续校验基准；校验通过后缓存派生密钥。**离开频道时同时清除密码缓存与承诺**（`ChannelManager.leaveChannel`），需要重新设定或重新输入。
- 限制：密码基准是本地承诺，不做跨设备协商或轮换协议；双方基准不一致时只能通过离开/重设解决。

## 资源与能力边界

- 群聊/频道为**明文广播 + 签名**；私聊为端到端加密。不要表述为“空口全密文”。
- **无智能路由 / AODV**；转发为 TTL 泛洪 + 去重（TTL 7，5 分钟去重窗口）。
- **无边缘 AI、无 Socket-UDP、无地理哈希、无 Nostr、无 Tor**。
- 单文件上限 **1 MB**，发送端与接收端均校验，接收端超限会拒绝落盘并给出系统提示。
