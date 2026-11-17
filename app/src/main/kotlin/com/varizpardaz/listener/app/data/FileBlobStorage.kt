package com.varizpardaz.listener.app.data

import android.util.AtomicFile
import com.varizpardaz.listener.core.pairing.BlobStorage
import java.io.File
import java.io.IOException

/** One private file, replaced atomically so a crash mid-write leaves the old blob or the new one, never half. */
class FileBlobStorage(file: File) : BlobStorage {
    private val atomic = AtomicFile(file)

    override fun read(): ByteArray? =
        try {
            if (atomic.baseFile.exists()) atomic.readFully() else null
        } catch (_: IOException) {
            null
        }

    override fun write(bytes: ByteArray) {
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    override fun clear() = atomic.delete()
}
