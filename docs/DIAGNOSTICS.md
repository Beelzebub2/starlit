# Optional Starlit diagnostics

Reliability diagnostics and crash reports are **separate opt-in choices**, both
off by default for new installations. Existing crash-report preferences are
preserved. A consent prompt appears after entering the app, and either choice
can be changed in Settings > Advanced. Neither option is required.

## Anonymous reliability diagnostics

Only fixed health codes are accepted:

- **Info:** app launches, successful playback readiness and completed downloads.
- **Warnings:** slow playback startup, mobile download retries and storage-quota pauses.
- **Errors:** playback failures, torrent-engine startup failures and download failures.

The server derives severity and component from each fixed code. OpenObserve
records only app version, platform, code, severity and component. There are
**no screen visits, search events, playback titles, media or episode IDs,
source URLs, server names, account IDs, credentials, stack traces or free-form
error messages** in this stream. The server acknowledges and discards older
clients' screen-visit, player-opening and download-queue-snapshot events.

An opt-in client supplies a random **session-only** token for gateway rate
limiting. The server hashes it only in memory, and **does not store either
the token or its hash**. The app no longer persists a diagnostics installation
identifier and erases an identifier left by older app versions. As with any
network connection, the reverse proxy sees the source address to serve the
HTTPS request; telemetry itself does not save the address or request body.
Configure the reverse proxy not to log these bodies.

Turning off diagnostics cancels queued work and rotates the session token.
Previously ingested records expire according to OpenObserve's configured
30-day retention. Old schema-1 records may temporarily retain a hashed
installation identifier until they expire. Event totals reflect consenting
reports, **not** distinct users, devices or watched titles.
If the network or telemetry server fails, normal app operation continues.

## Smart TV reliability diagnostics (Tizen and webOS)

The TV web app offers an **independent, device-local opt-in** under
Settings > Advanced > Diagnostics. It is off on new TV installations, including
after signing into an existing account; mobile/desktop consent does not enable
TV reporting. Disabling it removes pending events and aborts an in-flight
request on runtimes supporting AbortController.

The TV reports only fixed health codes: app launch, slow startup, known
playback/stall failures, torrent resolver failures, plugin-service failures,
USB-cache warnings, subtitle failures, and uncaught runtime/promise errors.
Unknown console warnings are **not** uploaded. The pre-existing on-device
debug console can still show diagnostic details locally; its free-form
messages, stacks, metadata and URLs never enter telemetry payloads.

The client initializes after first Home paint (or the existing bounded
startup fallback), keeps at most 12 categorical events in volatile memory,
deduplicates the same event type for 60 seconds, and sends at most one
event per 20 seconds, with a cap of 30 sends per app session. It does not
send while the player/source screen is active or while the app is hidden.
Failures are silently discarded, with no disk queue, retransmission,
analytics SDK or per-frame sampling. These limits prioritize playback and
D-pad responsiveness on older TVs over complete error delivery.

An ephemeral random session token is used only for gateway rate limiting;
neither it nor a device identifier is stored in OpenObserve. If secure
randomness, consent storage, or the telemetry endpoint is unavailable,
reporting fails closed. The gateway validates the exact four permitted JSON
fields and derives severity/component from the event's fixed code.
This feature does not install a crash reporter or send crash dumps on TV.
An abrupt power loss, process kill or fatal error before initialization
may therefore produce **no** remote diagnostic event.

Deployment requires updating the gateway to accept Tizen/webOS before
publishing a new TV build, configuring 30-day retention and confirming the
public privacy policy covers TV health signals. Test the release on
resource-constrained Tizen and webOS hardware; passing unit tests is not
proof of zero impact on a particular television.

## Optional crash reporting

Sentry reports are enabled independently of reliability diagnostics. When
explicitly enabled, the Android and desktop SDKs provide stack traces and
limited app, OS and device technical metadata, with the existing request,
user and exception-message sanitization. Screenshots, UI hierarchy,
tracing/replay and breadcrumbs (including navigation and network
destinations) are disabled. iOS Sentry reporting is not currently supported.
Disabling this option prevents future reporting.

## Deployment and operational safeguards

See [gateway configuration](../gateway/README.md#opt-in-usage-diagnostics) for
the private ingestion endpoint, server-only HMAC key and credentials,
request-body and rate limits, schema validation, 30-day retention and
verification. Ingestion fails closed without required settings.
Signing into an account does not opt a device into either diagnostics option.

Before public release, ensure the official website privacy policy describes
the two consent categories, the diagnostic fields, retention and contact for
privacy requests. Check that the public reverse proxy does not log telemetry
request bodies.
