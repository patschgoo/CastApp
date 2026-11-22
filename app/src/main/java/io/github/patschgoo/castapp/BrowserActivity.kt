package io.github.patschgoo.castapp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.browser.FoundVideo
import io.github.patschgoo.castapp.browser.VideoDetector
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * A small browser that spots the videos a web page plays and sends them to the
 * TV. The TV then plays the video by itself: you can leave this screen, use other
 * apps or lock the phone, like with Netflix's or YouTube's own Cast button.
 */
class BrowserActivity : ComponentActivity() {
    private val detector = VideoDetector()
    private var webView: WebView? = null
    private val pageUrlState = mutableStateOf("")
    private val loadProgress = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = CastApp.get(this)
        val start = intent.getStringExtra(EXTRA_URL) ?: HOME
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            CastAppTheme(settings.theme) {
                BrowserScreen(start)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BrowserScreen(startUrl: String) {
        val app = CastApp.get(this)
        val videos by detector.videos.collectAsStateWithLifecycle()
        val devices by app.devices.devices.collectAsStateWithLifecycle()
        val scanning by app.devices.scanning.collectAsStateWithLifecycle()
        var typed by remember { mutableStateOf(startUrl) }
        var editing by remember { mutableStateOf(false) }
        var showVideos by remember { mutableStateOf(false) }
        var chosen by remember { mutableStateOf<FoundVideo?>(null) }
        val currentUrl by pageUrlState
        val loading by loadProgress

        BackHandler {
            val w = webView
            if (w != null && w.canGoBack()) w.goBack() else finish()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = if (editing) typed else currentUrl.ifEmpty { typed },
                            onValueChange = { typed = it; editing = true },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            placeholder = { Text(stringResource(R.string.browser_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                editing = false
                                webView?.loadUrl(toUrl(typed))
                            }),
                            modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                        )
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    },
                )
            },
            floatingActionButton = {
                if (videos.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { if (videos.size == 1) chosen = videos[0] else showVideos = true },
                        icon = { Icon(Icons.Rounded.Cast, contentDescription = null) },
                        text = {
                            Text(
                                if (videos.size == 1) stringResource(R.string.browser_cast_video)
                                else stringResource(R.string.browser_cast_videos, videos.size),
                            )
                        },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                if (loading in 1..99) LinearProgressIndicator(progress = { loading / 100f }, modifier = Modifier.fillMaxWidth())
                Box(Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx -> createWebView(ctx).also { webView = it; it.loadUrl(toUrl(startUrl)) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (showVideos) {
            ModalBottomSheet(onDismissRequest = { showVideos = false }) {
                Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.browser_found),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    for (v in videos) {
                        ListItem(
                            onClick = { showVideos = false; chosen = v },
                            modifier = Modifier.padding(horizontal = 8.dp),
                            supportingContent = {
                                Text("${v.kind.label} · ${v.host}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        ) {
                            Text(v.url.substringBefore('?').substringAfterLast('/').ifEmpty { v.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        chosen?.let { video ->
            PickTvSheet(
                title = video.pageTitle.ifEmpty { video.url },
                devices = if (video.kind == FoundVideo.Kind.AUDIO) devices else devices.filter { it.device.video },
                scanning = scanning,
                onPick = { device -> chosen = null; castVideo(video, device) },
                onScan = { app.devices.scanNetwork() },
                onDismiss = { chosen = null },
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: android.content.Context): WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Only watch the traffic; the page loads normally.
                detector.onUrl(request.url.toString())
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https" // ignore app links, intent:// and the like
            }

            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                detector.newPage(pageUrl)
                pageUrlState.value = pageUrl
            }

            override fun onPageFinished(view: WebView, pageUrl: String) {
                pageUrlState.value = pageUrl
                scan(view)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadProgress.intValue = newProgress
                if (newProgress == 100) scan(view)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                title?.let { detector.updateTitle(it) }
            }
        }
        // Players often start loading only after a tap, so look again now and then.
        val rescan = object : Runnable {
            override fun run() {
                if (webView !== this@apply) return // closed
                scan(this@apply)
                postDelayed(this, 3000)
            }
        }
        postDelayed(rescan, 3000)
    }

    private fun scan(view: WebView) {
        view.evaluateJavascript(VideoDetector.SCAN_SCRIPT) { result ->
            try {
                val json = JSONObject(JSONObject("{\"v\":$result}").getString("v"))
                detector.updateTitle(json.optString("title"))
                val items = json.optJSONArray("items") ?: return@evaluateJavascript
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    detector.onUrl(o.optString("src"), o.optString("type").ifEmpty { null })
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun castVideo(video: FoundVideo, device: CastDevice) {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = WebSettings.getDefaultUserAgent(this)
        if (video.pageUrl.isNotEmpty()) {
            headers["Referer"] = video.pageUrl
            try {
                val page = URI(video.pageUrl)
                headers["Origin"] = "${page.scheme}://${page.host}" + if (page.port > 0) ":${page.port}" else ""
            } catch (_: Exception) {
            }
        }
        CookieManager.getInstance().getCookie(video.url)?.let { headers["Cookie"] = it }
        val title = video.pageTitle.ifEmpty { video.url.substringBefore('?').substringAfterLast('/') }
        CastService.startMedia(this, device, listOf(MediaItem(title = title, uri = video.url, mimeType = video.mimeType, headers = headers)))
        // Don't play it twice: pause the page's own player.
        webView?.evaluateJavascript(VideoDetector.PAUSE_SCRIPT, null)
        Toast.makeText(this, getString(R.string.browser_casting, device.name), Toast.LENGTH_LONG).show()
    }

    private fun toUrl(text: String): String {
        val t = text.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
        }
    }

    override fun onStart() {
        super.onStart()
        CastApp.get(this).devices.startDiscovery()
    }

    override fun onStop() {
        CastApp.get(this).devices.stopDiscovery()
        super.onStop()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val HOME = "https://duckduckgo.com/"
    }
}
