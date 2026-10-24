# Capsule Music — device log triage 2026-10-25 02:03

Observed APK: `1.1.2 (10)`, commit `1f95430`. This file is older than fixes committed in the current follow-up.

## Measured AUDIO / CDN facts
- Extracted 1,739 log events. Session summary: **19 CDN opens, 3 refused, 27 wire requests, 8 redirects, 21 sockets, 5 abandoned bodies, HTTP/2 0%**.
- **All three HTTP 403 responses arrived at `stage=redirect-followed`** and before useful audio bytes, not on an original direct CDN host. They had `authChallenge=false`. Two belonged to WebM/Opus itag 251, one to MP4/AAC itag 140. This contradicts a claim that only MP4 fails, while leaving open a format-dependent probability difference.
- The affected streams recovered with a subsequent request via the original signed link. This is a transport-route observation, *not* a confirmed YouTube bot-check.
- `01:52:14–01:52:18`: the cold first WEB_REMIX resolve took **4.266 seconds**, with cipher processing alone **3.281 seconds** while a remote player config store published ~1,001 entries; first sound after **6.34 seconds buffering**. Subsequent warm WEB_REMIX resolves in the same process were ~240–616ms. This is a *cold bootstrap/CPU/config* opportunity, not evidence that /player round trips take 4 seconds.
- 67 AUDIO extraction-profile attempts: 55 DOWNLOAD, 12 PLAYBACK, zero PREFETCH. As earlier sessions suggested, downloads account for most profile-extraction traffic.
- Some CDN requests received HTTP 302 to cross-group endpoints, causing extra cold HTTP/1.1 connections. Cross-group destinations supplied **all three** observed 403s. Existing one-reissue-before-following logic and fallback succeeded in this recording; arbitrary removal of its protective behaviour would be a risky regression.

## UI evidence not present in these logs
- No VIDEO timing, route attempts, first decoded VIDEO frame, or process-crash records were captured. The reported 18-second VIDEO startup therefore cannot be attributed to one backend from this file. The current VIDEO route pipeline has sequential deadlines of 4s+4s+10s+10s+4s; expensive fallback or first process initialization is plausible.
- No Compose trace, thumbnail URL/decoder-resolution trace or recomposition markers accompany the user's UI symptoms. Those need source fixes plus a targeted device run.

## Corrections introduced after this recording
- Item, home and album hero artwork requests now choose appropriately larger source pixels only for recognized Googleusercontent image-size suffixes; signed and third-party source URLs remain unmodified. This prevents first-install tiny URL responses from being reused indefinitely as card art.
- Grid music tiles 128→144dp and search-row artwork 48→60dp; the corresponding list row height increases to 78dp. Shelf heights derive from the card constant and update together.
- Home's delayed automatic scroll-to-top no longer animates across the entire feed and will not hijack a currently scrolling gesture. Artist's header waits for the resolved remote name rather than showing a provisional local name that reflows afterward.
- The player More menu remains open after starting a download and omits the active-download destructive X variant; other download entry points retain their existing explicit deletion controls.
- VIDEO repeats reuse a recently matched source-to-official-clip mapping and cached valid URL; the isolated extractor remembers the last successful backend as a priority when it is healthy, without modifying fallback deadlines or breaking the existing route-health contract.

## Release gate and remaining work
- Need actual VIDEO `VIDEO timing match/extract/routes/first-frame` lines for slow first-start sessions, plus device first-render image URLs/size traces.
- Need matched cold/warm audio timing with current code and **without** a simultaneous 100-track download queue before claiming fast initial response.
- Do not label all CDN 403s as bot checks. Treat server-auth challenges separately from redirect-host refusals; preserve successful same-link recovery for the latter.
- All fixes must pass app + innertube tests, lint and signed Release build, followed by upgrade-in-place validation on the HONOR device. No thermal/energy performance claims without traces.
