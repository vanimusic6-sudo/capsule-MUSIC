from pathlib import Path
import re

path = Path('app/src/main/kotlin/com/nikhil/yt/MusicService.kt')
text = path.read_text()
original = text

# 1) Imports: keep the probe next to the rest of the playback/audio imports.
needle = 'import com.nikhil.yt.playback.audio.AudioResolvePriority\n'
replacement = needle + 'import com.nikhil.yt.playback.audio.PlaybackLoadDiagnostics\nimport com.nikhil.yt.playback.audio.PlaybackTransferDiagnostics\n'
if 'import com.nikhil.yt.playback.audio.PlaybackLoadDiagnostics' not in text:
    if needle not in text:
        raise SystemExit('Step38: AudioResolvePriority import anchor not found')
    text = text.replace(needle, replacement, 1)

# 2) Attach the transfer probe only to the ordinary playback HTTP upstream.
# Do not touch the separate download/other factories that carry their own timeout policy.
pattern = re.compile(
    r'(private\s+val\s+appCacheDataSourceFactory[\s\S]*?\.setUpstreamDataSourceFactory\(\s*DefaultDataSource\.Factory\(\s*this,\s*)'
    r'(DefaultHttpDataSource\.Factory\(\))'
    r'(\s*\)\s*\))',
    re.MULTILINE,
)
if 'DefaultHttpDataSource.Factory().setTransferListener(PlaybackTransferDiagnostics)' not in text:
    text, count = pattern.subn(
        r'\1DefaultHttpDataSource.Factory().setTransferListener(PlaybackTransferDiagnostics)\3',
        text,
        count=1,
    )
    if count != 1:
        raise SystemExit(f'Step38: playback HTTP factory anchor count={count}, expected 1')

# 3) Media3 load lifecycle gives us loader timing/bytes around the same request.
needle = 'addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))'
replacement = needle + '\n                    addAnalyticsListener(PlaybackLoadDiagnostics)'
if 'addAnalyticsListener(PlaybackLoadDiagnostics)' not in text:
    if needle not in text:
        raise SystemExit('Step38: PlaybackStatsListener anchor not found')
    text = text.replace(needle, replacement, 1)

if text == original:
    raise SystemExit('Step38: no changes applied')

path.write_text(text)
print('Step38 MusicService wiring applied')
