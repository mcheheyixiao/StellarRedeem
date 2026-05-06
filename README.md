# StellarRedeem

StellarRedeem is an independent code-redeem plugin. It only handles `/redeem`, calls StellarWorld, executes returned console commands, and sends complete/fail callbacks.

## What It Does Not Do

- No web admin panel
- No local redeem-code database
- No direct MySQL access
- No dependency on StellarStatsSync
- No offline reward queue
- No automatic command rollback

## Commands

- `/redeem <code>`
- `/cdkey <code>`
- `/key <code>`

## Permissions

- `stellarredeem.redeem` (default: `true`)
- `stellarredeem.admin` (default: `op`, bypasses redeem cooldown)

## config.yml

```yaml
api:
  base-url
  server-id
  server-secret
  timeout-ms

redeem:
  cooldown-seconds
  case-insensitive

command:
  stop-on-first-failure
  log-executed-commands

heartbeat:
  enabled
  interval-seconds
```

Important:

- `server-secret` must match StellarWorld `REDEEM_PLUGIN_SERVER_SECRET`.
- `server-id` must match StellarWorld `REDEEM_PLUGIN_SERVER_ID`.
- Never commit `server-secret` to a public repository.

## API Signature

Headers:

- `X-Stellar-Server-Id`
- `X-Stellar-Timestamp`
- `X-Stellar-Signature`

Signature format:

```text
hmac_sha256(timestamp + "." + raw_body, server_secret)
```

## Required Website Environment Variables

```dotenv
REDEEM_CODE_PEPPER=
REDEEM_PLUGIN_SERVER_ID=survival-1
REDEEM_PLUGIN_SERVER_SECRET=
REDEEM_PLUGIN_TIME_WINDOW_SECONDS=300
```

For realtime event sync:

```dotenv
REALTIME_INTERNAL_EVENT_URL=http://127.0.0.1:3001/internal/events
REALTIME_INTERNAL_SECRET=
```

## Build

```bash
./gradlew build
```

Artifact:

`build/libs/StellarRedeem-1.0.0.jar`

Dependency strategy:

- Gson is set as `compileOnly("com.google.code.gson:gson:2.11.0")`.
- Gson is not bundled into the plugin jar.
- Runtime must provide Gson (Paper 1.21+ commonly does).

## Install

1. Put the jar into `plugins/`.
2. Start the server once to generate `config.yml`.
3. Stop server or reload first, then set `server-secret`.
4. Restart server.
5. Test with `/redeem <code>`.

## FAQ

- `api.server-secret is CHANGE_ME`: redeem is disabled by plugin.
- HTTP `401` from website: `server-id` or `server-secret` mismatch, or server clock drift exceeds the allowed window.
- Redeem success but no realtime admin refresh: check StellarWorld -> StellarRealtime `/internal/events` config.
- Command execution failed: backend will receive `failed`; V1 does not auto-rollback commands.
- Code always invalid after deploy: verify pepper settings; changing pepper invalidates old codes.

## Security Notes

- Do not log full `server-secret`.
- Do not log full `X-Stellar-Signature`.
- Full redeem code is masked in failure log output.
- `command.log-executed-commands` logs full executed commands. Disable it if commands may contain sensitive data.
