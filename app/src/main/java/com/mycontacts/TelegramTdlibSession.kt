package com.mycontacts

import android.content.Context
import android.os.Build
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import java.io.ByteArrayOutputStream
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
    onStatus: (String) -> Unit,
    onAuthorized: () -> Unit
) {
    @Volatile private var onStatus: (String) -> Unit = onStatus
    @Volatile private var onAuthorized: () -> Unit = onAuthorized
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("telegram_tdlib", Context.MODE_PRIVATE)
    private val ready = AtomicBoolean(false)
    @Volatile private var client: Client? = null
    @Volatile private var apiId: Int = 0
    @Volatile private var apiHash: String = ""

    companion object {
        @Volatile private var processSession: TelegramTdlibSession? = null

        fun get(
            context: Context,
            onStatus: (String) -> Unit,
            onAuthorized: () -> Unit
        ): TelegramTdlibSession = synchronized(this) {
            val existing = processSession
            if (existing != null) {
                existing.attach(onStatus, onAuthorized)
                existing
            } else {
                TelegramTdlibSession(context, onStatus, onAuthorized).also { processSession = it }
            }
        }
    }

    private fun keystoreKey(): java.security.Key {
        val alias = "moi_contacts_tdlib_aes"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generator.generateKey()
        }
        return store.getKey(alias, null)
    }

    private fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteArrayOutputStream().apply { write(cipher.iv); write(encrypted) }.toByteArray()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun unseal(value: String): String {
        if (value.isBlank()) return ""
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > 12) { "Повреждены локальные данные Telegram" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return cipher.doFinal(packed.copyOfRange(12, packed.size)).toString(Charsets.UTF_8)
    }

    /** Rebind UI callbacks when the Activity is recreated; never create a second TDLib client. */
    fun attach(onStatus: (String) -> Unit, onAuthorized: () -> Unit) {
        this.onStatus = onStatus
        this.onAuthorized = onAuthorized
        if (ready.get()) {
            onStatus("Telegram подключён.")
            onAuthorized()
        }
    }

    fun start(id: Int, hash: String) {
        require(id > 0 && hash.isNotBlank()) { "Укажите Telegram api_id и api_hash" }
        // TDLib owns an exclusive lock on its database. Keep one client for the whole app process.
        if (client != null) {
            onStatus(if (ready.get()) "Telegram уже подключён." else "Подключение Telegram уже выполняется.")
            return
        }
        apiId = id
        apiHash = hash.trim()
        prefs.edit().putInt("api_id", id).putString("api_hash_enc", seal(apiHash)).apply()
        onStatus("Подключение к Telegram…")
        client = Client.create({ result -> handle(result) }, null, null)
    }

    fun resumeIfConfigured(): Boolean {
        val id = prefs.getInt("api_id", 0)
        val hash = try { unseal(prefs.getString("api_hash_enc", "").orEmpty()) } catch (_: Exception) { "" }
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
                val key = try { unseal(prefs.getString("database_key_enc", "").orEmpty()) } catch (_: Exception) { "" }.ifBlank {
                    Base64.encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
                        .also { prefs.edit().putString("database_key_enc", seal(it)).commit() }
                }
                val p = TdApi.SetTdlibParameters()
                p.databaseDirectory = dir.absolutePath
                p.useTestDc = false
                p.databaseEncryptionKey = Base64.decode(key, Base64.NO_WRAP)
                p.useFileDatabase = false
                p.useChatInfoDatabase = false
                p.useMessageDatabase = false
                p.useSecretChats = false
                p.apiId = apiId
                p.apiHash = apiHash
                p.systemLanguageCode = "ru"
                p.deviceModel = Build.MODEL ?: "Android"
                p.applicationVersion = "0.1"
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

    fun submitEmail(email: String) {
        require(email.trim().isNotEmpty()) { "Введите электронную почту" }
        send(TdApi.SetAuthenticationEmailAddress(email.trim()))
    }

    fun submitEmailCode(code: String) {
        require(code.trim().isNotEmpty()) { "Введите код из электронной почты" }
        send(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code.trim())))
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

    fun importPhoneBatch(contacts: Array<TdApi.ImportedContact>, callback: (LongArray?, String?) -> Unit) {
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
