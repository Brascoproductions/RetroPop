package fr.seb.retropop

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var library: GameLibrary
    private lateinit var grid: RecyclerView
    private lateinit var empty: View
    private lateinit var count: TextView
    private lateinit var uploadUrl: TextView
    private lateinit var uploadHint: TextView
    private val adapter = GameAdapter { openGame(it) }
    private var server: UploadServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        library = GameLibrary(this)

        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        count = findViewById(R.id.count)
        uploadUrl = findViewById(R.id.uploadUrl)
        uploadHint = findViewById(R.id.uploadHint)

        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.adapter = adapter
        grid.itemAnimator = null
    }

    override fun onStart() {
        super.onStart()
        server = UploadServer.startOnFreePort(this, library) {
            runOnUiThread { refresh() }
        }
        showUploadAddress()
        refresh()
    }

    override fun onStop() {
        server?.stop()
        server = null
        super.onStop()
    }

    private fun showUploadAddress() {
        val ip = UploadServer.localIpAddress()
        val port = server?.listeningPort
        if (ip != null && port != null) {
            uploadHint.setText(R.string.upload_hint)
            uploadUrl.text = if (port == 80) "http://$ip" else "http://$ip:$port"
            uploadUrl.visibility = View.VISIBLE
        } else {
            uploadHint.setText(R.string.upload_unavailable)
            uploadUrl.visibility = View.GONE
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val games = withContext(Dispatchers.IO) { library.scan() }
            val hadFocus = grid.focusedChild?.let { grid.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
            adapter.submit(games)

            empty.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
            grid.visibility = if (games.isEmpty()) View.GONE else View.VISIBLE
            count.text = when (games.size) {
                0 -> ""
                1 -> getString(R.string.games_count_one)
                else -> getString(R.string.games_count, games.size)
            }

            if (games.isNotEmpty()) {
                val target = hadFocus.coerceIn(0, games.lastIndex)
                grid.post { grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
            }
        }
    }

    private fun openGame(game: Game) {
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_PATH, game.file.absolutePath)
                .putExtra(GameActivity.EXTRA_SYSTEM, game.system.name)
        )
    }

    private class GameAdapter(private val onClick: (Game) -> Unit) : RecyclerView.Adapter<GameAdapter.Holder>() {
        private var games: List<Game> = emptyList()

        fun submit(list: List<Game>) {
            games = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = games.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(games[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.name)
            private val tag: TextView = view.findViewById(R.id.tag)
            private val initial: TextView = view.findViewById(R.id.initial)

            init {
                view.setOnFocusChangeListener { v, focused ->
                    val s = if (focused) 1.08f else 1f
                    v.animate().scaleX(s).scaleY(s).translationZ(if (focused) 8f else 0f).setDuration(120).start()
                }
            }

            fun bind(game: Game) {
                val color = ContextCompat.getColor(itemView.context, game.system.color)
                name.text = game.title
                tag.text = game.system.shortLabel
                tag.backgroundTintList = ColorStateList.valueOf(color)
                initial.text = game.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
                initial.setTextColor(color and 0x00FFFFFF or 0x40000000)
                itemView.setOnClickListener { onClick(game) }
            }
        }
    }

    companion object {
        private const val COLUMNS = 5
    }
}
