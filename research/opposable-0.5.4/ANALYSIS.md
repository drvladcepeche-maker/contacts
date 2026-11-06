# Opposable 0.5.4 — статический анализ APK

Дата анализа: 2026-11-06  
Исходный файл: `opposable-0.5.4.apk`  
Размер: 43,101,026 байт (≈41.1 MiB)  
SHA-256: `7132e9ebfc5f5bdc7b89287ec50b32a42f302d6c54a9dad4ad23e348b888578b`

## 1. Идентификация

| Поле | Значение |
|---|---|
| Package | `com.getopposable.app` |
| Version name | `0.5.4` |
| Version code | `19` |
| minSdk | 30 (Android 11) |
| targetSdk | 35 (Android 15) |
| compileSdk | 35 |
| Основной экран | `dev.phoneuse.agent.MainActivity` |
| Accessibility service | `dev.phoneuse.agent.PhoneControlService` |
| APK ABI | В APK обнаружены native-библиотеки только для `arm64-v8a` |
| allowBackup | false |
| usesCleartextTraffic | true |
| largeHeap | true |

APK является ZIP-контейнером, содержит один `classes.dex`, Android resources и набор нативных библиотек. Подпись APK и сертификат отдельно не проверялись.

## 2. Разрешения из AndroidManifest.xml

### Объявлены приложением

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `POST_NOTIFICATIONS`
- `USE_FULL_SCREEN_INTENT`
- `READ_CALENDAR`
- `WRITE_CALENDAR`
- `READ_CONTACTS`
- `REQUEST_INSTALL_PACKAGES`
- `RECEIVE_BOOT_COMPLETED`
- `SCHEDULE_EXACT_ALARM`
- `USE_EXACT_ALARM`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `WAKE_LOCK`

Также объявлено внутреннее permission приложения `com.getopposable.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

### Важные отсутствующие разрешения

В манифесте не обнаружены `READ_SMS`, `SEND_SMS`, `READ_CALL_LOG`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `CAMERA`, `RECORD_AUDIO`. Это означает, что обычный runtime-доступ к этим данным через соответствующие Android permissions APK не запрашивает. Однако Accessibility Service может видеть содержимое интерфейса других приложений и выполнять действия от имени пользователя в пределах выданных ей возможностей. Поэтому отсутствие этих разрешений не делает приложение малопривилегированным.

В DEX встречается строка `android.permission.READ_SMS`, но такого permission нет в итоговом манифесте. По одной строке нельзя заключать, что приложение реально получает доступ к SMS через этот permission. В строках интерфейса описано чтение одноразовых кодов из SMS/уведомлений через notification shade.

## 3. Компоненты Android

### Основные

- Activity: `dev.phoneuse.agent.MainActivity` — экспортирована, содержит LAUNCHER intent-filter.
- Accessibility service: `dev.phoneuse.agent.PhoneControlService`, защищена системным `android.permission.BIND_ACCESSIBILITY_SERVICE`, не экспортирована; подключается через стандартный Accessibility intent и meta-data `android.accessibilityservice`.
- Receiver: `dev.phoneuse.agent.ConfigureReceiver` — экспортирован, защищён `android.permission.WRITE_SECURE_SETTINGS`; принимает действия `com.getopposable.app.CONFIGURE`, `com.getopposable.app.TYPE`, а также варианты с namespace `dev.phoneuse.agent`.
- Receiver: `dev.phoneuse.agent.BootReceiver` — экспортирован; получает `BOOT_COMPLETED` и `LOCKED_BOOT_COMPLETED`.
- Внутренние receivers: `HandoffReceiver`, `InstallResultReceiver`, `ScheduleReceiver`, `RelayKeepAlive`, `TrialReminderReceiver`, `NudgeReceiver`, `IdeaReceiver`.
- FileProvider: `androidx.core.content.FileProvider`, authority `com.getopposable.app.files`, не экспортирован.

Также присутствуют AndroidX WorkManager services/receivers, включая фоновые и foreground-компоненты.

### Что это означает

Приложение рассчитано на длительную работу в фоне, восстановление после перезагрузки и планирование задач. В манифесте есть разрешения для точных будильников, исключения из оптимизации батареи, foreground service и wake lock. Само наличие этих разрешений не доказывает, что каждая функция постоянно активна.

## 4. Модель управления телефоном через Accessibility

В DEX обнаружены классы и строки, относящиеся к `PhoneControlService`, включая:

- получение скриншотов/изображения экрана;
- чтение элементов интерфейса и их текстовых описаний;
- поиск и нажатие по видимому тексту или элементам;
- tap, swipe, gesture;
- ввод текста;
- системные клавиши: back, home, recents, notifications, quick settings, lock, screenshot, enter, delete, регулировка громкости;
- запуск приложений и открытие URL;
- чтение уведомлений и одноразовых кодов из уведомлений;
- ожидание появления заданного текста на экране;
- проверка изменения состояния экрана после действия.

В строках есть JSON-подобные описания инструментов `launch_app`, `tap_element`, `type_text`, `press_key`, `open_url`, `swipe`, `wait_for`. Также встречаются `tap_text`, `tap`, `read`, `screenshot`, `list_apps`, `ui_elements`.

**Вывод:** ключевой механизм Opposable — не скрытая системная привилегия, а Android Accessibility Service, которая после явного включения пользователем получает возможность наблюдать и управлять UI других приложений. Это наиболее важная часть для сравнения с нашей реализацией управления Brave.

В строках также обнаружена логика, запрещающая агенту нажимать элементы на собственных экранах Opposable, в частности на экранах подтверждения платежей. Подтверждение платежа предназначено владельцу телефона; после изменения платежного экрана подтверждение аннулируется и требуется новый запрос.

## 5. Сеть, сопряжение и MCP

Обнаружены следующие адреса и протокольные признаки:

- `https://opposable-relay.fly.dev`
- `https://opposable-relay.fly.dev/models`
- `https://opposable-relay.fly.dev/models/owl-vit-t400-wi8.tflite`
- `https://getopposable.com/mcp`
- `https://api.openai.com/v1`
- WebSocket-клиент и HTTP-клиент (OkHttp).

В DEX присутствуют классы `RelayClient`, `McpHttpServer`, `PhoneAgentMcp`. Строки интерфейса указывают на два режима связи:

1. Локальный MCP-сервер телефона, доступный по локальному адресу/порту; упоминаются endpoint `/mcp` (JSON-RPC) и REST API под `/api`.
2. Удалённый relay Opposable для доступа с компьютера/агента через мобильную сеть или Wi-Fi без открытия портов. В строках упоминаются pairing code, phone_id, client_token и client_secret.

В приложении есть текст о подключении Claude, ChatGPT, Claude Code и Codex. Наличие этих строк показывает предусмотренные интеграции, но не доказывает, что каждая из них активна без настройки/подписки.

В манифесте выставлено `usesCleartextTraffic=true`. Это допускает cleartext HTTP на уровне политики приложения; фактическое использование незашифрованного HTTP в конкретных запросах по одному манифесту не установлено. Обнаруженные основные relay/MCP URL используют HTTPS.

## 6. Локальная модель и нативный код

В APK присутствуют библиотеки:

- `libLiteRt.so`, `libLiteRtClGlAccelerator.so`
- `liblitert_jni.so`, `liblitertlm_jni.so`
- Qualcomm QNN: `libQnnHtp.so`, `libQnnSystem.so`, HTP skeleton/stub для V73, V75, V79 и V81
- `libopposable_owl.so`

Это указывает на интеграцию LiteRT/LiteRT-LM и Qualcomm QNN, включая аппаратное ускорение на совместимых устройствах.

В строках обнаружены названия:
- `GUI-Owl 1.5 2B` — модель для интерпретации экранов;
- `GUI-Owl-1.5-2B-Q4_0.gguf`;
- `mmproj-GUI-Owl-1.5-2B-q8_0.gguf`;
- `owl-vit-t400-wi8.tflite`;
- варианты Qwen3 0.6B/1.7B и Gemma 4 E2B LiteRT.

Основные веса моделей в просмотренном списке APK не обнаружены. По строкам приложение предлагает загрузить screen model отдельно; следовательно, APK содержит движок/нативную инфраструктуру, а часть моделей загружается позднее. Реальная доступность конкретной модели зависит от устройства и завершения загрузки.

## 7. Контакты, календарь, сообщения

Манифест содержит `READ_CONTACTS`, `READ_CALENDAR`, `WRITE_CALENDAR`. Это подтверждает заявленную Android-интеграцию с контактами и календарём на уровне permissions, но не доказывает, какие именно операции выполняются и когда.

Строки DEX описывают работу с уведомлениями, чтение последних уведомлений и извлечение одноразового кода из SMS/уведомлений. Прямые SMS permissions отсутствуют. Для реальной оценки нужно проверить код NotificationListener/Accessibility и поведение установленного приложения.

## 8. Предварительная оценка безопасности и приватности

1. **Высокий уровень возможностей после включения Accessibility.** Служба может читать содержимое экранов и выполнять действия в других приложениях. Это нормальная основа такого продукта, но разрешение следует считать чувствительным.
2. **Удалённое управление.** Relay/MCP-подключение позволяет внешнему агенту отправлять команды телефону. Критичны защита pairing credentials, токена, локального MCP и контроль подключённых компьютеров.
3. **Фоновая устойчивость.** Boot receiver, WorkManager, foreground service, wake lock и alarm permissions рассчитаны на продолжительную работу.
4. **Установка APK.** `REQUEST_INSTALL_PACKAGES` позволяет приложению инициировать установку пакетов после системного подтверждения.
5. **Платежные ограничения.** В кодовых строках есть отдельный механизм owner approval для платежей и запрет агенту подтверждать собственные approval screens.
6. **Cleartext policy.** `usesCleartextTraffic=true` — повод проверить сетевые вызовы; это не доказательство передачи данных открытым текстом.
7. **Телеметрия.** В DEX есть описание отправки нескольких анонимных шагов настройки с случайным install ID и утверждение, что экран/идентифицирующие данные не отправляются. Это текст продукта, а не независимая проверка фактического сетевого payload.

На основании этого анализа нельзя утверждать, что Opposable является шпионским ПО или, наоборот, что он безопасен. Для такого вывода нужны декомпиляция логики, проверка TLS/сетевых запросов, runtime-наблюдение и изучение серверной стороны.

## 9. Сравнение с нашей реализацией Brave Accessibility

Полезные архитектурные идеи, которые стоит изучить отдельно:

- единая Accessibility Service как исполнитель команд;
- структурированное чтение UI (элементы, текст, доступность, bounds), а не только screenshot;
- действия tap по тексту/элементу и gesture/swipe;
- подтверждение каждого действия проверкой обновлённого экрана;
- ожидание конкретного UI-состояния с ограниченным timeout;
- явная передача владельцу действий, которые требуют его подтверждения;
- журнал действий с outcome, не сохраняя введённый текст;
- разделение локального MCP и удалённого relay;
- отдельная локальная vision-модель для экранов, если UI не представлен корректно через Accessibility tree.

Для Brave особенно полезно перенести принцип «действие → повторное чтение окна → проверка результата», а не полагаться на один broadcast или на то, что запуск Activity автоматически означает активное окно.

## 10. Ограничения текущего анализа

Это **статический первичный анализ**, а не полный аудит:

- AndroidManifest.xml разобран из бинарного AXML; версия SDK, permissions, activities, services и receivers извлечены.
- DEX исследован по строкам и именам классов/методов. Полная декомпиляция Kotlin/Java bytecode не выполнена.
- XML-конфигурация Accessibility Service, на которую ссылается resource ID, пока не извлечена из resources.arsc.
- Нативные библиотеки перечислены, но не дизассемблировались.
- Сетевые запросы и поведение установленного APK не перехватывались.
- APK не устанавливался и не запускался в рамках этого анализа.
- Подпись, сертификат, хеши отдельных библиотек и проверка целостности внешних моделей не исследовались.

Следующий этап: извлечь Accessibility XML из resources.arsc, декомпилировать DEX, изучить RelayClient/McpHttpServer/PhoneControlService/Guard/OwlAgent и затем выполнить контролируемый тест на телефоне с наблюдением сетевой активности.

## 11. Повторяемость и идентификатор файла

Для сопоставления исходного APK с будущими копиями использовать SHA-256 из начала документа. Сам бинарный APK в эту ветку не включён: файл около 41 MiB, а запись бинарного объекта через текущий GitHub workflow не настроена на Git LFS. Исходный файл остаётся в текущем диалоге; при повторном анализе в другом чате его можно идентифицировать по SHA-256.



---

# Дополнение 1. Углублённый разбор DEX и ресурсов (2026-11-06)

Это дополнение основано на извлечении строк и идентификаторов методов из `classes.dex`, чтении code_item для выбранных методов и разборе бинарного XML `res/az.xml`. Это уже глубже простого просмотра строк, но не полная декомпиляция в исходный Kotlin.

## 12. Реальная конфигурация Accessibility Service

Извлечён файл `res/az.xml`, на который ссылается `PhoneControlService` в манифесте:

- `accessibilityEventTypes = 0x401860`: window state changed, view focused, window content changed, view scrolled, windows changed.
- `accessibilityFeedbackType = 0x10`: generic feedback.
- `notificationTimeout = 50 ms`.
- `accessibilityFlags = 0x52`: include not-important views (0x02), report view IDs (0x10), retrieve interactive windows (0x40).
- `canRetrieveWindowContent = true`.
- `canPerformGestures = true`.
- `canTakeScreenshot = true`.
- `isAccessibilityTool = true`.
- Package allowlist в XML не задана: служба не ограничена одним приложением через `android:packageNames`.

Последний пункт важен: Opposable рассчитан на управление разными приложениями. Наша служба намеренно ограничена Brave через `android:packageNames=com.brave.browser`, что уменьшает область доступа.

## 13. Подтверждённая логика выбора активного окна

В `PhoneControlService` найдены методы `frontAppWindow`, `frontPackage`, `appRootFallback`, `windowPackageAt`, `activeWindowQuiet`.

Алгоритм `frontAppWindow` по инструкциям DEX:

1. Получить `AccessibilityService.getWindows()`.
2. Оставить окна типа `TYPE_APPLICATION`.
3. Выбрать первое сфокусированное окно.
4. Если сфокусированного нет — выбрать окно с наибольшим `layer`.
5. Если подходящее окно всё ещё не выбрано — использовать активное окно.

`frontPackage` получает root выбранного окна и читает `root.packageName`. Дополнительный fallback — `getRootInActiveWindow()`, но он отбрасывает окно `com.android.systemui`.

Метод `launchApp`:

- вызывает `PackageManager.getLaunchIntentForPackage(app)`;
- запускает Activity через `startActivity`;
- добавляет флаги `0x10200000` (NEW_TASK + RESET_TASK_IF_NEEDED);
- после запуска в течение примерно 4 секунд опрашивает Accessibility windows, отбирает application windows, получает их root и ищет нужный package name.

**Это ключевое архитектурное отличие от нашей текущей Brave-реализации.** Мы используем только `rootInActiveWindow`, который в последнем тесте возвращал Termux. Opposable не ограничивается этим одним root: он сканирует доступные окна и отдельно определяет окно приложения.

## 14. Стабилизация интерфейса и проверка действий

В DEX подтверждены:

- `fingerprint` и `settleAfter`: снимают отпечаток текущего UI, ждут изменения/стабилизации и учитывают недавние Accessibility events и возраст активного окна.
- `waitForPageContent`: циклически ищет узлы и ожидает появления содержимого.
- `captureBitmap`: использует системный `AccessibilityService.takeScreenshot`, ждёт асинхронный callback через `CompletableFuture` (таймаут ожидания в коде — 10 секунд).
- `elements`, `findNodes`, `readText`: строят структурированное представление UI-дерева.
- `tapElement`, `tap`, `swipe`, `typeText`, `pressKey`: отдельные исполнительные методы, а не один общий универсальный tap.
- `typedCheck` в `OwlAgent`: после ввода проверяет, появился ли текст в поле; если не появился, может повторить ввод и сообщить, что поле не подтвердило результат.

Это не просто «отправить действие и вернуть boolean». Архитектура предусматривает чтение состояния до действия, паузу на стабилизацию и проверку результата.

## 15. Запуск сервиса, watchdog и модели

При `onServiceConnected` Opposable:

1. запускает `AppEvents`;
2. обновляет сведения о версии, в которой Accessibility Service запускалась;
3. создаёт notification channel;
4. перезапускает локальный MCP-сервер;
5. запускает `RelayClient`;
6. прогревает `OwlBrain`;
7. запускает watchdog MCP.

В коде `PhoneControlService` есть методы `restartMcp`, `startWatchdog`, `mcpStatus`. Это объясняет, почему локальный сервер и relay тесно связаны с жизненным циклом Accessibility Service.

## 16. Локальный MCP: фактическая схема

Код `McpHttpServer` подтверждает:

- отдельный accept-thread с именем `mcp-accept`;
- попытку привязки ServerSocket к `0.0.0.0`, с fallback на loopback;
- для каждого принятого соединения создаётся обработчик в executor;
- HTTP socket timeout задаётся примерно на 120 секунд;
- запросы к защищённым маршрутам проверяются по Bearer token;
- авторизация сравнивает байтовые значения через `MessageDigest.isEqual`;
- поддерживаются `Authorization: Bearer <token>` и query-параметр `token`;
- `GET /` возвращает справку;
- `GET /api` возвращает описание REST API;
- `/api/*` направляется в `PhoneApi.rest`;
- `POST /mcp` принимает JSON-RPC; SSE-поток не поддерживается;
- обрабатывается заголовок `Mcp-Session-Id`.

Справка API содержит endpoints для health, screen, do, tasks, recipes, audit, code и schedules. Следовательно, локальный MCP — не только транспорт для UI-команд, но и общий API телефона.

**Замечание безопасности:** привязка к `0.0.0.0` может сделать порт доступным с других устройств той же сети. Встроенная Bearer-проверка — обязательный барьер, но точный порт, сетевые ACL и способ хранения локального токена требуют отдельной проверки. Query-token менее предпочтителен, чем заголовок, поскольку URL может попадать в логи.

## 17. Relay: регистрация и сопряжение

Из тела методов `RelayClient` подтверждены следующие операции:

- регистрация телефона: `POST /v1/phones`; передаются signup key и имя; в ответе сохраняются `phone_id`, `phone_secret`, `client_token`;
- старт pairing: `POST /v1/pair/start`, Bearer phone_secret и `phone_id`; возвращаются pairing `code`, `expires_in`, `connect_url`, `cli`;
- проверка pairing: `/v1/pair/status?code=`;
- WebSocket-соединение: `/v1/phone/connect`, Bearer phone_secret и заголовок `X-Opposable-Device`;
- ротация client token: `POST /v1/phones/{phone_id}/token/rotate`;
- при регистрации код явно отвергает relay URL, который не начинается с `https://`.

Это подтверждает двухуровневую модель: локальный MCP на телефоне и отдельный relay для доступа извне. Не следует путать `client_token` и `phone_secret`: в изученных методах они используются для разных операций.

## 18. Guard и платежные подтверждения

В классе `Guard` подтверждены методы `isPayment`, `paymentShown`, `payment`, `assess`, `check`, `ownScreen`, `cover`, `coverRefusal`.

Строки и регулярные выражения показывают эвристическую классификацию финансового действия по нескольким группам признаков: слова purchase/pay/transfer/subscribe/donate/top up; платёжные кнопки и формулировки; суммы, валюта, получатель, IBAN, checkout, order summary и др. Есть отдельная проверка «скользящего подтверждения» и действий вроде swipe to pay.

`ownScreen` блокирует действия агента на экранах самого Opposable: настройки, задачи и экран Approve/Deny. Владелец телефона должен ответить сам. После изменения платёжного экрана прежнее одобрение становится недействительным и запрашивается заново.

Это важный пример принципа безопасности: агент может подготовить действие, но не должен сам подтверждать критическое действие на экране владельца.

## 19. Обновлённый статус анализа

Подтверждены на уровне DEX-инструкций и ресурсов: Accessibility configuration, выбор окна, запуск приложений, стабилизация UI, screenshot API, основные методы RelayClient, локальный HTTP/MCP и часть Guard.

Не подтверждены: полная семантика каждого obfuscated метода, точный порт локального MCP, внутренний формат всех WebSocket сообщений, точное содержимое отправляемой телеметрии, фактическое шифрование локального хранилища токенов и поведение сетевых запросов в runtime.
