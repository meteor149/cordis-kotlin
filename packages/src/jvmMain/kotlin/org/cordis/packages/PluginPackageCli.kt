package org.cordis.packages

import java.io.File

/** Small build-tool entry point; no application policies or online package manager. */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: pack <plugin.json> <payload-directory> <output.kplugin> | inspect <archive> <sha256>" }
    val archives = PluginPackageArchive()
    when (args[0]) {
        "pack" -> {
            require(args.size == 4) { "pack requires manifest, payload directory and output archive" }
            val payload = File(args[2]).canonicalFile
            val template = PluginPackageCodec.decode(File(args[1]).readBytes())
            val manifest = template.copy(files = template.files.map { record ->
                val source = File(payload, record.path)
                require(source.canonicalFile.toPath().startsWith(payload.toPath()) && source.isFile) { "Missing/unsafe package payload: ${record.path}" }
                PackageFile(record.path, source.length(), packageFileSha256(source))
            })
            println(archives.pack(manifest, payload, File(args[3])))
        }
        "inspect" -> {
            require(args.size == 3) { "inspect requires archive and trusted expected SHA-256" }
            println(PluginPackageCodec.encode(archives.inspect(File(args[1]), args[2]).manifest).decodeToString())
        }
        else -> error("Unknown package command: ${args[0]}")
    }
}
