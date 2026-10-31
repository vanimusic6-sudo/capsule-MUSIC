# HONOR Capsule — доказанное отсутствие системного MediaStyle (31.10.2026)

## Логи с физического устройства

Исходные данные пользователя: `capsule-logs-2026-10-31_19-54-14.txt` — **точная** проверенная сборка `1.1.2-dev 8f4fa1f`.

- `19:54:05.559` `platform-session mediaPresent=true playing=true state=3 foreground=true hasTitle=true hasArtist=true hasArtwork=true notifyEnabled=true channelImportance=2 sessionAdded=true nativeNotificationReady=false`.
- `19:54:06.857` `audit phase=immediate ... playing=true playWhenReady=true state=3 sessionAdded=true notificationAllowed=true channelImportance=2 notificationCount=0 ownMediaIdPresent=false mediaSessionTokenCount=0`.
- `19:54:07.574` `audit phase=settled ... playing=true ... notificationCount=0 mediaTokenOnOwnId=false`.

Доказательство: нет даже **платформенного уведомления Android**. Следовательно, сначала нужно исправить цепочку `Media3 Session → notification controller → MediaStyle publication`, а не копировать анимации HONOR или обвинять оболочку/права доступа. Право POST_NOTIFICATIONS есть, канал не отключён.

## Исходники AndroidX Media3 1.11.x

`MediaNotificationManager.shouldShowNotification()` проверяет наличие подключённого внутреннего `MediaController` и **непустого `controller.currentTimeline`**; если одно из условий нарушено, `updateNotification()` вызывает `removeNotification()` и даже не вызывает `MediaNotification.Provider.createNotification()`.

`MediaNotificationManager.addSession` создаёт отдельный controller c connection hint `KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG=true`. Его подключение можно проверить `MediaSession.isMediaNotificationController(controllerInfo)` и `getMediaNotificationControllerInfo()`. `onUpdateNotification(...)` может вызываться даже тогда, когда система не дошла до вызова notification provider.

Именно этот участок был слепой зоной предыдущего PR #66.

## Изменения

1. `MediaLibrarySessionCallback.onConnect` использует **точно тот же** конструктор `MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)`, что и рабочий ArchiveTune. Для внешних недоверенных клиентов остаётся только стандартный read-only набор команд; не расширены права на изменение библиотеки. Для доверенного/внутреннего notification controller добавлены стандартные команды, сохраняется `COMMAND_GET_TIMELINE`.
2. Добавлена однократная диагностика подключений `controller-connect notification=... trusted=... timelineGranted=...` и `notification-controller-connected`.
3. `CapsuleMediaNotificationProvider.createNotification` логирует факт реального вызова. Это исключает путаницу между «Media3 собирался публиковать» и «Media3 создал уведомление».
4. Если через **450 мс активного воспроизведения** Media3 по-прежнему не выдал ни одного уведомления, `NativeMediaCardFallback` публикует штатный **Android `Notification.MediaStyle`** с `mediaSession.getPlatformToken()`, настоящими prev/play-pause/next действиями, ID и каналом существующей Media3 notification. Никакой фальшивой отдельной медиасессии и никакого самодельного UI.
5. Recovery работает только после фактически отсутствующего уведомления и только по событиям Media3; нет периодического polling/лишнего расхода батареи. Когда Media3 самостоятельно начинает работать, его штатная notification получает приоритет. Если активных треков нет, recovery чистится.

## Приёмка на HONOR

- При воспроизведении и уходе на Home логи должны показывать `notificationCount >= 1`, `mediaTokenOnOwnId=true`, а не два подряд нуля.
- `native-platform-media-rescue published=true tokenAttached=true` разрешено, если Media3 ещё не создал собственное уведомление. `notification-controller-connected=true` и `media3-provider-create` ожидаются на нормальном пути.
- Проверить сначала системный медиацентр Android/шторку, затем анимированную верхнюю капсулу HONOR. Даже настоящий штатный MediaStyle не даёт стороннему приложению права принудительно запускать OEM-анимацию, поэтому финальный критерий — видео/устройство.
- Тесты: `NativeMediaCardFallbackTest` проверяет `Notification.EXTRA_MEDIA_SESSION`, transport category и три media actions. Обычный playback/download набор тестов обязан оставаться зелёным.

Новые логи не раскрывают ID или название трека и не собирают данные других приложений.
