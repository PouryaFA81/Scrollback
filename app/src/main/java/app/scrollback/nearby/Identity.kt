package app.scrollback.nearby

import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/** RSA-2048 key + self-signed certificate, stored as PKCS12 in the app's private files. */
class Identity private constructor(val keyStore: KeyStore, val certificate: X509Certificate) {
    val fingerprint: String = Fingerprint.of(certificate)

    companion object {
        const val ALIAS = "localsend"
        val PASSWORD = "scrollback-nearby".toCharArray() // file is app-private; the password only satisfies PKCS12
        private const val FILE = "identity.p12"

        @Synchronized
        fun loadOrCreate(dir: File): Identity {
            val f = File(dir, FILE)
            if (f.isFile) runCatching {
                val ks = KeyStore.getInstance("PKCS12").apply { f.inputStream().use { load(it, PASSWORD) } }
                (ks.getCertificate(ALIAS) as? X509Certificate)?.let { return Identity(ks, it) }
            }
            val kp = SelfSigned.newKeyPair()
            val cert = SelfSigned.create(kp)
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry(ALIAS, kp.private, PASSWORD, arrayOf(cert)) }
            dir.mkdirs()
            val tmp = File(dir, "$FILE.tmp")
            tmp.outputStream().use { ks.store(it, PASSWORD) }
            tmp.renameTo(f)
            return Identity(ks, cert)
        }
    }
}
