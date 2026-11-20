# Changelog

All notable changes to WallMode are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). WallMode currently remains unreleased; the inherited KioskZen history is noted below.

## [Unreleased]

### Added

- Voluntary **Support WallMode** section in Android System settings and the browser panel, using the existing OpenNova Buy Me a Coffee and GitHub Sponsors destinations, with external-link/copy controls and no paid feature gates.
- GitHub funding configuration and a first signed Android release checklist covering signing, source/media provenance, device acceptance and public blueprint links.
- Aurora Rail settings interface with focused Dashboard, Display, Browser, Device, and System sections.
- Matching Aurora Rail browser panel and sign-in screen, with native-style icons, light/dark/system colors, responsive navigation, accessible switches, and a sticky per-tab save bar.
- Responsive settings navigation with compact icon tabs and one content column tuned for the ThinkSmart View's 492dp width, a vertical rail on wider screens, and two columns where space permits.
- Dedicated, larger navigation and action icons with accessibility descriptions.
- Configurable hidden-settings gesture in any of the four screen corners.
- Native loading, timeout, error, and recovery surfaces so dashboard failures do not leave an unexplained blank WebView.
- Two native ambient screensaver scenes:
  - **Aurora Weather**, with a glow clock, date, current weather, and condition-dependent animation.
  - **Glow Clock**, with a clock-focused layout, back glow, seconds, date, and a 60-dot perimeter second indicator.
- Open-Meteo city lookup and current conditions refreshed every 30 minutes while Aurora Weather is active.
- Bundled looping cloud video plus native sun, moon, rain, snow, fog, and lightning effects.
- Visual Ambient Studio background picker with preview tiles for WallMode effects, bundled cloud media, direct image or video URLs, and an Immich album.
- Fullscreen **Preview now** for the unsaved scene, background, weather, and configured ambient brightness.
- Shared Immich-album photo clock with shuffled playback, configurable 10–3600 second interval, display-sized image loading, bounded album lists, and retry backoff.
- Complete authenticated LAN settings forms for all 57 saved options across Dashboard, Display, Browser, Device, and System, plus MQTT-password replacement/removal and the existing Security password controls.
- Readable, editable Immich share links and full photo-clock/background configuration from a phone or computer, without typing on the tablet.
- Per-tab browser saves with validation and stale-form detection; errors retain the typed values, and saves are blocked while native settings or an event takeover are active. Port changes and server shutdown return reconnect or re-enable instructions.
- Dynamic Aurora accent colors based on weather and day/night state, with deterministic photo-color sampling and minimum brightness.
- Bounded custom-image loading with media-type checks, redirect validation, an 8 MiB limit, and display-sized sampling.
- Fixed-percentage or Android-managed brightness for both dim and screensaver modes.
- Touch wake that consumes the waking gesture before returning to the dashboard.
- Motion wake using lightweight local camera-frame analysis and the proximity sensor while an ambient mode is active.
- Configurable presence-wake cooldown and filtering for whole-frame exposure changes.
- Home, Wall, and Night dashboard profiles with separate paths and optional whole-hour scheduling.
- Home Assistant MQTT device discovery with availability, telemetry, and controls.
- Automatically discovered **Announcement** notify entity for Home Assistant's standard `notify.send_message` action, reusing the existing TTS command with JSON-safe message encoding and non-retained QoS 1 delivery.
- Automatically discovered **Action response** MQTT event entity for action-card and banner-button choices, exposing the prompt ID, action ID, and unique response ID while preserving the existing response topic and blueprint compatibility.
- Native Aurora notification banners over the dashboard or screensaver, with four status colors, optional notification sound and one action button, matching-ID dismissal, and a bounded lifetime without navigation or ambient-brightness changes.
- Home Assistant testing examples for banner commands and guarded event-entity automations, without a custom integration or additional blueprints.
- Home Assistant automation blueprints with device selection for announcements, temporary event views, and two-choice action cards; installation and testing instructions included, with no custom integration required.
- MQTT telemetry for battery, charging, network, screen, browser, health, ambient mode, active profile, motion, and active takeover.
- MQTT controls for dashboard reload, browser restart, URL opening, display mode, ambient scene, brightness, idle timeout, browser profile, dashboard profile, and profile scheduling.
- Priority-aware event takeovers for doorbells, cameras, alarms, and other temporary views.
- Configurable takeover duration and priority, early dismissal, and exact restoration of the previous page and display mode.
- Takeover state restoration across Android activity recreation.
- Native MQTT action cards with one to three choices, priority and lifetime handling, exact display restoration, and QoS 1 response events.
- MQTT-triggered spoken announcements and immediate stop control through Android's native text-to-speech engine.
- JPEG capture support for the visible dashboard, recovery layer, and ambient display in the LAN control panel.
- Expanded local-control health reporting with current page, display mode, MQTT state, active takeover, and last error.
- LAN control-panel actions for starting and dismissing temporary takeovers.
- Unit coverage for profile scheduling, weather and Aurora palette mapping, motion detection, invalid stored-corner fallback, ambient-media and Immich validation, MQTT commands, announcements, preview limits, session invalidation, WebView load-state handling, and complete browser-field coverage with strict parsing and section-isolated saves.

### Changed

- Moved Support WallMode to the top of System in both settings UIs, with amber emphasis and a prominent Coffee button.
- Rebranded the application, project, package namespace, launcher assets, and default update target from KioskZen to WallMode.
- Changed the application ID to `io.github.rvbcrs.wallmode` and started the WallMode version line at `0.1.0`.
- Changed the default dashboard source to `http://homeassistant.local:8123` with an empty dashboard path.
- Replaced the previous settings screen with glass cards, clearer grouping, contextual descriptions, and responsive layouts.
- Consolidated browser support around the installed Android System WebView and an optional Chromium-like user-agent profile.
- Replaced ML-based face detection with local luminance-frame motion detection and proximity events.
- Limited camera motion analysis to the period in which WallMode is dimmed or showing a screensaver.
- Changed the LAN control panel to disabled by default.
- Extended authenticated LAN previews beyond the WebView to the complete visible WallMode display.
- Replaced the clock card's linear second-progress line with 60 perimeter markers and one smoothly flashing marker for the current second.
- Added smooth dashboard-to-ambient, ambient-to-dashboard, and photo-to-photo crossfades.
- Extended configuration import and export for display, profile, MQTT, local-control, and navigation settings.
- Added MQTT state and WallMode-specific fields to diagnostics and local-control status.

### Removed

- Removed the bundled GeckoView runtime and Mozilla Maven repository.
- Removed ML Kit face detection and its model dependency.
- Removed the old KioskZen website and launcher assets.

### Fixed

- Immich 3 shared-album photo clocks now fall back to the share-compatible timeline API when metadata search returns HTTP 403, filtering videos and trashed assets while retaining the 1,000-photo limit.
- Connected opt-in startup Home Assistant discovery for the default HA address, while preserving chosen dashboard URLs and ignoring late results after navigation, settings changes or event takeovers.
- Applied the native settings admin-unlock timeout with process-local, monotonic sessions scoped to the password and preference store; password changes invalidate remembered access, and kiosk exit still requires fresh authentication.
- Prevented stale WebView callbacks from hiding the loading or recovery state for a newer navigation.
- Added explicit handling for main-frame HTTP errors, connection failures, load timeouts, and WebView renderer crashes.
- Cancelled old recovery retries after a successful visible page load.
- Prevented automatic recovery from overriding an active temporary takeover.
- Correctly resolved scheduled dashboard profiles across midnight and independently of declaration order.
- Fell back to the main dashboard path when a profile path is empty.
- Deferred scheduled profile changes until an active takeover has finished.
- Kept manually selected profiles stable while automatic scheduling is disabled.
- Restored the clock-card background and strengthened the glow behind the time.
- Applied configured ambient brightness to screensavers as well as dim mode.
- Replaced visibly repeating cloud layers with a continuously looping video background.
- Included the video-backed ambient display in remote screen previews.
- Reduced false motion wakes caused by whole-frame exposure changes.
- Ignored initial camera warm-up frames to prevent an ambient scene from immediately closing on camera startup.
- Required a far-to-near proximity transition before each proximity wake.
- Migrated removed GeckoView preferences safely back to Android WebView.

### Security

- Bound local-control sessions to the current admin credential so a password change invalidates existing sessions.
- Kept remote screen preview behind the authenticated session.
- Protected browser settings submissions with form tokens, escaped saved values, and bounded input validation. Stored MQTT passwords are never returned to the browser and are cleared when the broker identity changes.
- Prevented preview caching, rate-limited captures, blocked concurrent captures, and capped JPEG dimensions and response size.
- Excluded admin and MQTT passwords and the persistent MQTT device key from configuration exports.
- Excluded the MQTT password from diagnostics.
- Warned when MQTT credentials are used without TLS.
- Rejected retained MQTT commands and malformed, out-of-range, unsupported, or oversized command payloads.
- Restricted MQTT and takeover URLs to HTTP and HTTPS schemes.
- Validated custom ambient-media redirects, content types, credentials, and download size before displaying an image.
- Kept the Immich public-album share URL stored on the device and out of configuration exports, diagnostics, and logs; plain HTTP is limited to private LAN hosts.
- Length-, character-, range-, and count-limited native action cards and spoken announcements at the MQTT boundary.
- Validated single-line banner text, ASCII notice/button IDs, boolean sound choice, status level, and 3–120 second expiry; notices remain non-retained and are not replayed after activity/app restart.
- Removed URL credentials, query strings, and fragments from published current-page telemetry.
- Extended the secure-window flag to the settings activity.

## Version-history note

No WallMode release or WallMode tag exists yet. The existing `v1.0.0` through `v1.4.0` tags belong to the inherited KioskZen history and are intentionally not presented as WallMode releases.
