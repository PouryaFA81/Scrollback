package app.scrollback.remote.fs

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

/** Password or private-key (OpenSSH/PEM/PuTTY) authentication. Never logged. */
sealed interface SftpAuth {
    data class Password(val password: String) : SftpAuth
    data class Key(val privateKey: String, val passphrase: String?) : SftpAuth
}

/**
 * SFTP via sshj. The host key is verified against [pinnedFingerprint] (OpenSSH-style
 * `SHA256:<base64>`); with none pinned the first connection fails with
 * [UnknownHostKeyException] so the UI can ask the user to trust it.
 */
class SftpFs(
    host: String,
    port: Int,
    user: String,
    auth: SftpAuth,
    pinnedFingerprint: String?,
) : RemoteFs {
    private val ssh = SSHClient(DefaultConfig())
    private val sftp: SFTPClient

    init {
        var seen: String? = null
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
                val fp = fingerprint(key)
                seen = fp
                return pinnedFingerprint != null && fp == pinnedFingerprint
            }
            override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> = mutableListOf()
        })
        ssh.connectTimeout = 15_000
        ssh.timeout = 60_000
        try {
            ssh.connect(host, port)
        } catch (e: Exception) {
            runCatching { ssh.close() }
            val fp = seen
            when {
                fp != null && pinnedFingerprint == null -> throw UnknownHostKeyException(fp)
                fp != null && fp != pinnedFingerprint -> throw HostKeyMismatchException(pinnedFingerprint!!, fp)
                else -> throw e
            }
        }
        try {
            ssh.connection.keepAlive.keepAliveInterval = 30
            when (auth) {
                is SftpAuth.Password -> ssh.authPassword(user, auth.password)
                is SftpAuth.Key -> {
                    val finder = auth.passphrase?.takeIf { it.isNotEmpty() }?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    ssh.authPublickey(user, ssh.loadKeys(auth.privateKey, null, finder))
                }
            }
            sftp = ssh.newSFTPClient()
        } catch (e: Exception) {
            runCatching { ssh.close() }
            throw e
        }
    }

    override val isConnected: Boolean get() = ssh.isConnected && ssh.isAuthenticated

    override fun list(path: String): List<RemoteEntry> = sftp.ls(RemotePaths.normalize(path)).map { r ->
        RemoteEntry(
            path = RemotePaths.join(path, r.name),
            name = r.name,
            isDir = r.isDirectory,
            size = r.attributes.size,
            modified = r.attributes.mtime * 1000,
        )
    }

    override fun stat(path: String): RemoteEntry? {
        val p = RemotePaths.normalize(path)
        val a = sftp.statExistence(p) ?: return null
        return RemoteEntry(p, RemotePaths.name(p), a.type == FileMode.Type.DIRECTORY, a.size, a.mtime * 1000)
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val file = sftp.open(RemotePaths.normalize(path), EnumSet.of(OpenMode.READ))
        val stream = file.ReadAheadRemoteFileInputStream(16, offset)
        return object : FilterInputStream(stream) {
            override fun close() { runCatching { super.close() }; file.close() }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val file: RemoteFile = sftp.open(RemotePaths.normalize(path), EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
        val stream = file.RemoteFileOutputStream(0, 16)
        return object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() { runCatching { super.close() }; file.close() }
        }
    }

    override fun mkdir(path: String) = sftp.mkdir(RemotePaths.normalize(path))

    override fun rename(from: String, to: String) = sftp.rename(RemotePaths.normalize(from), RemotePaths.normalize(to))

    override fun delete(path: String, isDir: Boolean) {
        val p = RemotePaths.normalize(path)
        if (!isDir) { sftp.rm(p); return }
        for (c in sftp.ls(p)) delete(RemotePaths.join(p, c.name), c.isDirectory)
        sftp.rmdir(p)
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }

    companion object {
        /** OpenSSH-style fingerprint: `SHA256:` + unpadded base64 of the SSH wire-format key. */
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
