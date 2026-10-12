package com.getvideo.app.data.repo

import android.content.Context
import java.io.File

/** A short-lived ticket can be provisioned privately on a verified legacy device. */
class CredentialMigrationStore(context: Context) {
    private val file = File(context.filesDir, "credential-migration.enc")
    private val pending = File(context.filesDir, "credential-migration.pending")
    fun ticket(): String? {
        if (pending.exists()) {
            val bytes = pending.readBytes()
            require(bytes.size in 32..128)
            file.writeBytes(AccountStore.encrypt(bytes))
            pending.delete()
        }
        return if (file.exists()) String(AccountStore.decrypt(file.readBytes())).trim() else null
    }
    fun clear() { file.delete(); pending.delete() }
}
