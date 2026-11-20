package space.forge365.app

import android.content.Context
import android.os.Bundle
import org.jitsi.meet.sdk.JitsiMeetActivity
import org.jitsi.meet.sdk.JitsiMeetConferenceOptions
import org.jitsi.meet.sdk.JitsiMeetUserInfo
import org.json.JSONObject
import java.net.URL

/**
 * Ouvre une salle FORGE 365 dans le Jitsi natif, avec ce que la page du site a transmis
 * (salle, jeton signé par le site, nom, options). Rien n'est gardé dans l'appli.
 */
object Visio {

    /** Seul le serveur visio de FORGE 365 est accepté. */
    private fun domaineAutorise(d: String) = d == "visio.forge365.space" || d.endsWith(".forge365.space")

    fun ouvrir(context: Context, json: String): Boolean {
        val d = JSONObject(json)
        val domaine = d.optString("domaine")
        val salle = d.optString("salle")
        val jeton = d.optString("jeton")
        if (!domaineAutorise(domaine) || salle.isBlank() || jeton.isBlank()) return false

        val spectateur = d.optBoolean("spectateur")
        val animateur = d.optBoolean("animateur")
        val moi = JitsiMeetUserInfo().apply { displayName = d.optString("nom") }

        val options = JitsiMeetConferenceOptions.Builder()
            .setServerURL(URL("https://$domaine"))
            .setRoom(salle)
            .setToken(jeton)
            .setSubject(d.optString("titre"))
            .setUserInfo(moi)
            .setAudioMuted(d.optBoolean("microCoupe"))
            .setVideoMuted(true) // caméra coupée à l'entrée, comme sur le site
            // partage de l'écran du téléphone (pas pour les spectateurs d'un webinaire)
            .setFeatureFlag("android.screensharing.enabled", !spectateur)
            .setFeatureFlag("pip.enabled", true)
            .setFeatureFlag("welcomepage.enabled", false)
            .setFeatureFlag("prejoinpage.enabled", false)
            .setFeatureFlag("invite.enabled", false)
            .setFeatureFlag("add-people.enabled", false)
            .setFeatureFlag("calendar.enabled", false)
            .setFeatureFlag("call-integration.enabled", false)
            .setFeatureFlag("live-streaming.enabled", false)
            .setFeatureFlag("recording.enabled", false)
            .setFeatureFlag("server-url-change.enabled", false)
            .setFeatureFlag("settings.enabled", false)
            .setFeatureFlag("unsaferoomwarning.enabled", false)
            .setFeatureFlag("meeting-password.enabled", animateur)
            .setFeatureFlag("lobby-mode.enabled", animateur)
            .setFeatureFlag("kick-out.enabled", animateur)
            .setFeatureFlag("security-options.enabled", animateur)
            .setConfigOverride("startWithVideoMuted", true)
            .setConfigOverride("readOnlyName", true)
            .setConfigOverride("disableInviteFunctions", true)
            .setConfigOverride("disableDeepLinking", true)
            // téléphone allégé (surtout pendant un partage de tout l'écran, qui occupe déjà beaucoup le téléphone) :
            // caméra envoyée en 360p, au plus 6 vidéos reçues (les personnes qui parlent), pas de calcul du niveau sonore
            .setConfigOverride("resolution", 360)
            .setConfigOverride("channelLastN", 6)
            .setConfigOverride("disableAudioLevels", true)
            .setConfigOverride("enableLayerSuspension", true)
            // pas de liaison directe téléphone ↔ ordinateur (P2P) : le serveur visio n'a pas de relais TURN, et sur les
            // réseaux mobiles la liaison directe échoue souvent ou passe mal (attente, image qui traîne, surtout en partage
            // d'écran). Tout passe par le serveur visio, comme à 3 personnes et plus.
            .setConfigOverride("p2p", Bundle().apply { putBoolean("enabled", false) })
            .build()

        JitsiMeetActivity.launch(context, options)
        return true
    }
}
