package dev.snapbudget.domain

import java.util.Locale

/** Category policy kept independent of Android and persistence. */
object CategoryCatalog {
    val predefined: List<String> = listOf(
        "Food", "Groceries", "Transport", "Shopping", "Bills", "Health",
        "Entertainment", "Travel", "Education", "Other",
    )

    fun normalizeName(value: String): String = value.trim()

    fun validationError(value: String): String? {
        val name = normalizeName(value)
        return when {
            value.any { it.isISOControl() } -> "Category names cannot contain control characters."
            name.isEmpty() -> "Enter a category name."
            name.length > 60 -> "Use 60 characters or fewer."
            else -> null
        }
    }

    /** Combines current, persisted and legacy names while keeping the first spelling of duplicates. */
    fun choices(custom: Iterable<String>, observed: Iterable<String>, selected: String): List<String> {
        val names = linkedMapOf<String, String>()
        (predefined.asSequence() + custom.asSequence() + observed.asSequence() + sequenceOf(selected))
            .map(::normalizeName)
            .filter(String::isNotEmpty)
            .forEach { names.putIfAbsent(it.lowercase(Locale.ROOT), it) }
        return names.values.toList()
    }

    fun existingName(value: String, choices: Iterable<String>): String? {
        val key = normalizeName(value).lowercase(Locale.ROOT)
        return choices.firstOrNull { normalizeName(it).lowercase(Locale.ROOT) == key }
    }
}
