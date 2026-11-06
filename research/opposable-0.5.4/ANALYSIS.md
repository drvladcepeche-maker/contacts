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

