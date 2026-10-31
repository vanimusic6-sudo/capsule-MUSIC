# Capsule Music × ArchiveTune — системный медиаплеер HONOR (31.10.2026)

## Воспроизведение дефекта

Видео `10386.mp4` — один и тот же телефон, около 15 секунд:
- 0–7 секунд: Capsule Music воспроизводит «Не все дома»; после Home/Recents на рабочем столе сверху **нет** медиаминиплеера HONOR.
- 8–15 секунд: ArchiveTune воспроизводит «Hurt»; после Home появляется **нативная верхняя капсула** HONOR, по нажатию раскрывается в системную медиа-панель с паузой, названием, обложкой и переходом к приложению.
- Это не Compose mini player, не `AnimatedVisibility`, не `Media3` seek и не SABR. OEM формирует его из реального Android media session и опубликованного уведомления MediaStyle.

Устройство пользователя по ранее предоставленным данным: HONOR / Android 17. Прошлый патч #65 (добавление провайдера и `addSession`) сам по себе не дал подтверждённого эффекта: пользователь проверил на телефоне, капсулы всё ещё не было. Поэтому проверена **вся цепочка** от Activity до SystemUI.

## Сравнение исходников, не предположений

| Участок | Рабочий ArchiveTune | Capsule до этого аудита | Исправление |
| --- | --- | --- | --- |
| `MainActivity.onStart()` | Только `bindService(... BIND_AUTO_CREATE)` | **Всегда** `startService` ещё без трека, затем `bindService` | Убрано раннее `startService`; Media3 запускает FGS лишь при воспроизведении |
| `MusicService.onStartCommand` | При активном плеере использует **сохранённое реальное** `MediaNotification` (или краткий стартовый fallback) | Вызывал собственный `ensureStartedAsForeground`, способный опубликовать generic `CATEGORY_SERVICE` с ID 888 | Убрана подмена: Media3 теперь единственный владелец MediaStyle/FGS |
| `MusicService.onUpdateNotification` | Использует стандартный цикл Media3 | Принудительно вызывал `ensureStartedAsForeground()`; `super` завёрнут в `runCatching`, скрывающий исключения FGS от внутреннего автомата Media3 | Оставлен прямой стандартный `super` + диагностика, без дополнительного generic и без подавления исключений |
| Manual notification refresh | На изменение playback state хватает событий Media3 | Несколько мест вручную вызывали `super.onUpdateNotification()`, что **запрещено контрактом Media3** | Удалены прямые вызовы, разрешён только `triggerNotificationUpdate()` после гранта разрешения |
| `MediaItemExt` | Все музыкальные `MediaMetadata`: `setIsPlayable(true)` | Три конвертера пропускали `setIsPlayable` (nullable) | Три конвертера явно помечены playable; добавлен regression test |
| `CoilBitmapLoader.decodeBitmap` | Сначала `BitmapFactory.Options.inJustDecodeBounds`, затем `inSampleSize`, затем ограничение размера | Декодировал **полное оригинальное изображение**, не ограничивая пиксели | Добавлен sample decode + максимум 512 пикселей для platform MediaSession (у текущей логики плотности фактический лимит обычно ниже); regression test |
| `MediaLibrarySession` | Явно `addSession`, native `DefaultMediaNotificationProvider` | На старой ветке не добавлялся заранее; на PR #65 добавлен провайдер | Сохраняем исправления #65, устанавливаем провайдер перед регистрацией сессии |
| `AndroidManifest.xml` | `MediaSessionService`, `MediaLibraryService`, `MediaBrowserService`, exported service и mediaPlayback FGS | После PR #63 то же самое | Без изменений, проблема не в новых intent-filter |
| `onConnect` + browse | ArchiveTune допускает более широкое библиотечное управление | Capsule ограничивает **недоверенных** контроллеров | Не открываем библиотеку посторонним приложениям ради OEM без доказательства необходимости |
| Альбом/обложка/битмап | Данные из `MediaItem.mediaMetadata`, асинхронный loader | MediaItems держат title, artist, art, но не playable; bitmap decode без размера | Исправили именно системный контракт, не меняя UI |

Официальный контракт AndroidX Media3: `MediaSessionService.onUpdateNotification` «must not be called directly by app code». Для разового ручного обновления у `MediaSessionService` есть `triggerNotificationUpdate()`; обычные player events обслуживает Media3. Повторные уведомления могут подавляться SystemUI (см. комментарий `MediaNotificationManager` в AndroidX).

## Доказательство на телефоне: новое логирование

В `MusicService.auditPublishedAndroidMediaCard` есть **два** среза после выхода на Home: сразу и через 700 мс. Данные:
- `sessionAdded` — Media3 действительно знает о сессии.
- `playing / playWhenReady / state` — Android media player в активном состоянии.
- `notificationAllowed / channelImportance` — права Android и важность канала.
- `notificationCount / ownMediaIdPresent` — уведомление `NOTIFICATION_ID = 888` реально опубликовано.
- `ownTemplate` — должна быть `MediaStyle`, **не** `BigTextStyle` или generic.
- `mediaSessionTokenCount / mediaTokenOnOwnId` — у уведомления есть `Notification.EXTRA_MEDIA_SESSION`.

Нет логирования названий треков, signed CDN URL, аккаунтов, приватных ID или bitmap. Логи только при включённом `GlobalLog`.

### A/B-проверка

1. Проверить **отдельно** плитку медиаплеера Android в шторке, затем островок HONOR. Если ни плитки, ни островка, смотрим платформенный токен и native MediaStyle, а не OEM анимацию.
2. Запустить аудиотрек в Capsule, дождаться READY, выйти домой, раскрыть островок. Вернуться, поставить паузу, возобновить, повторить. Затем проверить с активной загрузкой и без неё.
3. Из экспортированного лога посмотреть записи `CapsuleSystemMedia audit phase=immediate` и `phase=settled`. **Если `mediaTokenOnOwnId=false` или `ownMediaIdPresent=false`, дефект ещё внутри Capsule/Media3.** Если все true, системная медиа-карточка Android работает, но HONOR по-прежнему не показывает островок, проверять настройки разрешений Magic Capsule и правила OEM; Android-приложение не может насильно добавить неподдерживаемую функцию.
4. Полезный необязательный ADB: `adb logcat -d -s CapsuleSystemMedia:I`. Не публиковать `dumpsys notification --noredact` целиком: он содержит другие приложения и личные данные.
5. Проверить Bluetooth и экран блокировки: Session должен оставаться тем же после `MainActivity.onStop()`; музыка не должна замолкать или сбрасываться.

### Правило приёмки

- Сборка и unit-тесты проходят; ни одного прямого вызова `super.onUpdateNotification(mediaSession, ...)` или generic music `CATEGORY_SERVICE` в `MusicService`.
- `MediaItem.metadata.isPlayable == true`; метаданные содержат title, artist, artworkUri; bitmap ограничен.
- На HONOR после Home образуется системный плеер, по нажатию раскрывается, пауза/следующий трек/обложка обновляются; перезапуск Activity не сбрасывает воспроизведение.
- Параллельные YouTube/Audius downloads не обрывают stream, нет роста тепла от notification polling; безопасность browse-контроллеров не регрессировала.

**Ограничение:** реальный переход Magic Capsule пока должен подтвердить телефон пользователя. Тесты на CI не проверяют проприетарную прошивку HONOR. Нельзя считать проблему решённой только после успешного Gradle.
