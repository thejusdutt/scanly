package com.scanly.ui.security

import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Documents unlocked in THIS process session. Process death re-locks everything, which
 * is exactly the behavior a per-document lock promises.
 */
@Singleton
class DocumentUnlockRegistry @Inject constructor() {
    private val unlocked = Collections.synchronizedSet(mutableSetOf<Long>())

    fun isUnlocked(documentId: Long): Boolean = documentId in unlocked
    fun markUnlocked(documentId: Long) { unlocked.add(documentId) }
}
