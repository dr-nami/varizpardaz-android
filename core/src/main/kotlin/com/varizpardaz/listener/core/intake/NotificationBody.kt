package com.varizpardaz.listener.core.intake

/**
 * Turns the text fields of a bank-app notification into one body the classifier can read. Bank templates for the
 * notification channel are written against exactly this composition (title, then the main text, then the rest, one per
 * line), so it must not change casually; real samples decide the final shape (see docs/test-plan.md).
 */
object NotificationBody {
    fun compose(
        title: CharSequence?,
        text: CharSequence?,
        bigText: CharSequence?,
        subText: CharSequence?,
        lines: List<CharSequence>,
    ): String? {
        // The expanded text normally contains the collapsed one; do not send the same sentence twice.
        val main = (bigText?.toString()?.takeIf { it.isNotBlank() } ?: text?.toString())
        val parts = ArrayList<String>()
        for (candidate in listOf(title?.toString(), main, subText?.toString()) + lines.map { it.toString() }) {
            val trimmed = candidate?.trim().orEmpty()
            if (trimmed.isNotEmpty() && trimmed !in parts) parts += trimmed
        }
        return parts.joinToString("\n").takeIf { it.isNotEmpty() }
    }
}
