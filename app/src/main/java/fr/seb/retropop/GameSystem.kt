package fr.seb.retropop

import androidx.annotation.ColorRes

/**
 * Consoles prises en charge. Pour en ajouter une : déposer le cœur libretro
 * dans app/src/main/jniLibs/<abi>/ puis ajouter une entrée ici.
 */
enum class GameSystem(
    val label: String,
    val shortLabel: String,
    val coreLibrary: String,
    val extensions: Set<String>,
    @ColorRes val color: Int,
) {
    NES(
        label = "NES",
        shortLabel = "NES",
        coreLibrary = "libfceumm_libretro_android.so",
        extensions = setOf("nes", "unf", "unif"),
        color = R.color.nes,
    ),
    SNES(
        label = "Super Nintendo",
        shortLabel = "SNES",
        coreLibrary = "libsnes9x_libretro_android.so",
        extensions = setOf("sfc", "smc", "fig", "swc"),
        color = R.color.snes,
    );

    companion object {
        val allExtensions: Set<String> = entries.flatMap { it.extensions }.toSet()

        fun forExtension(ext: String): GameSystem? {
            val e = ext.lowercase()
            return entries.firstOrNull { e in it.extensions }
        }
    }
}
