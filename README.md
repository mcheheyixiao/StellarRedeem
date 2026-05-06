# StellarRedeem

StellarRedeem 是独立卡密兑换插件（Redeem V1/V2/V3）。

它只负责：

- `/redeem` 触发兑换
- 调用网站 claim API
- 在主线程执行网站返回命令
- 回传 complete/fail
- 在网络抖动时重试 complete/fail 回调

它不负责本地卡密库，不直接连接数据库，不内嵌 Web 后台。

## Redeem V3：精准投放 / 限定兑换 / 多服规则

V3 插件端职责保持不变，并新增可选上下文上报：

- 插件不做规则判断
- 插件只提交玩家上下文
- 规则由 StellarWorld 统一判断

## V2 新增能力

- Callback retry queue（仅 complete/fail 回传）
- 管理命令 `/stellarredeem`
- Debug 日志开关
- 启动检查增强

## V3 新增能力

- claim payload 增加可选上下文（world / serverVersion / onlinePlayers / playerIp）
- claim 失败 reason 映射增强（支持限定服务器、限定玩家、账号状态类原因）
- `/stellarredeem status` 显示 context 开关

## 严格边界

- 不保存卡密数据库
- 不接 MySQL
- 不做卡密规则判断
- 不重试 claim
- 不重新执行奖励命令
- 不自动补发奖励
- 不改变网站 API 协议和签名格式

## 命令

玩家兑换：

- `/redeem <code>`
- `/cdkey <code>`
- `/key <code>`

管理命令（`stellarredeem.admin`）：

- `/stellarredeem reload`
- `/stellarredeem status`
- `/stellarredeem testapi`

## 权限

- `stellarredeem.redeem`（default: true）
- `stellarredeem.admin`（default: op）

## 配置

```yaml
api:
  base-url: "https://www.stellarvan.cn"
  server-id: "survival-1"
  server-secret: "CHANGE_ME"
  timeout-ms: 5000

redeem:
  cooldown-seconds: 5
  case-insensitive: true
  allow-console: false

command:
  stop-on-first-failure: true
  log-executed-commands: true

heartbeat:
  enabled: true
  interval-seconds: 60

debug:
  enabled: false

callback-retry:
  enabled: true
  file: "callback-queue.json"
  interval-seconds: 30
  max-attempts: 10
  max-queue-size: 500

context:
  include-world: true
  include-server-version: true
  include-online-players: true
  include-player-ip: false
```

## V3 上下文配置说明

- `context.include-world`：claim payload 是否传世界名
- `context.include-server-version`：claim payload 是否传服务端版本
- `context.include-online-players`：claim payload 是否传在线人数
- `context.include-player-ip`：claim payload 是否传玩家 IP（默认 `false`，不建议开启）

隐私说明：

- `include-player-ip` 默认关闭
- 若启用，请确保你的隐私政策与服务器规则明确说明用途

## Callback Retry 机制

- 只重试 `complete` / `fail` 回传
- 不重试 `claim`
- 不重新执行命令
- 不保存卡密
- 不改变奖励发放状态

队列文件：

- `plugins/StellarRedeem/callback-queue.json`

队列条目字段：

- `type`（`COMPLETE` / `FAIL`）
- `redeemId`
- `executedCommands`
- `failedCommand`
- `error`
- `attempts`
- `createdAt`
- `lastAttemptAt`

超过 `max-attempts` 后条目会保留在队列文件中并停止自动重试（用于人工排查）。

## 管理命令说明

`/stellarredeem reload`

- 重载 `config.yml`
- 重建 API client / RedeemService
- 重启 heartbeat task
- 重启 callback retry task

`/stellarredeem status`

- 显示插件状态、Redeem 开关、Base URL、Server ID、Heartbeat、Callback Retry、队列长度、Debug 状态、Context 开关
- 不显示 `server-secret`

`/stellarredeem testapi`

- 异步发送一次 heartbeat 用于连通性测试
- 不调用 claim，不执行奖励命令

## 启动检查

启动时会检查：

- `api.base-url` 非空
- `api.server-id` 非空
- `api.server-secret` 非空
- `api.timeout-ms > 0`
- callback queue 文件可读写（启用 callback-retry 时）

启动日志会输出：

- StellarRedeem enabled
- Redeem enabled: true/false
- API base URL
- Server ID
- Heartbeat enabled/disabled
- Callback retry enabled/disabled

不会输出 secret。

## API 签名

Headers:

- `X-Stellar-Server-Id`
- `X-Stellar-Timestamp`
- `X-Stellar-Signature`

签名格式：

```text
hmac_sha256(timestamp + "." + raw_body, server_secret)
```

## V3 claim 失败 reason 映射

规则：

1. `reason` 属于 API 错误类时，显示 `messages.api-error`
2. 若网站返回 `message` 非空，优先显示网站 `message`
3. `reason` 属于无效/限制类时，显示 `messages.invalid`
4. 其他显示 `messages.failed`

无效/限制类（映射到 `invalid`）：

- `invalid_code`
- `revoked`
- `expired`
- `used_up`
- `category_disabled`
- `server_not_allowed`（服务器不允许）
- `player_not_allowed`（玩家不匹配）
- `bound_account_required`（需要绑定网站账号）
- `email_not_verified`（需要邮箱验证）
- `account_not_active`（账号状态异常）
- `per_player_limit_reached`（该玩家达到兑换次数限制）
- `per_account_limit_reached`（该网站账号达到兑换次数限制）

API 错误类（映射到 `api-error`）：

- `rule_invalid`
- `internal_error`
- `server_auth_failed`

## 故障处理建议

- 队列持续增长：优先检查网站 API 可用性、`server-secret`、网络连通性。
- 出现 exhausted 条目：人工对照网站 `redeem_logs` 和服务器日志排查。

## 构建

```bash
./gradlew build
```

产物：

- `build/libs/StellarRedeem-1.0.0.jar`
