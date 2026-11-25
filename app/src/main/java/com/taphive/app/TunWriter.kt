package com.taphive.app

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor

/** Writes complete IP packets back into the VPN tun interface. */
class TunWriter(private val fd: FileDescriptor) {
    private val lock = Any()

    fun write(packet: ByteArray) {
        synchronized(lock) {
            var offset = 0
            while (offset < packet.size) {
                val written = try {
                    Os.write(fd, packet, offset, packet.size - offset)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN) {
                        waitWritable()
                        continue
                    }
                    // The tun can be closed during teardown; best-effort drop.
                    return
                }
                if (written <= 0) {
                    waitWritable()
                } else {
                    offset += written
                }
            }
        }
    }

    private fun waitWritable() {
        val descriptor = StructPollfd().apply {
            fd = this@TunWriter.fd
            events = OsConstants.POLLOUT.toShort()
        }
        try {
            Os.poll(arrayOf(descriptor), POLL_TIMEOUT_MS)
        } catch (_: ErrnoException) {
        }
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 500
    }
}
