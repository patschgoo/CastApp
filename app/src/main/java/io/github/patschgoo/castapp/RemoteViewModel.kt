package io.github.patschgoo.castapp

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.patschgoo.castapp.remote.RemoteKey
import io.github.patschgoo.castapp.remote.RemoteProviders
import io.github.patschgoo.castapp.remote.RemoteSession
import io.github.patschgoo.castapp.remote.RemoteTarget
import io.github.patschgoo.castapp.remote.RemoteUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the remote screen shows. */
data class RemoteUiState(
    /** The TV being steered (or being connected to). */
    val target: RemoteTarget? = null,
    val session: RemoteSession? = null,
    val connecting: Boolean = false,
    /** Status or instruction while connecting ("Choose Allow on the TV"). */
    val message: String? = null,
    /** What went wrong, in plain words; the screen offers to try again. */
    val error: String? = null,
)

/** A code the TV shows that the user has to type in. */
class CodeRequest(val title: String, val message: String, val answer: CompletableDeferred<String?>)

/** Keeps the connection to the TV across screen rotations and sends key presses in order. */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("remote", Application.MODE_PRIVATE)

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    private val _showList = MutableStateFlow(false)
    /** True while the user picks another TV (the list over a connected remote). */
    val showList: StateFlow<Boolean> = _showList.asStateFlow()

    private val _codeRequest = MutableStateFlow<CodeRequest?>(null)
    val codeRequest: StateFlow<CodeRequest?> = _codeRequest.asStateFlow()

    /** Set while looking for the TV used last time; shown in the list. */
    private val _searchingFor = MutableStateFlow<String?>(null)
    val searchingFor: StateFlow<String?> = _searchingFor.asStateFlow()

    private val actions = Channel<suspend () -> Unit>(capacity = 12)
    private var connectJob: Job? = null
    private var started = false

    private val ui = object : RemoteUi {
        override suspend fun askCode(title: String, message: String): String? {
            val answer = CompletableDeferred<String?>()
            val request = CodeRequest(title, message, answer)
            _codeRequest.value = request
            return try {
                answer.await()?.trim()?.takeIf { it.isNotEmpty() }
            } finally {
                _codeRequest.update { if (it === request) null else it }
            }
        }

        override fun showMessage(message: String) {
            _state.update { it.copy(message = message) }
        }
    }

    init {
        viewModelScope.launch {
            for (action in actions) action()
        }
    }

    /** Opens [targetId] (or the TV used last time) once the providers list it. */
    fun start(targetId: String?, force: Boolean = false) {
        if (started && !force) return
        started = true
        val id = targetId ?: prefs.getString(KEY_LAST, null)
        if (id == null) {
            _showList.value = true
            return
        }
        if (_state.value.target?.id == id && _state.value.error == null) return
        val name = if (targetId == null) prefs.getString(KEY_LAST_NAME, null) else null
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _searchingFor.value = name ?: ""
            val context = getApplication<Application>()
            val flows = RemoteProviders.all.map { it.targets(context) }
            val target = withTimeoutOrNull(12_000) {
                combine(flows) { lists -> lists.flatMap { it } }.first { list -> list.any { it.id == id } }.first { it.id == id }
            }
            _searchingFor.value = null
            if (target != null) {
                connect(target)
            } else {
                _showList.value = true
                if (name != null) _state.value = RemoteUiState(error = context.getString(R.string.remote_not_found, name))
            }
        }
    }

    fun setShowList(show: Boolean) {
        _showList.value = show
    }

    fun connect(target: RemoteTarget) {
        val context = getApplication<Application>()
        connectJob?.cancel()
        _searchingFor.value = null
        _showList.value = false
        closeSession()
        prefs.edit { putString(KEY_LAST, target.id).putString(KEY_LAST_NAME, target.name) }
        _state.value = RemoteUiState(target = target, connecting = true, message = context.getString(R.string.remote_connecting, target.name))
        connectJob = viewModelScope.launch {
            try {
                val session = target.connect(context, ui)
                if (_state.value.target?.id != target.id) {
                    closeQuietly(session)
                    return@launch
                }
                _state.value = RemoteUiState(target = target, session = session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = RemoteUiState(target = target, error = describe(e, target))
            }
        }
    }

    fun retry() {
        _state.value.target?.let { connect(it) }
    }

    fun answerCode(code: String?) {
        _codeRequest.value?.answer?.complete(code)
        _codeRequest.value = null
    }

    fun press(key: RemoteKey) {
        actions.trySend { send { it.press(key) } }
    }

    fun type(text: String) {
        if (text.isEmpty()) return
        actions.trySend { send { it.type(text) } }
    }

    /** Runs [block] on the session; when the TV dropped the connection, reconnects once and tries again. */
    private suspend fun send(block: suspend (RemoteSession) -> Unit) {
        val current = _state.value
        val session = current.session ?: return
        val target = current.target ?: return
        try {
            block(session)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        val context = getApplication<Application>()
        closeQuietly(session)
        _state.value = RemoteUiState(target = target, connecting = true, message = context.getString(R.string.remote_connecting, target.name))
        try {
            val again = target.connect(context, ui)
            _state.value = RemoteUiState(target = target, session = again)
            block(again)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value.session?.let(::closeQuietly)
            _state.value = RemoteUiState(target = target, error = context.getString(R.string.remote_lost, target.name))
        }
    }

    private fun describe(e: Exception, target: RemoteTarget): String {
        val context = getApplication<Application>()
        val message = e.message?.trim()
        // Targets throw readable messages; low-level ones ("ECONNREFUSED…") get a plain sentence instead.
        return if (message.isNullOrEmpty() || e is java.io.IOException) context.getString(R.string.remote_cant_reach, target.name) else message
    }

    private fun closeSession() {
        val s = _state.value.session ?: return
        closeQuietly(s)
        _state.update { it.copy(session = null) }
    }

    /** Closing may say goodbye to the TV over the network: never on the main thread. */
    private fun closeQuietly(s: RemoteSession) {
        Thread({ try { s.close() } catch (_: Exception) {} }, "remote-close").start()
    }

    override fun onCleared() {
        _codeRequest.value?.answer?.complete(null)
        closeSession()
        actions.close()
    }

    companion object {
        private const val KEY_LAST = "last_target"
        private const val KEY_LAST_NAME = "last_target_name"
    }
}
