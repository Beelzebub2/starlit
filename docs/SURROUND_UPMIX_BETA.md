# Stereo surround enhancement (Windows experimental beta)

The desktop player's opt-in PCM upmixer uses the existing libmpv/FFmpeg
audio-filter pipeline. It does **not** recreate the original discrete surround
master and should not be described as native multichannel content.

## Availability and user controls

- Supported initially on 64-bit Windows. Physical 5.1 output requires an exact
  six-channel default shared-mode mix; the two headset modes require their
  explicitly requested multichannel PCM format to be accepted by the Windows
  shared-mode output. Other platforms do not expose the settings yet.
- In **Settings > Playback > Surround audio**, switch on the experimental
  **Stereo to 5.1 surround** option. It is off by default.
- **Automatic surround** is a separate, default-off opt-in that tests the
  current Windows audio route and attempts compatible PCM layouts without
  requiring manual mode changes. An exact physical 5.1/7.1 shared-mode mix is
  preferred as-is. On a stereo shared-mode mix, it tries exact 7.1 PCM, then
  5.1(side), then 5.1(back), only when the driver explicitly accepts each
  format. If a filter is rejected or mpv fails to report the requested actual
  output channels after three checks, the next candidate is attempted.
  Exhausting the candidates restores the original audio rather than repeatedly
  retrying a failed layout. Failed formats are remembered for that player,
  output endpoint and audio track until the route, track or selected mode
  changes. Auto never modifies Windows device defaults or installs a driver.
- **Stereo to headset 5.1 PCM** and **Stereo to headset 7.1 PCM** are separate,
  default-off modes for compatible audio drivers, regardless of manufacturer.
  The former sends a six-channel 5.1(side) or 5.1(back) stream; the latter sends
  eight-channel 7.1. Both can work when Windows advertises a stereo *mix*
  format but its driver/processing object accepts the exact stream format.
  The headset/vendor spatial-surround setting remains controlled externally.
  Sending PCM to a stereo device is **not** proof of virtualization: without
  vendor or Windows HRTF, ordinary stereo downmixing may be all that occurs.
  Starlit does not apply its own HRTF on these paths and avoids double processing.
- Selecting one upmix mode disables the others. Existing manual preferences
  survive the update; Auto must be enabled explicitly. The in-player
  **Auto/5.1/7.1** button toggles the most recently selected preference. Its label and
  tooltip distinguish *active*, *waiting/incompatible*, and *rejected* states.
- The preference is stored locally, not synced across accounts. Route-specific
  preferences and an automated subjective speaker test are future enhancements.

## Safety policy

1. Only the decoded two-channel stereo track is eligible.
2. Windows' multimedia and console default endpoint must match. For physical
   5.1, the shared-mode mix format must advertise an exact six-channel
   speaker mask; unknown, stereo-only and eight-channel routes remain unchanged
   in that physical mode.
3. The filter is only applied to the primary mpv player when it uses its
   default audio device; explicitly routed playback and passthrough are
   ineligible. Native 5.1/7.1 sources are never upmixed.
4. The named `@starlit-surround` filter is added and removed without clearing
   other user or playback filters. An audio-track switch removes it first.
5. If the actual player output layout does not match the requested 5.1/7.1
   layout, the filter is removed and the rejection is reported. If the route
   disappears, processing is bypassed. Playback continues.
6. The upmixer does not synthesize LFE: the sound system owns bass management.
7. Headset modes are explicitly opt-in, never auto-selected from device names
   or a driver's broad claimed format support. They require matching Console
   and Multimedia default endpoints, a known stereo (or same-layout 5.1/7.1)
   WAVEFORMATEXTENSIBLE mix mask, and `IAudioClient::IsFormatSupported` returning
   `S_OK` for an exact float PCM format at the current mix rate. For headset
   5.1, try side then back on stereo mixes, but preserve the actual mask on
   a six-channel mix. Unknown layouts and unsupported formats bypass.
8. Verify `audio-out-params/channels` matches the selected 5.1(side), 5.1 or
   7.1 stream after applying the filter; otherwise remove it and restore
   original audio. Player status confirms **PCM stream delivery only**, not
   headset spatialization. Subjective positioning remains a listening test.
9. Automatic selection follows the same rules, including the stereo-source,
   default-route and passthrough guards. Reject each failed candidate at most
   once per output/track before trying the next, and keep the original sound
   if no supported layout succeeds. A driver advertising 7.1 format support is
   not evidence of better perceived quality or actual HRTF processing.

## Verification

- Run `./gradlew.bat :composeApp:buildWindowsPlayerBridge` on Windows.
- Run `./gradlew.bat :composeApp:testWindowsSurroundUpmixPolicy` to compile
  and run deterministic speaker-mask, automatic fallback order, rejection
  exhaustion and source-format classification tests.
- Run `py -3 tests/surround-upmix-libmpv-probe.windows.py` to check the
  bundled DLL can add/remove the real FFmpeg filter using synthetic audio and
  an audio-null sink; the test renders six- and eight-channel WAVEFORMATEXTENSIBLE
  float PCM files through mpv's PCM sink and checks non-silent front, centre and
  surround samples with silent LFE. This does not play through real speakers.
- Perform a real-hardware speaker-identification test before treating this as
  production-ready. Compare original stereo with upmix, then test actual
  native 5.1 and 7.1 sources, device unplugging, HDMI/USB audio, mute,
  playback speed, seek, and source/track changes.

### Not yet supported or proven

- Built-in arbitrary-headset HRTF/binaural processing, generic stereo-to-7.1 speaker
  upmix, Linux/macOS/Android/iOS/Tizen upmix, head-tracked headphone
  spatialization and compressed Dolby/DTS 5.1 encoding.
- Detecting whether a connected AVR/soundbar already applies its own upmixer.
- Real-speaker listening quality, A/V latency and output routing across
  manufacturers/firmware. The initial setting remains opt-in pending those
  acceptance tests.

Implementation uses the existing open-source libmpv/FFmpeg dependency and
requires no proprietary surround decoder, device driver, server-side
processing or external media upload.

## Headset compatibility and verification boundaries

- Microsoft documents a driver-provided LFX APO that can accept a six-channel
  stream and render it to stereo headphones. That behavior is **not automatically**
  an HRTF implementation. Windows can accept six- or eight-channel streams
  even when the endpoint mix is stereo; that is necessary but insufficient evidence
  of virtual surround:
  https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-isformatsupported
- On one compatible Windows headset, a *muted synthetic-audio* libmpv
  test confirmed stereo input -> 7.1 WASAPI output -> stereo restoration.
  A separate 5.1 PCM probe is required to establish format acceptance on each
  target device and does not verify vendor/OS DSP.
  Subjective position, DSP engagement, mono compatibility, A/V latency and
  device reconnect behavior were **not** measured in that test. This feature
  remains opt-in and experimental pending listening and switching validation.
