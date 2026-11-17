package com.varizpardaz.listener.app.data

import android.util.AtomicFile
import com.varizpardaz.listener.core.Base64Url
import com.varizpardaz.listener.core.configstore.ConfigPersistence
import com.varizpardaz.listener.core.configstore.StoredConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/**
 * The signed config as the server sent it, kept with its signature. It is not secret (it holds bank sender ids and
 * message patterns), so it is not encrypted; it is verified against the pinned key every time it is read, so
 * editing the file achieves nothing.
 */
class ConfigFilePersistence(file: File) : ConfigPersistence {
    private val atomic = AtomicFile(file)

    override fun read(): StoredConfig? =
        try {
            if (!atomic.baseFile.exists()) {
                null
            } else {
                val obj = Json.parseToJsonElement(atomic.readFully().toString(Charsets.UTF_8)).jsonObject
                val body = Base64Url.decodeOrNull(obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val signature = obj["signature"]?.jsonPrimitive?.contentOrNull
                if (body == null || signature == null) null else StoredConfig(body, signature)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null // unparsable JSON, or a field of the wrong type
        }

    override fun write(config: StoredConfig) {
        val text = JsonObject(
            mapOf(
                "signature" to JsonPrimitive(config.signature),
                "body" to JsonPrimitive(Base64Url.encode(config.body)),
            ),
        ).toString()
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    override fun clear() = atomic.delete()
}
