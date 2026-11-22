package io.github.patschgoo.castapp.network

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.patschgoo.castapp.stream.MediaSource
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Where a Windows / Samba share lives and how to log in. */
data class SmbTarget(
    val host: String,
    val share: String,
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    val port: Int = 445,
)

/**
 * Windows and Samba network shares (most NAS boxes, a PC's shared folder, a
 * router's USB disk) through the smbj library: list folders, read files from any
 * position so the TV can seek.
 */
class SmbConnection(private val target: SmbTarget) : AutoCloseable {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            // Android's crypto lacks MD4 (needed for NTLM log-in); BouncyCastle has it.
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withSigningRequired(false)
            .build(),
    )
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null

    @Synchronized
    fun share(): DiskShare {
        disk?.takeIf { it.isConnected }?.let { return it }
        close()
        try {
            val c = client.connect(target.host, target.port)
            connection = c
            val auth = if (target.user.isEmpty()) AuthenticationContext.guest()
            else AuthenticationContext(target.user, target.password.toCharArray(), target.domain.ifEmpty { null })
            val s = c.authenticate(auth)
            session = s
            val d = s.connectShare(target.share) as? DiskShare ?: throw IOException(Txt.s(R.string.err_not_folder_share, target.share))
            disk = d
            return d
        } catch (e: IOException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw IOException(friendly(e), e)
        }
    }

    /** Lists [path] ("" = the share's top folder). */
    fun list(path: String): List<RemoteEntry> {
        val d = share()
        return d.list(smbPath(path))
            .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
            .map {
                val isDir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                RemoteEntry(
                    name = it.fileName,
                    path = if (path.isEmpty()) it.fileName else "$path/${it.fileName}",
                    isFolder = isDir,
                    size = if (isDir) null else it.endOfFile,
                )
            }
            .sortedWith(NaturalOrder.entries)
    }

    fun size(path: String): Long = share().getFileInformation(smbPath(path)).standardInformation.endOfFile

    /** Opens [path] for reading from [offset]. */
    fun open(path: String, offset: Long): InputStream {
        val file = share().openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        return object : InputStream() {
            private var position = offset
            private val one = ByteArray(1)

            override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = file.read(b, position, off, minOf(len, MAX_READ))
                if (n <= 0) return -1
                position += n
                return n
            }

            override fun close() {
                try { file.close() } catch (_: Exception) {}
            }
        }
    }

    fun mediaSource(path: String): MediaSource = object : MediaSource {
        override val length: Long by lazy { try { size(path) } catch (_: Exception) { -1L } }
        override fun open(offset: Long): InputStream = this@SmbConnection.open(path, offset)
    }

    @Synchronized
    override fun close() {
        try { disk?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        disk = null
        session = null
        connection = null
    }

    companion object {
        private const val MAX_READ = 1024 * 1024

        fun smbPath(path: String): String = path.trim('/').replace('/', '\\')

        private fun friendly(e: Exception): String {
            val text = e.message ?: e.javaClass.simpleName
            return when {
                text.contains("STATUS_LOGON_FAILURE") -> Txt.s(R.string.err_wrong_login)
                text.contains("STATUS_BAD_NETWORK_NAME") -> Txt.s(R.string.err_no_share)
                text.contains("STATUS_ACCESS_DENIED") -> Txt.s(R.string.err_access_denied)
                else -> text
            }
        }
    }
}
