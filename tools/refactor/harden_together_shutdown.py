#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

APP = Path("app/src/main/kotlin/com/nikhil/yt/App.kt")
SERVICE = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")

APP_SCOPE = "    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)\n"
APP_SCOPE_REPLACEMENT = APP_SCOPE + '''\n    internal fun launchLifecycleCleanup(block: suspend () -> Unit) =\n        applicationScope.launch(Dispatchers.IO) { block() }\n'''

SERVICE_IMPORT = "import com.nikhil.yt.MainActivity\n"
SERVICE_IMPORT_REPLACEMENT = "import com.nikhil.yt.App\n" + SERVICE_IMPORT

SERVICE_BINDER = "    private val binder = MusicBinder()\n"
SERVICE_BINDER_REPLACEMENT = SERVICE_BINDER + "    private val togetherShutdownGate = TogetherShutdownGate()\n"

OLD_DESTROY_BLOCK = '''        try {\n            scope.launch { stopTogetherInternal() }\n        } catch (error: Exception) {\n            reportRecoverableException("MusicService", "schedule Together shutdown", error)\n        }\n'''
NEW_DESTROY_BLOCK = "        scheduleTogetherShutdown()\n"

OLD_TASK_REMOVED = "                    runCatching { scope.launch { stopTogetherInternal() } }\n"
NEW_TASK_REMOVED = "                    scheduleTogetherShutdown()\n"

HELPER_ANCHOR = "    override fun onDestroy() {\n"
HELPER = '''    private fun scheduleTogetherShutdown() {\n        if (!togetherShutdownGate.tryBegin()) return\n\n        try {\n            App.instance.launchLifecycleCleanup {\n                try {\n                    stopTogetherInternal()\n                } catch (cancelled: kotlinx.coroutines.CancellationException) {\n                    throw cancelled\n                } catch (error: Exception) {\n                    reportRecoverableException(\n                        "MusicService",\n                        "complete Together shutdown",\n                        error,\n                    )\n                }\n            }\n        } catch (error: Exception) {\n            reportRecoverableException("MusicService", "schedule Together shutdown", error)\n        }\n    }\n\n'''


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"{label}: expected exactly one match, found {count}")
    return source.replace(old, new, 1)


def validate_before(app: str, service: str) -> None:
    app_required = [APP_SCOPE]
    service_required = [
        SERVICE_IMPORT,
        SERVICE_BINDER,
        OLD_DESTROY_BLOCK,
        OLD_TASK_REMOVED,
        HELPER_ANCHOR,
        "        scopeJob.cancel()",
        "    private suspend fun stopTogetherInternal()",
    ]
    missing = [item for item in app_required if item not in app]
    missing += [item for item in service_required if item not in service]
    if missing:
        raise SystemExit("missing Together teardown preconditions: " + ", ".join(missing))
    if "launchLifecycleCleanup(" in app:
        raise SystemExit("process-lifetime lifecycle cleanup already exists")
    if "togetherShutdownGate" in service or "scheduleTogetherShutdown()" in service:
        raise SystemExit("Together shutdown scheduling already hardened")


def transform(app: str, service: str) -> tuple[str, str]:
    app = replace_once(app, APP_SCOPE, APP_SCOPE_REPLACEMENT, "application scope")
    service = replace_once(service, SERVICE_IMPORT, SERVICE_IMPORT_REPLACEMENT, "App import")
    service = replace_once(service, SERVICE_BINDER, SERVICE_BINDER_REPLACEMENT, "shutdown gate field")
    service = replace_once(service, OLD_DESTROY_BLOCK, NEW_DESTROY_BLOCK, "onDestroy shutdown")
    service = replace_once(service, OLD_TASK_REMOVED, NEW_TASK_REMOVED, "task-removed shutdown")
    service = replace_once(service, HELPER_ANCHOR, HELPER + HELPER_ANCHOR, "shutdown helper")
    return app, service


def validate_after(app: str, service: str) -> None:
    app_required = [
        "internal fun launchLifecycleCleanup(block: suspend () -> Unit)",
        "applicationScope.launch(Dispatchers.IO) { block() }",
    ]
    service_required = [
        "import com.nikhil.yt.App",
        "private val togetherShutdownGate = TogetherShutdownGate()",
        "private fun scheduleTogetherShutdown()",
        "if (!togetherShutdownGate.tryBegin()) return",
        "App.instance.launchLifecycleCleanup",
        "complete Together shutdown",
    ]
    missing = [item for item in app_required if item not in app]
    missing += [item for item in service_required if item not in service]
    if missing:
        raise SystemExit("missing hardened Together teardown markers: " + ", ".join(missing))

    forbidden = [
        "scope.launch { stopTogetherInternal() }",
        "runCatching { scope.launch { stopTogetherInternal() } }",
    ]
    present = [item for item in forbidden if item in service]
    if present:
        raise SystemExit("service-scoped Together teardown still present: " + ", ".join(present))
    if service.count("scheduleTogetherShutdown()") != 3:
        raise SystemExit("expected helper declaration plus two Together shutdown call sites")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    app = APP.read_text()
    service = SERVICE.read_text()
    validate_before(app, service)
    if args.check:
        print("Together teardown hardening preconditions satisfied")
        return

    app, service = transform(app, service)
    validate_after(app, service)
    APP.write_text(app)
    SERVICE.write_text(service)
    print("Together shutdown now survives MusicService scope cancellation and is scheduled once")


if __name__ == "__main__":
    main()
