package app.scrollback.remote.fs

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/SMB3 via smbj (SMB1 is never offered). Paths are '/'-separated and relative to [shareName].
 * An empty user signs in as guest/anonymous.
 */
class SmbFs(
    host: String,
    port: Int,
    private val shareName: String,
    user: String,
    password: String,
    domain: String?,
) : RemoteFs {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withEncryptData(false)
            .build()
    )
    private val connection: Connection
    private val session: Session
    private val share: DiskShare

    init {
        try {
            connection = client.connect(host, port)
            val ctx = if (user.isBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(user, password.toCharArray(), domain?.ifBlank { null })
            session = connection.authenticate(ctx)
            share = session.connectShare(shareName) as? DiskShare
                ?: throw java.io.IOException("“$shareName” is not a disk share")
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }

    private fun smb(p: String) = RemotePaths.normalize(p).trimStart('/').replace('/', '\\')

    override val isConnected: Boolean get() = connection.isConnected && share.isConnected

    override fun list(path: String): List<RemoteEntry> = share.list(smb(path))
        .filter { it.fileName != "." && it.fileName != ".." }
        .map { f ->
            RemoteEntry(
                path = RemotePaths.join(path, f.fileName),
                name = f.fileName,
                isDir = f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                size = f.endOfFile,
                modified = f.lastWriteTime.toEpochMillis(),
            )
        }

    override fun stat(path: String): RemoteEntry? {
        val p = smb(path)
        if (p.isEmpty()) return RemoteEntry("/", shareName, true, 0, 0)
        if (!share.fileExists(p) && !share.folderExists(p)) return null
        val info = share.getFileInformation(p)
        val isDir = info.basicInformation.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
        return RemoteEntry(RemotePaths.normalize(path), RemotePaths.name(path), isDir, info.standardInformation.endOfFile,
            info.basicInformation.lastWriteTime.toEpochMillis())
    }

    private fun open(p: String, access: AccessMask, disposition: SMB2CreateDisposition): File = share.openFile(
        smb(p), EnumSet.of(access), null, SMB2ShareAccess.ALL, disposition, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )

    /** Reads must not exceed the server's negotiated MaxReadSize (often 64 KiB on small NAS/Samba setups). */
    private val maxRead: Int by lazy { connection.negotiatedProtocol.maxReadSize.coerceIn(16 * 1024, 1 shl 20) }

    override fun openRead(path: String, offset: Long): InputStream {
        val file = open(path, AccessMask.GENERIC_READ, SMB2CreateDisposition.FILE_OPEN)
        return object : InputStream() {
            private var pos = offset
            private val one = ByteArray(1)
            override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = file.read(b, pos, off, minOf(len, maxRead))
                if (n <= 0) return -1
                pos += n
                return n
            }
            override fun skip(n: Long): Long { pos += n; return n }
            override fun close() = file.close()
        }
    }

    override fun openWrite(path: String): OutputStream {
        val file = open(path, AccessMask.GENERIC_WRITE, SMB2CreateDisposition.FILE_OVERWRITE_IF)
        return object : FilterOutputStream(file.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() { runCatching { super.close() }; file.close() }
        }
    }

    override fun mkdir(path: String) = share.mkdir(smb(path))

    override fun rename(from: String, to: String) {
        val isDir = share.folderExists(smb(from))
        val entry = share.open(
            smb(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(if (isDir) SMB2CreateOptions.FILE_DIRECTORY_FILE else SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        entry.use { it.rename(smb(to)) }
    }

    override fun delete(path: String, isDir: Boolean) {
        if (isDir) share.rmdir(smb(path), true) else share.rm(smb(path))
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
