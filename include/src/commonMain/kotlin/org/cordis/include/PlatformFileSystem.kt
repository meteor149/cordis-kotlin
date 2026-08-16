package org.cordis.include

/** Host file-system policy for configuration files. */
internal expect object PlatformFileSystem {
    fun resolve(path: String, baseUrl: String?): String
    fun parent(path: String): String
    fun toFileUrl(path: String): String
    fun extension(path: String): String
    fun exists(path: String): Boolean
    fun isWritable(path: String): Boolean
    fun readUtf8(path: String): String
    fun writeUtf8Atomic(path: String, content: String)
}
