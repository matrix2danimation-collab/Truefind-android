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
@SuppressLint("ViewConstructor") class ClueMePage(ctx: Context, layer: GamesLayer) : StubScreen(ctx, layer, "ClueMe")
@SuppressLint("ViewConstructor") class NameGuessLocal(ctx: Context, layer: GamesLayer) : StubScreen(ctx, layer, "ClueMe · one phone")
@SuppressLint("ViewConstructor") class NameGuessOnline(ctx: Context, layer: GamesLayer) : StubScreen(ctx, layer, "ClueMe · two phones")
@SuppressLint("ViewConstructor") class DressCodeScreen(ctx: Context, layer: GamesLayer) : StubScreen(ctx, layer, "Dress Code")

object NameGuess {
    const val ROUND_SECONDS = 60
    val RULES = listOf(
        "Ek player ko gupt shabd dikhega (jaise पंखा · Fan). Wo bina shabd bole hint dega.",
        "Doosra player hint sun kar naam bolega ya type karega.",
        "Hint me shabd, uska hissa, spelling ya doosri bhasha me wahi shabd bolna mana hai.",
        "Har round 60 second. Hint dene wala 2 baar shabd badal (skip) sakta hai, koi saza nahi.",
        "Sahi jawab: bujhne wale ko +10, hint dene wale ko +5. Time khatam: koi point nahi.",
        "6 round, baari-baari se dono hint dete hain. Jiske zyada points, wo jeeta.",
    )
}

object DressData { val designsLabel = "100+ designs" }

object GamesDemoMore { fun answer(fn: String, body: JSONObject): app.truefind.android.Api.Result? = null }
