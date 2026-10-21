package fr.seb.retropop

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class Game(
    val file: File,
    val system: GameSystem,
    val title: String,
)

/**
 * Bibliothèque de jeux, entièrement dans le stockage de l'app :
 *  - files/roms : jeux reçus par le navigateur (serveur web intégré)
 *  - Android/data/fr.seb.retropop/files/roms : dépôt possible via adb
 * Aucune permission de stockage n'est nécessaire.
 */
class GameLibrary(private val context: Context) {

    val internalDir: File = File(context.filesDir, "roms").apply { mkdirs() }
    private val externalDir: File? = context.getExternalFilesDir("roms")?.apply { mkdirs() }

    val savesDir: File = File(context.filesDir, "saves").apply { mkdirs() }
    val statesDir: File = File(context.filesDir, "states").apply { mkdirs() }
    val systemDir: File = File(context.filesDir, "system").apply { mkdirs() }

    fun scan(): List<Game> =
        listOfNotNull(internalDir, externalDir)
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { it.isFile }
            .mapNotNull { file ->
                GameSystem.forExtension(file.extension)?.let { Game(file, it, cleanTitle(file.nameWithoutExtension)) }
            }
            .distinctBy { it.file.name.lowercase() }
            .sortedWith(compareBy({ it.system.ordinal }, { it.title.lowercase() }))

    /**
     * Enregistre un fichier reçu. Les .zip sont décompressés : on garde le
     * premier fichier de jeu reconnu à l'intérieur.
     * @return le nom du fichier enregistré, ou null si le format est inconnu.
     */
    fun import(fileName: String, input: InputStream): String? {
        val name = sanitize(fileName)
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "zip" -> importZip(input)
            GameSystem.forExtension(ext) != null -> {
                writeAtomically(File(internalDir, name), input)
                name
            }
            else -> null
        }
    }

    private fun importZip(input: InputStream): String? {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.isDirectory) continue
                val inner = sanitize(entry.name.substringAfterLast('/'))
                if (GameSystem.forExtension(inner.substringAfterLast('.', "")) != null) {
                    writeAtomically(File(internalDir, inner), zip)
                    return inner
                }
            }
        }
    }

    fun delete(fileName: String): Boolean {
        val name = sanitize(fileName)
        val target = File(internalDir, name)
        val deleted = target.exists() && target.delete()
        File(savesDir, target.nameWithoutExtension + ".srm").delete()
        File(statesDir, target.nameWithoutExtension + ".state").delete()
        return deleted
    }

    fun sramFile(game: Game) = File(savesDir, game.file.nameWithoutExtension + ".srm")
    fun stateFile(game: Game) = File(statesDir, game.file.nameWithoutExtension + ".state")

    private fun writeAtomically(target: File, input: InputStream) {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    companion object {
        /** Empêche toute écriture hors du dossier (../, sous-dossiers, caractères spéciaux). */
        fun sanitize(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N} ._()\\[\\]!&,'+-]"), "_")
                .trimStart('.')
                .take(150)
                .ifBlank { "jeu" }

        /** "Mon Jeu (France) [!] (Rev 1)" -> "Mon Jeu" */
        fun cleanTitle(raw: String): String =
            raw.replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "")
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { raw }
    }
}
