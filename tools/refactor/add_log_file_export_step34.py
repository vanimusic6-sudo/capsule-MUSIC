from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEBUG_SETTINGS = ROOT / "app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/DebugSettings.kt"
STRINGS = ROOT / "app/src/main/res/values/capsule_log_export_strings.xml"
STRINGS_RU = ROOT / "app/src/main/res/values-ru/capsule_log_export_strings.xml"

text = DEBUG_SETTINGS.read_text(encoding="utf-8")

# Storage Access Framework gives the user an explicit destination and requires
# no storage permission. No file is created or updated until the user presses
# the save button.
import_anchor = "import android.util.Log\n"
imports = (
    "import android.util.Log\n"
    "import android.widget.Toast\n"
    "import androidx.activity.compose.rememberLauncherForActivityResult\n"
    "import androidx.activity.result.contract.ActivityResultContracts\n"
)
if "rememberLauncherForActivityResult" not in text:
    if import_anchor not in text:
        raise SystemExit("DebugSettings import anchor not found")
    text = text.replace(import_anchor, imports, 1)
else:
    raise SystemExit("Log export launcher already exists; refusing ambiguous re-run")

state_anchor = '''    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
'''
state_replacement = '''    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pendingLogExportText by remember { mutableStateOf("") }
    val saveLogsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            if (uri != null) {
                val saved =
                    runCatching {
                        context.contentResolver.openOutputStream(uri)
                            ?.bufferedWriter(Charsets.UTF_8)
                            ?.use { writer -> writer.write(pendingLogExportText) }
                            ?: error("Unable to open selected log export destination")
                    }.isSuccess
                Toast.makeText(
                    context,
                    context.getString(
                        if (saved) R.string.logs_saved_to_file else R.string.logs_save_failed
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
'''
if state_anchor not in text:
    raise SystemExit("LogViewerPanel state anchor not found")
text = text.replace(state_anchor, state_replacement, 1)

row_block = '''            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { GlobalLog.clear() },
                    enabled = filtered.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.clear_all),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.clear))
                }

                FilledTonalButton(
                    onClick = {
                        if (filtered.isEmpty()) return@FilledTonalButton
                        val sb = StringBuilder()
                        filtered.forEach { sb.appendLine(GlobalLog.format(it)) }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, sb.toString())
                        }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_logs)))
                    },
                    enabled = filtered.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
            }
'''
row_replacement = row_block + '''
            FilledTonalButton(
                onClick = {
                    if (filtered.isEmpty()) return@FilledTonalButton
                    val exportedAt = System.currentTimeMillis()
                    pendingLogExportText =
                        buildString {
                            appendLine("=== Capsule Debug Logs ===")
                            appendLine(
                                "Exported: ${DateFormat.format(\"yyyy-MM-dd HH:mm:ss\", exportedAt)}"
                            )
                            appendLine(
                                "Filter: ${when (filterMode) { 0 -> \"Discord\"; 1 -> \"YouTube core\"; else -> \"All\" }}"
                            )
                            appendLine("Count: ${filtered.size}")
                            appendLine("==========================")
                            appendLine()
                            filtered.forEach { entry -> appendLine(GlobalLog.format(entry)) }
                        }
                    val fileStamp =
                        DateFormat.format("yyyy-MM-dd_HH-mm-ss", exportedAt).toString()
                    saveLogsLauncher.launch("capsule-logs-$fileStamp.txt")
                },
                enabled = filtered.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save_logs_to_file))
            }
'''
if row_block not in text:
    raise SystemExit("Log action button block not found")
text = text.replace(row_block, row_replacement, 1)

DEBUG_SETTINGS.write_text(text, encoding="utf-8")

STRINGS.write_text(
    '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="save_logs_to_file">Save logs to file</string>
    <string name="logs_saved_to_file">Logs saved to file</string>
    <string name="logs_save_failed">Failed to save logs</string>
</resources>
''',
    encoding="utf-8",
)
STRINGS_RU.parent.mkdir(parents=True, exist_ok=True)
STRINGS_RU.write_text(
    '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="save_logs_to_file">Сохранить логи в файл</string>
    <string name="logs_saved_to_file">Логи сохранены в файл</string>
    <string name="logs_save_failed">Не удалось сохранить логи</string>
</resources>
''',
    encoding="utf-8",
)

updated = DEBUG_SETTINGS.read_text(encoding="utf-8")
assert 'ActivityResultContracts.CreateDocument("text/plain")' in updated
assert 'saveLogsLauncher.launch("capsule-logs-$fileStamp.txt")' in updated
assert 'context.contentResolver.openOutputStream(uri)' in updated
assert 'pendingLogExportText' in updated
assert 'R.string.save_logs_to_file' in updated
# Guard the user's logging requirement: this patch must not introduce any
# continuous file sink or background write loop.
assert 'FileOutputStream' not in updated
assert 'GlobalLog.logs.collect' not in updated
print("step34 log file export patch applied successfully")
