package com.lite.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var bar: ProgressBar
    private val prefs by lazy { getSharedPreferences("p", 0) }
    private val extDir by lazy { File(filesDir, "ext").apply { mkdirs() } }
    private var desktop = false
    private val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        urlBox = EditText(this).apply {
            setSingleLine(); hint = "Search or type URL"; imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
            setOnKeyListener { _, k, e -> if (k == KeyEvent.KEYCODE_ENTER && e.action == KeyEvent.ACTION_UP) { go(text.toString()); true } else false }
        }
        val menu = Button(this).apply { text = "⋮"; setOnClickListener { showMenu(it) } }
        top.addView(urlBox, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(menu, LinearLayout.LayoutParams(dp(52), -2))
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE }
        web = WebView(this)
        root.addView(top); root.addView(bar, LinearLayout.LayoutParams(-1, dp(3))); root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; displayZoomControls = false; builtInZoomControls = true; setSupportZoom(true) }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { bar.progress = p; bar.visibility = if (p < 100) android.view.View.VISIBLE else android.view.View.GONE }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) { urlBox.setText(u) }
            override fun onPageFinished(v: WebView, u: String) { injectExtensions(v, u) }
        }
        web.setDownloadListener { u, _, _, _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        go(intent?.dataString ?: "https://duckduckgo.com")
    }

    private fun go(q: String) {
        val s = q.trim()
        val u = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(" ") && s.contains(".") -> "https://$s"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(s)
        }
        web.loadUrl(u)
    }

    private fun showMenu(anchor: android.view.View) {
        val m = PopupMenu(this, anchor)
        listOf("Reload", "Add extension (.zip)", "Extensions on/off", "Remove extension", "Desktop site", "Clear data").forEachIndexed { i, t -> m.menu.add(0, i, i, t) }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> web.reload()
                1 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 1)
                2 -> toggleDialog()
                3 -> removeDialog()
                4 -> { desktop = !desktop; web.settings.userAgentString = if (desktop) DESKTOP_UA else null
                       web.settings.useWideViewPort = desktop; web.settings.loadWithOverviewMode = desktop; web.reload() }
                5 -> { web.clearCache(true); web.clearHistory(); CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData(); toast("Cleared") }
            }
            true
        }
        m.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun exts() = extDir.listFiles()?.filter { File(it, "manifest.json").exists() }?.sortedBy { it.name } ?: emptyList()
    private fun disabled() = prefs.getStringSet("off", emptySet())!!.toMutableSet()

    private fun toggleDialog() {
        val l = exts(); if (l.isEmpty()) { toast("No extensions"); return }
        val off = disabled(); val on = BooleanArray(l.size) { l[it].name !in off }
        AlertDialog.Builder(this).setTitle("Extensions")
            .setMultiChoiceItems(l.map { it.name }.toTypedArray(), on) { _, i, c -> if (c) off.remove(l[i].name) else off.add(l[i].name) }
            .setPositiveButton("Save") { _, _ -> prefs.edit().putStringSet("off", off).apply(); web.reload() }.show()
    }

    private fun removeDialog() {
        val l = exts(); if (l.isEmpty()) { toast("No extensions"); return }
        AlertDialog.Builder(this).setTitle("Remove which?")
            .setItems(l.map { it.name }.toTypedArray()) { _, i -> l[i].deleteRecursively(); toast("Removed"); web.reload() }.show()
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        val uri = d?.data ?: return
        if (r == 1 && c == RESULT_OK) try { install(uri); toast("Extension installed") } catch (e: Exception) { toast("Failed: ${e.message}") }
    }

    private fun install(uri: Uri) {
        val tmp = File(cacheDir, "x").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(contentResolver.openInputStream(uri)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val f = File(tmp, e.name)
                if (!f.canonicalPath.startsWith(tmp.canonicalPath)) throw Exception("bad zip") // zip-slip guard
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                e = z.nextEntry
            }
        }
        val mf = tmp.walkTopDown().filter { it.name == "manifest.json" }.minByOrNull { it.path.length } ?: throw Exception("no manifest.json")
        val name = JSONObject(mf.readText()).optString("name", "ext").replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val dest = File(extDir, name).apply { deleteRecursively() }
        mf.parentFile!!.copyRecursively(dest); tmp.deleteRecursively()
        web.reload()
    }

    private fun match(p: String, u: String): Boolean {
        if (p == "<all_urls>") return u.startsWith("http")
        return Regex("^" + p.split("*").joinToString(".*") { Regex.escape(it) } + "$").matches(u)
    }

    private val stub = "window.chrome=window.chrome||{};chrome.runtime=chrome.runtime||{getURL:function(p){return p}};" +
        "chrome.storage=chrome.storage||{local:{get:function(k,cb){try{cb&&cb(JSON.parse(localStorage.getItem('_x')||'{}'))}catch(e){}},set:function(o,cb){try{var s=JSON.parse(localStorage.getItem('_x')||'{}');for(var k in o)s[k]=o[k];localStorage.setItem('_x',JSON.stringify(s))}catch(e){}cb&&cb()}}};window.browser=window.browser||window.chrome;"

    private fun injectExtensions(v: WebView, url: String) {
        if (!url.startsWith("http")) return
        val off = disabled(); val js = StringBuilder(stub)
        for (d in exts()) {
            if (d.name in off) continue
            try {
                val cs = JSONObject(File(d, "manifest.json").readText()).optJSONArray("content_scripts") ?: continue
                for (i in 0 until cs.length()) {
                    val o = cs.getJSONObject(i); val m = o.optJSONArray("matches") ?: continue
                    if ((0 until m.length()).none { match(m.getString(it), url) }) continue
                    o.optJSONArray("css")?.let { a -> for (j in 0 until a.length()) File(d, a.getString(j)).takeIf { it.exists() }?.let {
                        js.append("(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(it.readText())};document.documentElement.appendChild(s)})();") } }
                    o.optJSONArray("js")?.let { a -> for (j in 0 until a.length()) File(d, a.getString(j)).takeIf { it.exists() }?.let {
                        js.append("try{").append(it.readText()).append("\n}catch(e){console.log(e)};") } }
                }
            } catch (_: Exception) {}
        }
        v.evaluateJavascript(js.toString(), null)
    }

    @Deprecated("") override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
