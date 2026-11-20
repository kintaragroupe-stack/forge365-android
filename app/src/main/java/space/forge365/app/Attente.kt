package space.forge365.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Salle d'attente de l'hôte pendant une réunion native : toutes les 4 s, l'appli lit la liste des invités qui attendent
 * (même adresse que la page « Ma salle visio », avec la connexion du membre) et affiche une alerte par-dessus la réunion,
 * comme Zoom, avec « Faire entrer » et « Refuser ». Le bouton touché est transmis à la page du site, qui fait l'action.
 */
object Attente {
    const val CANAL = "salle_attente"
    const val ACTION = "space.forge365.app.SALLE_ACTION" // vers MainActivity (LocalBroadcast)
    private const val BASE_ID = 7000

    @Volatile private var fil: Thread? = null
    private val vus = mutableSetOf<Int>()

    fun demarrer(context: Context, adresse: String, userAgent: String) {
        if (!MainActivity.estForge(Uri.parse(adresse))) return
        arreter(context)
        creerCanal(context)
        val app = context.applicationContext
        val t = Thread {
            while (fil === Thread.currentThread()) {
                try { verifier(app, adresse, userAgent) } catch (_: Exception) { }
                try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
            }
        }
        fil = t
        t.isDaemon = true
        t.start()
    }

    fun arreter(context: Context) {
        fil?.interrupt()
        fil = null
        val nm = NotificationManagerCompat.from(context)
        synchronized(vus) { vus.forEach { nm.cancel(BASE_ID + it) }; vus.clear() }
    }

    fun retirer(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(BASE_ID + id)
    }

    private fun verifier(context: Context, adresse: String, userAgent: String) {
        val c = URL(adresse).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.useCaches = false
        c.setRequestProperty("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(adresse)?.let { c.setRequestProperty("Cookie", it) }
        val texte = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        val gens = JSONObject(texte).optJSONArray("gens") ?: return
        val presents = mutableSetOf<Int>()
        for (i in 0 until gens.length()) {
            val g = gens.getJSONObject(i)
            val id = g.optInt("id")
            if (id <= 0) continue
            presents += id
            val nouveau = synchronized(vus) { vus.add(id) }
            if (nouveau) alerter(context, id, g.optString("nom"))
        }
        // entré, refusé ou parti : l'alerte disparaît
        synchronized(vus) {
            val partis = vus.filter { it !in presents }
            partis.forEach { retirer(context, it); vus.remove(it) }
        }
    }

    private fun alerter(context: Context, id: Int, nom: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        fun bouton(action: String, code: Int): PendingIntent {
            val i = Intent(context, ActionSalle::class.java).putExtra("a", action).putExtra("id", id)
            return PendingIntent.getBroadcast(context, BASE_ID * 10 + id * 2 + code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_porte)
            .setContentTitle(context.getString(R.string.attente_titre, nom))
            .setContentText(context.getString(R.string.attente_texte))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.attente_entrer), bouton("admettre", 0))
            .addAction(0, context.getString(R.string.attente_refuser), bouton("refuser", 1))
            .build()
        try { NotificationManagerCompat.from(context).notify(BASE_ID + id, n) } catch (_: SecurityException) { }
    }

    private fun creerCanal(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, context.getString(R.string.attente_canal), NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

/** Bouton touché dans l'alerte : transmis à la page « Ma salle visio » sans quitter la réunion. */
class ActionSalle : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val a = intent.getStringExtra("a") ?: return
        if (id <= 0 || (a != "admettre" && a != "refuser")) return
        Attente.retirer(context, id)
        LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(Attente.ACTION).putExtra("a", a).putExtra("id", id))
    }
}
