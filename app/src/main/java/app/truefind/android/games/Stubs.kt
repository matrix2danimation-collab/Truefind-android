package app.truefind.android.games

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import org.json.JSONObject

// TEMPORARY (work branch only): placeholders while the remaining games are ported.

@SuppressLint("ViewConstructor")
open class StubScreen(ctx: Context, layer: GamesLayer, title: String) : GameScreen(ctx, layer) {
    init {
        setBackgroundColor(G.c("#1a0d33"))
        addView(G.text(ctx, title, 20f, Color.WHITE), FrameLayout.LayoutParams(G.WRAP, G.WRAP, Gravity.CENTER))
    }
}

@SuppressLint("ViewConstructor") class MoodChangerScreen(ctx: Context, layer: GamesLayer) : StubScreen(ctx, layer, "Mood Changer")
@SuppressLint("ViewConstructor") class DressCodeScreen(ctx: Context, layer: GamesLayer) : StubScreen(ctx, layer, "Dress Code")

object DressData { val designsLabel = "100+ designs" }

object GamesDemoMore { fun answer(fn: String, body: JSONObject): app.truefind.android.Api.Result? = null }
