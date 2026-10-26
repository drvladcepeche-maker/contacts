package com.mycontacts

import android.content.Context
import android.os.Build
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local TDLib session. The Telegram API credentials and TDLib database stay on this device.
 * Authentication is interactive; no login code or 2FA password is sent to our bridge/server.
 */
class TelegramTdlibSession(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onAuthorized: () -> Unit
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("telegram_tdlib", Context.MODE_PRIVATE)
    private val ready = AtomicBoolean(false)
    @Volatile private var client: Client? = null
    @Volatile private var apiId: Int = 0
    @Volatile private var apiHash: String = ""

    fun start(id: Int, hash: String) {
        require(id > 0 && hash.isNotBlank()) { "Укажите Telegram api_id и api_hash" }
        apiId = id
        apiHash = hash.trim()
        prefs.edit().putInt("api_id", id).putString("api_hash", apiHash).apply()
        onStatus("Подключение к Telegram…")
        client = Client.create({ result -> handle(result) }, null, null)
    }

    fun resumeIfConfigured(): Boolean {
        val id = prefs.getInt("api_id", 0)
        val hash = prefs.getString("api_hash", "").orEmpty()
        if (id <= 0 || hash.isBlank()) return false
        start(id, hash)
        return true
    }

    private fun handle(result: TdApi.Object) {
        when (result) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(result.authorizationState)
            is TdApi.Error -> onStatus("Telegram: ошибка ${result.code}: ${result.message}")
            else -> Log.d("TelegramTDLib", result.toString())
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val dir = File(app.filesDir, "telegram-tdlib").apply { mkdirs() }
                val key = prefs.getString("database_key", null) ?: java.util.UUID.randomUUID().toString().also {
                    prefs.edit().putString("database_key", it).commit()
                }
                val p = TdApi.SetTdlibParameters()
                p.databaseDirectory = dir.absolutePath
                p.useTestDc = false
                p.databaseEncryptionKey = key.toByteArray(Charsets.UTF_8)
                p.useFileDatabase = false
                p.useChatInfoDatabase = false
                p.useMessageDatabase = false
                p.useSecretChats = false
                p.apiId = apiId
                p.apiHash = apiHash
                p.systemLanguageCode = "ru"
                p.deviceModel = Build.MODEL ?: "Android"
                p.applicationVersion = "0.1"
                p.enableStorageOptimizer = true
                send(p)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> onStatus("Нужен номер телефона Telegram.")
            is TdApi.AuthorizationStateWaitCode -> onStatus("Нужен код подтверждения Telegram.")
            is TdApi.AuthorizationStateWaitPassword -> onStatus("Нужен пароль двухэтапной проверки Telegram.")
            is TdApi.AuthorizationStateReady -> {
                ready.set(true)
                onStatus("Telegram подключён.")
                onAuthorized()
            }
            is TdApi.AuthorizationStateWaitEmailAddress -> onStatus("Telegram требует адрес электронной почты.")
            is TdApi.AuthorizationStateWaitEmailCode -> onStatus("Telegram требует код из электронной почты.")
            is TdApi.AuthorizationStateWaitRegistration -> onStatus("Для этого аккаунта требуется завершить регистрацию в официальном клиенте Telegram.")
            is TdApi.AuthorizationStateLoggingOut -> onStatus("Выход из Telegram…")
            is TdApi.AuthorizationStateClosing -> onStatus("Закрытие сессии Telegram…")
            is TdApi.AuthorizationStateClosed -> { ready.set(false); onStatus("Сессия Telegram закрыта.") }
            else -> onStatus("Состояние авторизации Telegram: ${state.javaClass.simpleName}")
        }
    }

    fun submitPhone(phone: String) {
        require(phone.trim().isNotEmpty()) { "Введите номер телефона" }
        send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null))
    }

    fun submitCode(code: String) {
        require(code.trim().isNotEmpty()) { "Введите код Telegram" }
        send(TdApi.CheckAuthenticationCode(code.trim()))
    }

    fun submitPassword(password: String) {
        require(password.isNotEmpty()) { "Введите пароль Telegram" }
        send(TdApi.CheckAuthenticationPassword(password))
    }

    fun isReady(): Boolean = ready.get()

    fun fetchUsername(userId: Long, callback: (String) -> Unit) {
        send(TdApi.GetUser(userId)) { result ->
            if (result is TdApi.User) {
                try {
                    val usernamesField = result.javaClass.getField("usernames")
                    val usernames = usernamesField.get(result)
                    val active = usernames?.javaClass?.getField("activeUsernames")?.get(usernames)
                    val username = when (active) {
                        is Array<*> -> active.firstOrNull()?.toString().orEmpty()
                        else -> ""
                    }
                    callback(username)
                } catch (_: Exception) {
                    // TDLib's User username representation differs by API version.
                    callback("")
                }
            } else callback("")
        }
    }

    fun importPhoneBatch(contacts: Array<TdApi.Contact>, callback: (LongArray?, String?) -> Unit) {
        if (!ready.get()) {
            callback(null, "Сначала авторизуйтесь в Telegram")
            return
        }
        send(TdApi.ImportContacts(contacts)) { result ->
            if (result is TdApi.ImportedContacts) callback(result.userIds, null)
            else if (result is TdApi.Error) callback(null, "${result.code}: ${result.message}")
            else callback(null, "Неожиданный ответ Telegram: ${result.javaClass.simpleName}")
        }
    }

    private fun send(function: TdApi.Function<*>, callback: ((TdApi.Object) -> Unit)? = null) {
        val c = client ?: run { onStatus("TDLib ещё не запущена."); return }
        c.send(function) { result ->
            if (callback != null) callback(result) else handle(result)
        }
    }
}
