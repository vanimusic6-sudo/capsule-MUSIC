from pathlib import Path

ROOT = Path(".")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def copy_template(source: str, destination: str) -> None:
    write(destination, read(source))


def replace_balanced_if_block(text: str, marker: str, replacement: str, label: str) -> str:
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"{label}: marker not found")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"{label}: opening brace not found")
    depth = 0
    end = None
    for index in range(brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                end = index + 1
                break
    if end is None:
        raise SystemExit(f"{label}: block did not close")
    return text[:start] + replacement + text[end:]


copy_template(
    ".github/tmp/AudioClientOrder.kt",
    "app/src/main/kotlin/com/nikhil/yt/constants/AudioClientOrder.kt",
)
copy_template(
    ".github/tmp/AudioClientOrderTest.kt",
    "app/src/test/kotlin/com/nikhil/yt/constants/AudioClientOrderTest.kt",
)
copy_template(
    ".github/tmp/AudioClientPriorityDialog.kt",
    "app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/AudioClientPriorityDialog.kt",
)
copy_template(
    ".github/tmp/CapsuleAudioFallbackPolicy.kt",
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioFallbackPolicy.kt",
)
copy_template(
    ".github/tmp/CapsuleAudioFallbackPolicyTest.kt",
    "app/src/test/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioFallbackPolicyTest.kt",
)

path = "app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/PlayerSettings.kt"
text = read(path)
text = replace_once(
    text,
    "import com.nikhil.yt.constants.AudioCrossfadeDurationKey\n",
    "import com.nikhil.yt.constants.AudioClientOrder\nimport com.nikhil.yt.constants.AudioClientOrderKey\nimport com.nikhil.yt.constants.AudioCrossfadeDurationKey\n",
    "PlayerSettings imports",
)
text = replace_once(
    text,
    "    val (audioStreamPolicy, onAudioStreamPolicyChange) =\n",
    "    val (audioStreamPolicy, _) =\n",
    "PlayerSettings legacy setter",
)
anchor = """    val (audioStreamPolicy, _) =
        rememberEnumPreference(
            AudioStreamPolicyKey,
            defaultValue = AudioStreamPolicy.VISIONOS,
        )
"""
text = replace_once(
    text,
    anchor,
    anchor
    + """
    val (rawAudioClientOrder, onAudioClientOrderChange) =
        rememberPreference(
            AudioClientOrderKey,
            defaultValue = "",
        )
    val audioClientOrder =
        AudioClientOrder.resolve(
            raw = rawAudioClientOrder,
            legacyPolicy = audioStreamPolicy,
        )
""",
    "PlayerSettings order preference",
)
text = replace_once(
    text,
    "    var showAudioStreamPolicyDialog by remember { mutableStateOf(false) }\n",
    "    var showAudioClientPriorityDialog by remember { mutableStateOf(false) }\n",
    "PlayerSettings dialog state",
)
text = replace_balanced_if_block(
    text,
    "    if (showAudioStreamPolicyDialog) {",
    """    if (showAudioClientPriorityDialog) {
        AudioClientPriorityDialog(
            currentOrder = audioClientOrder,
            resetOrder = AudioClientOrder.legacyOrder(audioStreamPolicy),
            onDismiss = { showAudioClientPriorityDialog = false },
            onSave = { newOrder ->
                onAudioClientOrderChange(AudioClientOrder.encode(newOrder))
                showAudioClientPriorityDialog = false
            },
        )
    }""",
    "PlayerSettings old policy dialog",
)
preference_start = text.find(
    """        PreferenceEntry(
            title = { Text(stringResource(R.string.audio_stream_policy)) },
"""
)
if preference_start < 0:
    raise SystemExit("PlayerSettings old policy preference not found")
next_switch = text.find("        SwitchPreference(", preference_start)
if next_switch < 0:
    raise SystemExit("PlayerSettings switch after policy preference not found")
new_preference = """        PreferenceEntry(
            title = { Text(stringResource(R.string.audio_client_priority_title)) },
            description =
                stringResource(
                    R.string.audio_client_priority_summary,
                    audioClientOrder.firstOrNull() ?: AudioClientOrder.VISIONOS,
                    audioClientOrder.size,
                ),
            icon = {
                Icon(
                    painterResource(R.drawable.integration),
                    null,
                )
            },
            onClick = {
                showAudioClientPriorityDialog = true
            },
        )

        Text(
            text = stringResource(R.string.audio_client_priority_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier =
                Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 8.dp,
                ),
        )

"""
text = text[:preference_start] + new_preference + text[next_switch:]
write(path, text)

path = "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt"
text = read(path)
text = replace_once(
    text,
    """        streamPolicy: AudioStreamPolicy,
        priority: AudioResolvePriority = AudioResolvePriority.PLAYBACK,
""",
    """        streamPolicy: AudioStreamPolicy,
        clientOrder: List<String> = emptyList(),
        priority: AudioResolvePriority = AudioResolvePriority.PLAYBACK,
""",
    "CapsuleInnerTubeXPlayer signature",
)
text = replace_once(
    text,
    "            val primaryProfileId = streamPolicy.playbackClientOverrideId\n",
    "            val primaryProfileId = clientOrder.firstOrNull() ?: streamPolicy.playbackClientOverrideId\n",
    "CapsuleInnerTubeXPlayer primary",
)
text = replace_once(
    text,
    """                                primaryProfileId = primaryProfileId,
                                priority = priority,
""",
    """                                primaryProfileId = primaryProfileId,
                                preferredProfiles = clientOrder,
                                priority = priority,
""",
    "CapsuleInnerTubeXPlayer fallback call",
)
text = replace_once(
    text,
    """        audioQuality: InnerTubeXAudioQuality,
        primaryProfileId: String,
        priority: AudioResolvePriority,
""",
    """        audioQuality: InnerTubeXAudioQuality,
        primaryProfileId: String,
        preferredProfiles: List<String>,
        priority: AudioResolvePriority,
""",
    "CapsuleInnerTubeXPlayer safe fallback signature",
)
text = replace_once(
    text,
    """                isUploaded = baseHints.isUploaded == true,
                excludedProfiles = quarantinedAtStart + perSongExcluded,
""",
    """                isUploaded = baseHints.isUploaded == true,
                excludedProfiles = quarantinedAtStart + perSongExcluded,
                preferredProfiles = preferredProfiles,
""",
    "CapsuleInnerTubeXPlayer plan order",
)
write(path, text)

path = "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioEngine.kt"
text = read(path)
text = replace_once(
    text,
    """        connectivityManager: ConnectivityManager,
        streamPolicy: AudioStreamPolicy = AudioStreamPolicy.VISIONOS,
        priority: AudioResolvePriority = AudioResolvePriority.PLAYBACK,
""",
    """        connectivityManager: ConnectivityManager,
        streamPolicy: AudioStreamPolicy = AudioStreamPolicy.VISIONOS,
        clientOrder: List<String> = emptyList(),
        priority: AudioResolvePriority = AudioResolvePriority.PLAYBACK,
""",
    "CapsuleAudioEngine signature",
)
text = replace_once(
    text,
    """                connectivityManager = connectivityManager,
                streamPolicy = streamPolicy.normalizedForPlayback(),
                priority = priority,
""",
    """                connectivityManager = connectivityManager,
                streamPolicy = streamPolicy.normalizedForPlayback(),
                clientOrder = clientOrder,
                priority = priority,
""",
    "CapsuleAudioEngine resolver call",
)
write(path, text)

matches = []
for candidate in (ROOT / "app/src/main/kotlin").rglob("*.kt"):
    body = candidate.read_text(encoding="utf-8")
    if "data class AudioPlaybackContext(" in body:
        matches.append(candidate)
if len(matches) != 1:
    raise SystemExit(f"AudioPlaybackContext definition count={len(matches)}")
context_path = matches[0]
lines = context_path.read_text(encoding="utf-8").splitlines(keepends=True)
start = next(i for i, line in enumerate(lines) if "data class AudioPlaybackContext(" in line)
close = None
for i in range(start + 1, min(start + 30, len(lines))):
    if lines[i].lstrip().startswith(")"):
        close = i
        break
if close is None:
    raise SystemExit("AudioPlaybackContext closing parenthesis not found")
if any("clientOrder:" in line for line in lines[start:close]):
    raise SystemExit("AudioPlaybackContext already has clientOrder")
previous = lines[close - 1]
newline = "\n" if previous.endswith("\n") else ""
previous_body = previous[:-1] if newline else previous
if not previous_body.rstrip().endswith(","):
    lines[close - 1] = previous_body + "," + newline
lines.insert(close, "    val clientOrder: List<String> = emptyList(),\n")
context_path.write_text("".join(lines), encoding="utf-8")
print(f"AudioPlaybackContext patched in {context_path}")

path = "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"
text = read(path)
text = replace_once(
    text,
    "import com.nikhil.yt.constants.AudioCrossfadeDurationKey\n",
    "import com.nikhil.yt.constants.AudioClientOrder\nimport com.nikhil.yt.constants.AudioClientOrderKey\nimport com.nikhil.yt.constants.AudioCrossfadeDurationKey\n",
    "MusicService imports",
)
text = replace_once(
    text,
    """    @Volatile
    private var audioStreamPolicy = AudioStreamPolicy.VISIONOS
""",
    """    @Volatile
    private var audioStreamPolicy = AudioStreamPolicy.VISIONOS

    @Volatile
    private var audioClientOrder: List<String> =
        AudioClientOrder.legacyOrder(AudioStreamPolicy.VISIONOS)
""",
    "MusicService order field",
)
text = replace_once(
    text,
    """    private fun playbackContext() = AudioPlaybackContext(
        audioQuality.normalizedPlaybackQuality(),
        audioStreamPolicy,
        connectivityManager.isActiveNetworkMetered,
    )
""",
    """    private fun playbackContext() = AudioPlaybackContext(
        audioQuality.normalizedPlaybackQuality(),
        audioStreamPolicy,
        connectivityManager.isActiveNetworkMetered,
        audioClientOrder,
    )
""",
    "MusicService playback context",
)
init_anchor = """        audioStreamPolicy = dataStore[AudioStreamPolicyKey]
            .toEnum(AudioStreamPolicy.VISIONOS)
            .normalizedForPlayback()
"""
text = replace_once(
    text,
    init_anchor,
    init_anchor
    + """        audioClientOrder =
            AudioClientOrder.resolve(
                raw = dataStore[AudioClientOrderKey],
                legacyPolicy = audioStreamPolicy,
            )
""",
    "MusicService initial order",
)
observer_old = """        dataStore.data
            .map { prefs ->
                Pair(
                    prefs[AudioStreamPolicyKey].toEnum(AudioStreamPolicy.VISIONOS).normalizedForPlayback(),
                    prefs[AudioQualityKey]
                        .toEnum(AudioQuality.AUTO)
                        .normalizedPlaybackQuality(),
                )
            }
            .distinctUntilChanged()
            .collect(scope) { (policy, quality) ->
                if (policy != audioStreamPolicy || quality != audioQuality) {
                    audioStreamPolicy = policy
                    audioQuality = quality
                    reloadAudioForClientChange(policy)
                }
            }
"""
observer_new = """        dataStore.data
            .map { prefs ->
                val policy =
                    prefs[AudioStreamPolicyKey]
                        .toEnum(AudioStreamPolicy.VISIONOS)
                        .normalizedForPlayback()
                val quality =
                    prefs[AudioQualityKey]
                        .toEnum(AudioQuality.AUTO)
                        .normalizedPlaybackQuality()
                val clientOrder =
                    AudioClientOrder.resolve(
                        raw = prefs[AudioClientOrderKey],
                        legacyPolicy = policy,
                    )
                Triple(policy, quality, clientOrder)
            }
            .distinctUntilChanged()
            .collect(scope) { (policy, quality, clientOrder) ->
                if (
                    policy != audioStreamPolicy ||
                    quality != audioQuality ||
                    clientOrder != audioClientOrder
                ) {
                    audioStreamPolicy = policy
                    audioQuality = quality
                    audioClientOrder = clientOrder
                    reloadAudioResolveConfig(
                        clientOrder.firstOrNull() ?: policy.playbackClientOverrideId,
                    )
                }
            }
"""
text = replace_once(text, observer_old, observer_new, "MusicService preference observer")
text = replace_once(
    text,
    "    private fun reloadAudioForClientChange(policy: AudioStreamPolicy) {\n",
    "    private fun reloadAudioResolveConfig(primaryProfileId: String) {\n",
    "MusicService reload helper",
)
text = replace_once(
    text,
    """            "Audio client selected profile=%s; future resolves updated, current playback preserved",
            policy.playbackClientOverrideId,
""",
    """            "Audio client priority updated first=%s; future resolves updated, current playback preserved",
            primaryProfileId,
""",
    "MusicService reload log",
)
text = replace_once(
    text,
    """                    streamPolicy = selection.policy,
                    priority = priority,
""",
    """                    streamPolicy = selection.policy,
                    clientOrder = selection.clientOrder,
                    priority = priority,
""",
    "MusicService resolve client order",
)
write(path, text)

english = """
    <string name="audio_client_priority_title">Audio client priority</string>
    <string name="audio_client_priority_summary">First: %1$s • %2$d clients</string>
    <string name="audio_client_priority_description">Drag clients by the handle. Higher clients are queried earlier when Capsule needs a new audio stream.</string>
    <string name="audio_client_priority_note">The order controls real playback resolves, not only the settings screen.</string>
    <string name="audio_client_priority_safety_note">Safety rules still apply: quarantined and incompatible clients are skipped, background prefetch uses only position #1, and a bot-check may cross to only one different client family.</string>
    <string name="audio_client_priority_reset">Reset</string>
    <string name="audio_client_priority_cancel">Cancel</string>
    <string name="audio_client_priority_done">Done</string>
    <string name="audio_client_visionos">visionOS</string>
    <string name="audio_client_visionos_description">Stable visionOS playback profile.</string>
    <string name="audio_client_visionos_compat">visionOS compatibility</string>
    <string name="audio_client_visionos_compat_description">Alternative maintained profile from the Vision family.</string>
    <string name="audio_client_web_remix">YouTube Music Web</string>
    <string name="audio_client_web_remix_description">Music web profile with the modern PoToken path.</string>
    <string name="audio_client_web_embedded">Embedded Web</string>
    <string name="audio_client_web_embedded_description">Independent embedded-web fallback profile.</string>
    <string name="audio_client_web_creator">Web Creator</string>
    <string name="audio_client_web_creator_description">Authenticated web profile; skipped automatically while signed out.</string>
    <string name="audio_client_tv">TV HTML5</string>
    <string name="audio_client_tv_description">Maintained TVHTML5_SIMPLY profile. The broken legacy TVHTML5 profile is not exposed.</string>
"""
russian = """
    <string name="audio_client_priority_title">Приоритет аудиоклиентов</string>
    <string name="audio_client_priority_summary">Первый: %1$s • клиентов: %2$d</string>
    <string name="audio_client_priority_description">Перетаскивай клиентов за ручку. Чем выше клиент, тем раньше Capsule опрашивает его при получении нового аудиопотока.</string>
    <string name="audio_client_priority_note">Порядок реально управляет резолвом воспроизведения, а не только отображением в настройках.</string>
    <string name="audio_client_priority_safety_note">Защита остаётся активной: карантинные и несовместимые клиенты пропускаются, фоновый PREFETCH использует только позицию №1, а после bot-check разрешён только один переход в другое семейство клиентов.</string>
    <string name="audio_client_priority_reset">Сбросить</string>
    <string name="audio_client_priority_cancel">Отмена</string>
    <string name="audio_client_priority_done">Готово</string>
    <string name="audio_client_visionos">visionOS</string>
    <string name="audio_client_visionos_description">Стабильный профиль воспроизведения visionOS.</string>
    <string name="audio_client_visionos_compat">visionOS совместимый</string>
    <string name="audio_client_visionos_compat_description">Альтернативный поддерживаемый профиль семейства Vision.</string>
    <string name="audio_client_web_remix">YouTube Music Web</string>
    <string name="audio_client_web_remix_description">Музыкальный web-профиль с современным PoToken-путём.</string>
    <string name="audio_client_web_embedded">Embedded Web</string>
    <string name="audio_client_web_embedded_description">Независимый встроенный web-профиль для fallback.</string>
    <string name="audio_client_web_creator">Web Creator</string>
    <string name="audio_client_web_creator_description">Авторизованный web-профиль; без входа в аккаунт автоматически пропускается.</string>
    <string name="audio_client_tv">TV HTML5</string>
    <string name="audio_client_tv_description">Поддерживаемый TVHTML5_SIMPLY. Сломанный старый TVHTML5 в список не добавляется.</string>
"""


def insert_resource_block(path: str, block: str) -> None:
    text = read(path)
    if 'name="audio_client_priority_title"' in text:
        raise SystemExit(f"{path}: audio client strings already exist")
    index = text.rfind("</resources>")
    if index < 0:
        raise SystemExit(f"{path}: closing resources tag not found")
    write(path, text[:index] + block + text[index:])


insert_resource_block("app/src/main/res/values/strings.xml", english)
insert_resource_block("app/src/main/res/values-ru/strings.xml", russian)

print("Audio client priority patch applied")
