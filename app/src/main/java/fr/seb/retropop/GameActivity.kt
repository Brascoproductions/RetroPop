package fr.seb.retropop

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GameActivity : AppCompatActivity() {

    private lateinit var library: GameLibrary
    private lateinit var game: Game
    private lateinit var retroView: GLRetroView
    private lateinit var menu: View

    private var lastSramHash = 0
    private val heldMenuKeys = mutableSetOf<Int>()
    private var remoteTipShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        hideSystemBars()

        library = GameLibrary(this)
        val file = File(intent.getStringExtra(EXTRA_PATH) ?: return finish())
        val system = GameSystem.valueOf(intent.getStringExtra(EXTRA_SYSTEM) ?: return finish())
        game = Game(file, system, GameLibrary.cleanTitle(file.nameWithoutExtension))

        val sram = library.sramFile(game).takeIf { it.exists() }?.readBytes()
        lastSramHash = sram?.contentHashCode() ?: 0

        val data = GLRetroViewData(this).apply {
            coreFilePath = File(applicationInfo.nativeLibraryDir, system.coreLibrary).absolutePath
            gameFilePath = file.absolutePath
            systemDirectory = library.systemDir.absolutePath
            savesDirectory = library.savesDir.absolutePath
            saveRAMState = sram
            shader = ShaderConfig.Sharp // pixels nets sur grand écran
            rumbleEventsEnabled = false
            preferLowLatencyAudio = true
        }

        retroView = GLRetroView(this, data).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        lifecycle.addObserver(retroView)
        findViewById<FrameLayout>(R.id.gameContainer).addView(
            retroView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        retroView.requestFocus()

        setupMenu()

        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect {
                Toast.makeText(this@GameActivity, R.string.toast_game_error, Toast.LENGTH_LONG).show()
                finish()
            }
        }

        // Sauvegarde automatique de la SRAM (les sauvegardes "en jeu") toutes les 30 s
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(30_000)
                    if (menu.visibility != View.VISIBLE) saveSram()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Si le menu était ouvert, le jeu reste figé derrière
        if (menu.visibility == View.VISIBLE) retroView.post { freezeGame(true) }
    }

    override fun onPause() {
        // La GLSurfaceView traite les tâches en file même en pause : la lecture de la SRAM passe.
        runCatching { writeSramIfChanged(retroView.serializeSRAM()) }
        super.onPause()
    }

    // ---------------------------------------------------------------- Entrées

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (menu.visibility == View.VISIBLE) {
            if (code in MENU_CLOSE_KEYS) {
                if (event.action == KeyEvent.ACTION_UP) hideMenu()
                return true
            }
            return super.dispatchKeyEvent(event) // navigation dans le menu
        }

        // Ouvrir le menu : Retour, bouton central de la manette, touche Menu
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE || code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }

        // Ou Select + Start en même temps
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT || code == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.action == KeyEvent.ACTION_DOWN) heldMenuKeys += code else heldMenuKeys -= code
            if (heldMenuKeys.size == 2) {
                heldMenuKeys.clear()
                showMenu()
                return true
            }
        }

        // Manette : GLRetroView gère elle-même (mapping, multi-joueurs par port)
        if (isGamepad(event)) return super.dispatchKeyEvent(event)

        // Télécommande / clavier
        val mapped = remoteMapping(code) ?: return super.dispatchKeyEvent(event)
        if (!remoteTipShown && event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_CENTER) {
            remoteTipShown = true
            Toast.makeText(this, R.string.toast_remote, Toast.LENGTH_LONG).show()
        }
        if (event.repeatCount == 0) retroView.sendKeyEvent(event.action, mapped, 0)
        return true
    }

    private fun isGamepad(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        val pad = device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        return pad && device.controllerNumber > 0
    }

    /**
     * Télécommande Freebox : croix = croix, OK = saut/action principale,
     * Lecture/Pause = Start. Les codes renvoyés sont ceux de la RetroPad.
     */
    private fun remoteMapping(code: Int): Int? {
        val mainAction = if (game.system == GameSystem.NES) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        val secondAction = if (game.system == GameSystem.NES) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_Y
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> code
            KeyEvent.KEYCODE_DPAD_CENTER -> mainAction
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_UP -> secondAction
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> KeyEvent.KEYCODE_BUTTON_START
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_DOWN -> KeyEvent.KEYCODE_BUTTON_SELECT
            // Clavier (tests sur PC / émulateur)
            KeyEvent.KEYCODE_X -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_W -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_S -> KeyEvent.KEYCODE_BUTTON_X
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Q -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_D -> KeyEvent.KEYCODE_BUTTON_L1
            KeyEvent.KEYCODE_C -> KeyEvent.KEYCODE_BUTTON_R1
            KeyEvent.KEYCODE_ENTER -> KeyEvent.KEYCODE_BUTTON_START
            KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_TAB -> KeyEvent.KEYCODE_BUTTON_SELECT
            else -> null
        }
    }

    // ---------------------------------------------------------------- Menu pause

    private fun setupMenu() {
        menu = findViewById(R.id.menu)
        findViewById<TextView>(R.id.menuGame).text = game.title
        findViewById<View>(R.id.menuResume).setOnClickListener { hideMenu() }
        findViewById<View>(R.id.menuSave).setOnClickListener { quickSave() }
        findViewById<View>(R.id.menuLoad).setOnClickListener { quickLoad() }
        findViewById<View>(R.id.menuReset).setOnClickListener {
            retroView.reset()
            hideMenu()
        }
        findViewById<View>(R.id.menuQuit).setOnClickListener { quit() }
    }

    private fun showMenu() {
        releaseAllInputs()
        menu.visibility = View.VISIBLE
        freezeGame(true)
        findViewById<View>(R.id.menuResume).requestFocus()
    }

    private fun hideMenu() {
        menu.visibility = View.GONE
        freezeGame(false)
        retroView.requestFocus()
    }

    /** Fige l'émulation (plus d'images = plus de calcul) et coupe le son. */
    private fun freezeGame(frozen: Boolean) {
        retroView.audioEnabled = !frozen
        if (frozen) retroView.onPause() else retroView.onResume()
    }

    /** Évite qu'une touche reste "enfoncée" dans le jeu à l'ouverture du menu. */
    private fun releaseAllInputs() {
        for (port in 0..3) {
            for (key in ALL_PAD_KEYS) retroView.sendKeyEvent(KeyEvent.ACTION_UP, key, port)
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f, port)
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, 0f, 0f, port)
        }
    }

    private fun quickSave() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching {
                    val state = retroView.serializeState()
                    library.stateFile(game).writeBytes(state)
                    writeSramIfChanged(retroView.serializeSRAM())
                }.isSuccess
            }
            if (ok) {
                Toast.makeText(this@GameActivity, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                hideMenu()
            }
        }
    }

    private fun quickLoad() {
        val file = library.stateFile(game)
        if (!file.exists()) {
            Toast.makeText(this, R.string.toast_no_state, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching { retroView.unserializeState(file.readBytes()) }.getOrDefault(false)
            }
            Toast.makeText(
                this@GameActivity,
                if (ok) R.string.toast_loaded else R.string.toast_load_failed,
                Toast.LENGTH_SHORT,
            ).show()
            if (ok) hideMenu()
        }
    }

    private fun quit() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { saveSramBlocking() }
            finish()
        }
    }

    // ---------------------------------------------------------------- SRAM

    private suspend fun saveSram() = withContext(Dispatchers.Default) { saveSramBlocking() }

    private fun saveSramBlocking() {
        runCatching { writeSramIfChanged(retroView.serializeSRAM()) }
    }

    @Synchronized
    private fun writeSramIfChanged(sram: ByteArray) {
        if (sram.isEmpty()) return
        val hash = sram.contentHashCode()
        if (hash == lastSramHash) return
        val target = library.sramFile(game)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(sram)
        tmp.renameTo(target)
        lastSramHash = hash
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SYSTEM = "system"

        private val MENU_CLOSE_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_B, // bouton "retour" usuel des manettes
        )

        private val ALL_PAD_KEYS = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        )
    }
}
