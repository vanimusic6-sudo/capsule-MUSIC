# Cannot round NaN в SeekableTransitionState

Дата: 24 октября 2026. База: `24fcead1c80997931e19d53a5addb359e7816c7c`.

## Причина и воспроизведение

R8 mapping подписанной release-сборки `24fcead1` имеет pg_map_id
`17a9d8aacf5126a84b0745061a5a17919db61a7c03b803375ed9148e8fd5813d`,
точно совпадающий с пользовательским отчётом HONOR VKJ-NX9 / Android 17 SDK 37.
Mapping предыдущей сборки имеет другой ID и для этой трассировки не использовался.
Стандартный R8 Retrace восстановил:

```
kotlin.math.MathKt__MathJVMKt.roundToLong(MathJVM.kt:741)
androidx.compose.animation.core.SeekableTransitionState.seekToFraction(Transition.kt:847)
androidx.compose.animation.core.SeekableTransitionState.animateOneFrameLambda$lambda$0(Transition.kt:439)
```

В Compose Animation 1.12.1 fraction умножался на длительность перехода и сразу
округлялся. Нулевая длительность и некорректный стартовый/прерванный кадр могут
дать NaN. Это ошибка вычислений внутри библиотеки, а не roundToInt размеров
обложки и не механизм скачивания.

Добавлен детерминированный тест с настоящими SeekableTransitionState/Compose
runtime и управляемым frame clock. До обновления библиотек два сценария
падали с тем же `IllegalArgumentException: Cannot round NaN value`:
нулевая длительность с обратным временем второго кадра и со служебным
Long.MIN_VALUE в первом кадре. Тест конечного обычного перехода и новый
формат crash report при этом проходили (4 теста, 2 NaN failures).

## Исправление

Использован официальный патч AndroidX b/555294641 из 1.13.0-beta02:
корректная обработка первого кадра, нулевой длительности и нечислового
результата перед продвижением перехода. Catch-all обработчик кадров или
подавление IllegalArgumentException не добавлялись.

На дату исправления стабильная Animation 1.12.1 не содержит этого патча.
Animation/runtime/UI явно закреплены на 1.13.0-beta02; UI требуется транзитивно
самой Animation. Foundation остаётся 1.12.1, версии Material3 и остальных
зависимостей не менялись. Это осознанное использование beta-компонентов,
а не заявление об их стабильном статусе. Разрешённый release runtime graph
проверен задачей dependencies.

Требование AAR — compileSdk 37.1. Обновлён только compile SDK;
targetSdk остаётся 36, minSdk — 26. CI явно устанавливает android-37.1.
Параметры жестов, длительности/кривые анимаций, цвета и экранные компоненты
приложения не перерисовывались. Воспроизведение и скачивание сохранены.

Заголовок экспорта теперь `Capsule crash report`. Добавлена строка Build
с Git SHA, build type и ABI, чтобы одинаковый versionCode разных проверочных
APK больше не мешал выбирать точный mapping. Stack trace сохраняется целиком.

## Проверки

- Все пять регрессионных motion-сценариев проходят: два воспроизводивших NaN,
  отключённые анимации, положительный accessibility duration scale и прерывание
  обычного конечного перехода. CrashReportTest проверяет заголовок, Build и stack.
- Исправлены только fixtures четырёх старых навигационных тестов: добавлен
  обязательный inset provider, который уже есть в MainActivity; сценарии вкладок
  используют текущие home/search/library вместо бывших history/stats. Проверки
  состояний lifecycle, Back, уничтожения ViewModel и восстановления состояния
  не удалялись. Эти 34 теста проходят на своих конфигурациях Robolectric.
- Полный локальный прогон приложения: 1196 тестов, 13 failures, 0 errors/skips.
  Все 13 соответствуют ранее существовавшим проверкам: HomeStartupStabilityTest (1),
  LyricsUiRegressionTest (7), ScreenBottomInsetTest (1), ScreensTest (1),
  SearchBarInsetTest (1), StandardChromeTest (2). Они не исправлялись изменением
  авторского интерфейса. Полный набор нельзя считать зелёным.
- Расширенный целевой набор из этого же полного прогона: 601 тест, 0 failures,
  errors или skips. Он включает музыкальное ядро, загрузки, новый NaN regression,
  crash report и четыре навигационных класса. Именно этот набор включён в CI;
  дополнительно CI выполняет innertube, JSON runtime, lint и debug/release сборки.

Физическое устройство не подключено. Тесты используют Robolectric SDK 28/35 и
управляемые кадры; это не проверка плавности/60–120 Hz на HONOR. Пользовательский
crash сопоставлен с точным release mapping, но исправленный APK на устройстве
пользователя в этом окружении не запускался.

## Первичные источники

- https://android.googlesource.com/platform/frameworks/support/+/d13c5eee40700a5ea006ecc081424b6475b35230%5E1..d13c5eee40700a5ea006ecc081424b6475b35230/
- https://developer.android.com/jetpack/androidx/releases/compose-animation
- https://developer.android.com/build

Исходник Transition.kt проверен также из Google Maven sources.jar для 1.12.1;
номер строки 847 совпадает с Retrace и вызовом roundToLong в сообщённом crash.
