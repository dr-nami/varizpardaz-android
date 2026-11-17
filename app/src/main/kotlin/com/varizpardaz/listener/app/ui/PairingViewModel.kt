package com.varizpardaz.listener.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.varizpardaz.listener.app.BuildConfig
import com.varizpardaz.listener.app.data.PairResult
import com.varizpardaz.listener.app.data.PairingRepository
import com.varizpardaz.listener.core.PairingLink
import com.varizpardaz.listener.core.pairing.PairingFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything that can go wrong on the way from "scan" to "paired", each with its own Persian message. */
sealed interface PairingError {
    class BadLink(val problem: PairingLink.Problem) : PairingError

    class Pairing(val failure: PairingFailure) : PairingError

    data object CameraDenied : PairingError

    data object StorageFailed : PairingError

    data object AlreadyPaired : PairingError
}

sealed interface PairingUiState {
    data object Idle : PairingUiState

    data object Working : PairingUiState

    class Failed(val error: PairingError) : PairingUiState
}

class PairingViewModel(private val repository: PairingRepository) : ViewModel() {
    private val _ui = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val ui: StateFlow<PairingUiState> = _ui.asStateFlow()

    /** Text from the QR scanner or, in debug builds, from the paste field. */
    fun onLinkText(text: String) {
        if (_ui.value is PairingUiState.Working) return
        val parsed = PairingLink.parse(
            text = text,
            scheme = BuildConfig.PAIR_SCHEME,
            host = BuildConfig.PAIR_HOST,
            allowInsecureHttp = BuildConfig.ALLOW_INSECURE_HTTP,
        )
        when (parsed) {
            is PairingLink.Result.Invalid -> _ui.value = PairingUiState.Failed(PairingError.BadLink(parsed.problem))
            is PairingLink.Result.Ok -> {
                _ui.value = PairingUiState.Working
                viewModelScope.launch {
                    _ui.value = when (val result = repository.pair(parsed.link)) {
                        PairResult.Paired -> PairingUiState.Idle // the screen switches because repository state changes
                        PairResult.AlreadyPaired -> PairingUiState.Failed(PairingError.AlreadyPaired)
                        PairResult.StorageFailed -> PairingUiState.Failed(PairingError.StorageFailed)
                        is PairResult.Failed -> PairingUiState.Failed(PairingError.Pairing(result.failure))
                    }
                }
            }
        }
    }

    fun onCameraDenied() {
        _ui.value = PairingUiState.Failed(PairingError.CameraDenied)
    }

    fun dismissError() {
        if (_ui.value is PairingUiState.Failed) _ui.value = PairingUiState.Idle
    }
}
