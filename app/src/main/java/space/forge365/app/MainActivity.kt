package space.forge365.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.jitsi.meet.sdk.BroadcastEvent
import org.jitsi.meet.sdk.BroadcastIntentHelper

/**
 * FORGE 365 : tout le site dans l'appli (connexion par cookies : le membre se connecte lui-même sur /connexion/).
 * Les salles visio du site appellent window.F365App.ouvrirVisio(...) : la réunion s'ouvre alors dans le Jitsi natif.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val ACCUEIL = "https://forge365.space/espace/"
        const val MARQUE_UA = "FORGE365App/1.0"
        private val HOTES = setOf("forge365.space", "www.forge365.space")
        fun estForge(uri: Uri?) = uri != null && uri.scheme == "https" && uri.host in HOTES
    }

    private lateinit var web: WebView
    private lateinit var horsLigne: View
    private var fichierRappel: ValueCallback<Array<Uri>>? = null
    private var permissionWeb: PermissionRequest? = null
    private var pleinEcran: View? = null

    private val choixFichier = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fichierRappel?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fichierRappel = null
    }

    /** micro / caméra demandés par une page du site (messages vocaux, etc.) */
    private val droitsWeb = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val p = permissionWeb ?: return@registerForActivityResult
        permissionWeb = null
        val ok = p.resources.filter {
            (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && res[Manifest.permission.RECORD_AUDIO] != false && aLeDroit(Manifest.permission.RECORD_AUDIO)) ||
                (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && res[Manifest.permission.CAMERA] != false && aLeDroit(Manifest.permission.CAMERA))
        }
        if (ok.isEmpty()) p.deny() else p.grant(ok.toTypedArray())
    }

    /** salle demandée par la page, ouverte seulement après la réponse aux autorisations (micro, caméra, notifications) */
    private var visioEnAttente: String? = null

    private val droitsVisio = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val json = visioEnAttente ?: return@registerForActivityResult
        visioEnAttente = null
        lancerVisio(json)
    }

    private fun lancerVisio(json: String) {
        val ok = try { Visio.ouvrir(this, json) } catch (e: Exception) { false }
        if (!ok) { Toast.makeText(this, R.string.visio_impossible, Toast.LENGTH_LONG).show(); return }
        // salle de l'hôte : alerte par-dessus la réunion quand un invité attend à la porte
        val attente = try { org.json.JSONObject(json).optString("attente") } catch (e: Exception) { "" }
        if (attente.isNotBlank()) Attente.demarrer(this, attente, web.settings.userAgentString) else Attente.arreter(this)
    }

    /** fin de la réunion native : la page de la salle est prévenue */
    private val finVisio = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            Attente.arreter(this@MainActivity)
            web.evaluateJavascript("window.dispatchEvent(new Event('f365e-app-fin'))", null)
        }
    }

    /** « Faire entrer » / « Refuser » touché dans l'alerte : la page « Ma salle visio » fait l'action */
    private val actionSalle = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val a = i?.getStringExtra("a") ?: return
            val id = i.getIntExtra("id", 0)
            if (id <= 0 || (a != "admettre" && a != "refuser")) return
            web.evaluateJavascript("window.f365eAppSalle && window.f365eAppSalle('$a', $id)", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val racine = FrameLayout(this)
        web = WebView(this)
        racine.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        horsLigne = construireHorsLigne()
        racine.addView(horsLigne, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(racine)

        // Android 15 étale l'appli sous la barre du haut (heure, batterie) et sous les boutons du bas :
        // on garde le site entre les deux, et au-dessus du clavier quand il s'ouvre.
        racine.setBackgroundColor(Color.parseColor("#0B0B0B"))
        WindowCompat.getInsetsController(window, racine).apply {
            isAppearanceLightStatusBars = false // icônes blanches sur fond noir
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(racine) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString $MARQUE_UA"
        }
        web.setBackgroundColor(Color.parseColor("#0B0B0B"))
        web.addJavascriptInterface(Pont(), "F365App")
        web.webViewClient = ClientWeb()
        web.webChromeClient = ChromeWeb()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ -> telecharger(url, userAgent, contentDisposition, mimeType) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    pleinEcran != null -> (web.webChromeClient as ChromeWeb).onHideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> moveTaskToBack(true)
                }
            }
        })

        val filtre = IntentFilter().apply {
            addAction(BroadcastEvent.Type.CONFERENCE_TERMINATED.action)
            addAction(BroadcastEvent.Type.READY_TO_CLOSE.action)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(finVisio, filtre)
        LocalBroadcastManager.getInstance(this).registerReceiver(actionSalle, IntentFilter(Attente.ACTION))

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            val lien = intent?.data
            web.loadUrl(if (estForge(lien)) lien.toString() else ACCUEIL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val lien = intent.data
        if (estForge(lien)) web.loadUrl(lien.toString())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush() // la connexion reste gardée même si l'appli est fermée
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(finVisio)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(actionSalle)
        Attente.arreter(this)
        web.destroy()
        super.onDestroy()
    }

    private fun aLeDroit(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Pont appelé par les pages du site (window.F365App) */
    inner class Pont {
        @JavascriptInterface
        fun ouvrirVisio(json: String) {
            runOnUiThread {
                // seulement depuis une page de forge365.space
                if (!estForge(Uri.parse(web.url ?: ""))) return@runOnUiThread
                val manque = mutableListOf<String>()
                if (!aLeDroit(Manifest.permission.RECORD_AUDIO)) manque += Manifest.permission.RECORD_AUDIO
                if (!aLeDroit(Manifest.permission.CAMERA)) manque += Manifest.permission.CAMERA
                if (Build.VERSION.SDK_INT >= 33 && !aLeDroit(Manifest.permission.POST_NOTIFICATIONS)) manque += Manifest.permission.POST_NOTIFICATIONS
                if (manque.isEmpty()) {
                    lancerVisio(json)
                } else {
                    // d'abord les autorisations, ensuite la salle : sinon la visio démarre sans micro ni caméra
                    visioEnAttente = json
                    droitsVisio.launch(manque.toTypedArray())
                }
            }
        }

        /** séance terminée sur le site (fin pour tout le monde) : la visio native se ferme aussi */
        @JavascriptInterface
        fun raccrocher() {
            runOnUiThread {
                if (!estForge(Uri.parse(web.url ?: ""))) return@runOnUiThread
                LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(BroadcastIntentHelper.buildHangUpIntent())
            }
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }

    private inner class ClientWeb : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val uri = req.url
            if (estForge(uri)) return false
            if (!req.isForMainFrame) return false
            ouvrirDehors(uri)
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            horsLigne.visibility = View.GONE
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (req.isForMainFrame) horsLigne.visibility = View.VISIBLE
        }
    }

    /** WhatsApp, téléphone, e-mail, autres sites : ouverts dans l'application qui convient */
    private fun ouvrirDehors(uri: Uri) {
        try {
            val i = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
            } else Intent(Intent.ACTION_VIEW, uri)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.aucune_appli, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.aucune_appli, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ChromeWeb : WebChromeClient() {
        override fun onShowFileChooser(v: WebView, rappel: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fichierRappel?.onReceiveValue(null)
            fichierRappel = rappel
            return try {
                choixFichier.launch(Intent.createChooser(params.createIntent(), getString(R.string.choisir_fichier)))
                true
            } catch (e: Exception) {
                fichierRappel = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (!estForge(request.origin)) { request.deny(); return }
            val voulus = mutableListOf<String>()
            request.resources.forEach {
                if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) voulus += Manifest.permission.RECORD_AUDIO
                if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) voulus += Manifest.permission.CAMERA
            }
            if (voulus.isEmpty()) { request.deny(); return }
            permissionWeb = request
            droitsWeb.launch(voulus.toTypedArray())
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            pleinEcran = view
            (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            rappelPleinEcran = callback
        }

        private var rappelPleinEcran: CustomViewCallback? = null

        override fun onHideCustomView() {
            pleinEcran?.let { (window.decorView as FrameLayout).removeView(it) }
            pleinEcran = null
            rappelPleinEcran?.onCustomViewHidden()
            rappelPleinEcran = null
        }
    }

    private fun telecharger(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        try {
            val nom = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(uri)
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(nom)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nom)
            if (estForge(uri)) CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, R.string.telechargement, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            ouvrirDehors(uri)
        }
    }

    private fun construireHorsLigne(): View {
        val boite = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(64, 64, 64, 64)
            visibility = View.GONE
            isClickable = true
        }
        boite.addView(TextView(this).apply {
            setText(R.string.hors_ligne_titre); setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
        })
        boite.addView(TextView(this).apply {
            setText(R.string.hors_ligne_texte); setTextColor(Color.parseColor("#C9C9C9")); textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        })
        boite.addView(Button(this).apply {
            setText(R.string.reessayer)
            setOnClickListener { horsLigne.visibility = View.GONE; if (web.url.isNullOrBlank()) web.loadUrl(ACCUEIL) else web.reload() }
        })
        return boite
    }
}
