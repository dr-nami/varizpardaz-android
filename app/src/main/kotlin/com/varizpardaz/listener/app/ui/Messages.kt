package com.varizpardaz.listener.app.ui

import androidx.annotation.StringRes
import com.varizpardaz.listener.app.R
import com.varizpardaz.listener.app.data.RepairReason
import com.varizpardaz.listener.core.PairingLink
import com.varizpardaz.listener.core.pairing.PairingFailure

/** A message to show: a string resource, plus text the server sent (already Persian and show-safe) if any. */
class UiMessage(@param:StringRes val resId: Int, val extra: String? = null)

/** One Persian message per way pairing can fail. `when` is exhaustive on purpose: a new case must get a message. */
fun PairingError.toMessage(): UiMessage = when (this) {
    is PairingError.BadLink -> UiMessage(
        when (problem) {
            PairingLink.Problem.NOT_A_PAIRING_LINK -> R.string.err_not_a_pairing_link
            PairingLink.Problem.UNSUPPORTED_VERSION -> R.string.err_unsupported_version
            PairingLink.Problem.BAD_BASE_URL -> R.string.err_bad_base_url
            PairingLink.Problem.BAD_CODE -> R.string.err_bad_code
        },
    )
    is PairingError.Pairing -> failure.toMessage()
    PairingError.CameraDenied -> UiMessage(R.string.err_camera_denied)
    PairingError.StorageFailed -> UiMessage(R.string.err_storage_failed)
    PairingError.AlreadyPaired -> UiMessage(R.string.err_already_paired)
}

fun PairingFailure.toMessage(): UiMessage = when (this) {
    PairingFailure.CodeInvalid -> UiMessage(R.string.err_code_invalid)
    is PairingFailure.RateLimited -> UiMessage(R.string.err_rate_limited)
    PairingFailure.Network -> UiMessage(R.string.err_network)
    PairingFailure.Tls -> UiMessage(R.string.err_tls)
    is PairingFailure.Server -> UiMessage(R.string.err_server)
    PairingFailure.BadResponse -> UiMessage(R.string.err_bad_response)
    is PairingFailure.Rejected -> UiMessage(R.string.err_rejected, message)
}

@StringRes
fun RepairReason.bodyRes(): Int = when (this) {
    RepairReason.STORAGE_UNREADABLE -> R.string.repair_body_unreadable
    RepairReason.REVOKED -> R.string.repair_body_revoked
    RepairReason.SECRET_REJECTED -> R.string.repair_body_secret_rejected
}
