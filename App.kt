package com.friday.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ------------------------------------------------------------------ */
/* Settings stored on the phone (the Gemini key never leaves the app)   */
/* ------------------------------------------------------------------ */
object Prefs {
    private const val DEFAULT_MODEL = "gemini-2.5-flash"
    private fun sp(c: Context) = c.getSharedPreferences("friday", Context.MODE_PRIVATE)
    fun key(c: Context): String = sp(c).getString("key", "") ?: ""
    fun model(c: Context): String = sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun save(c: Context, key: String, model: String) {
        sp(c).edit().putString("key", key.trim()).putString("model", model.trim().ifEmpty { DEFAULT_MODEL }).apply()
    }
}

/* ------------------------------------------------------------------ */
/* Bridge from the service to the hologram page                         */
/* ------------------------------------------------------------------ */
object Bus {
    @Volatile
    var activity: MainActivity? = null

    private fun js(code: String) {
        val a = activity ?: return
        a.runOnUiThread { a.web.evaluateJavascript(code, null) }
    }

    fun mic(v: Float) = js("window.Friday&&Friday.setMic($v)")
    fun speaking(b: Boolean) = js("window.Friday&&Friday.setSpeaking($b)")
    fun status(s: String) = js("window.Friday&&Friday.setStatus(" + JSONObject.quote(s) + ")")
}

/* ------------------------------------------------------------------ */
/* The brain: Gemini over plain HTTPS                                   */
/* ------------------------------------------------------------------ */
class Reply(val say: String, val action: JSONObject?)

object Gemini {
    private val hist = ArrayList<Pair<String, String>>()

    private fun system(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        return "You are Friday, a warm, witty and concise voice assistant living on the user's Android phone, " +
            "like the AI from the Iron Man films. You are speaking aloud, so reply in 1 to 3 short natural sentences " +
            "with no markdown, lists or emoji, in the same language the user speaks. " +
            "Answer ONLY with JSON of the form {\"say\": string, \"action\": object or null}. " +
            "Set action only when the user wants the phone to do something. Action types: " +
            "{\"type\":\"call\",\"name\":\"..\"} (or \"number\"); " +
            "{\"type\":\"sms\",\"name\":\"..\",\"text\":\"..\"}; " +
            "{\"type\":\"whatsapp\",\"name\":\"..\",\"text\":\"..\"}; " +
            "{\"type\":\"open_app\",\"app\":\"..\"}; " +
            "{\"type\":\"web_search\",\"query\":\"..\"}; " +
            "{\"type\":\"youtube\",\"query\":\"..\"}; " +
            "{\"type\":\"navigate\",\"place\":\"..\"}; " +
            "{\"type\":\"alarm\",\"hour\":0-23,\"minute\":0-59,\"label\":\"..\"}; " +
            "{\"type\":\"timer\",\"seconds\":n,\"label\":\"..\"}; " +
            "{\"type\":\"flashlight\",\"on\":true or false}; " +
            "{\"type\":\"volume\",\"direction\":\"up\" or \"down\"}; " +
            "{\"type\":\"settings\",\"page\":\"wifi\" or \"bluetooth\" or \"display\" or \"sound\" or \"battery\" or \"location\"}. " +
            "Never claim you did something unless you included the matching action. " +
            "If you cannot do something, say so honestly. Messages and chats are only drafted, the user sends them. " +
            "Current date and time: " + now + "."
    }

    fun ask(c: Context, text: String): Reply {
        val key = Prefs.key(c)
        if (key.isEmpty()) return Reply("I need a Gemini key first. Press and hold the screen to add one.", null)
        try {
            val contents = JSONArray()
            for (h in hist) {
                contents.put(JSONObject().put("role", h.first).put("parts", JSONArray().put(JSONObject().put("text", h.second))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system()))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.8))

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" + Prefs.model(c) + ":generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 429) return Reply("I have used up my free requests for the moment. Try again in a minute.", null)
            if (code !in 200..299) {
                val m = try {
                    JSONObject(resp).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    "error $code"
                }
                return Reply("Gemini reported a problem: " + m.take(120), null)
            }
            val raw = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val j = JSONObject(clean)
            hist.add(Pair("user", text))
            hist.add(Pair("model", clean))
            while (hist.size > 12) hist.removeAt(0)
            val act = j.optJSONObject("action")
            return Reply(j.optString("say", ""), if (act != null && act.optString("type", "none") != "none") act else null)
        } catch (e: java.net.UnknownHostException) {
            return Reply("I can't reach the internet right now.", null)
        } catch (e: java.io.IOException) {
            return Reply("My connection dropped. Please try again.", null)
        } catch (e: Exception) {
            return Reply("Sorry, I couldn't make sense of that reply.", null)
        }
    }
}

/* ------------------------------------------------------------------ */
/* The hands: things Friday can do on the phone                         */
/* ------------------------------------------------------------------ */
class Actions(private val c: Context) {
    private fun go(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }

    private fun number(a: JSONObject): String? {
        val n = a.optString("number", "")
        if (n.isNotEmpty()) return n
        val name = a.optString("name", "").lowercase().trim()
        if (name.isEmpty()) return null
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        var best: String? = null
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val dn = (cur.getString(0) ?: "").lowercase()
                if (dn == name) {
                    best = cur.getString(1)
                    break
                }
                if (best == null && dn.contains(name)) best = cur.getString(1)
            }
        }
        return best
    }

    private fun missing(a: JSONObject) = "I couldn't find " + a.optString("name", "that contact") + " in your contacts."

    private fun openApp(name: String): String? {
        val q = name.lowercase().trim()
        if (q.isEmpty()) return "Which app should I open?"
        val pm = c.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: String? = null
        for (r in list) {
            val label = r.loadLabel(pm).toString().lowercase()
            if (label == q) {
                best = r.activityInfo.packageName
                break
            }
            if (best == null && (label.contains(q) || q.contains(label))) best = r.activityInfo.packageName
        }
        val pkg = best ?: return "I couldn't find an app called $name."
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "I couldn't open $name."
        go(i)
        return null
    }

    /** Runs the action. Returns a sentence to speak if something went wrong, otherwise null. */
    fun run(a: JSONObject): String? {
        try {
            when (a.optString("type")) {
                "call" -> {
                    val n = number(a) ?: return missing(a)
                    val granted = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                    go(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
                }
                "sms" -> {
                    val n = number(a) ?: return missing(a)
                    go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).putExtra("sms_body", a.optString("text")))
                }
                "whatsapp" -> {
                    val n = number(a) ?: return missing(a)
                    val digits = n.filter { it.isDigit() }
                    go(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(a.optString("text")))))
                }
                "open_app" -> return openApp(a.optString("app"))
                "web_search" -> go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.optString("query")))))
                "youtube" -> go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("query")))))
                "navigate" -> go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(a.optString("place")))))
                "alarm" -> go(
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Friday"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
                "timer" -> go(
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Friday"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
                "flashlight" -> {
                    val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    cm.setTorchMode(cm.cameraIdList[0], a.optBoolean("on", true))
                }
                "volume" -> {
                    val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val dir = if (a.optString("direction") == "down") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                    for (n in 0 until 3) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, if (n == 2) AudioManager.FLAG_SHOW_UI else 0)
                }
                "settings" -> {
                    val act = when (a.optString("page")) {
                        "wifi" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                        "display" -> Settings.ACTION_DISPLAY_SETTINGS
                        "sound" -> Settings.ACTION_SOUND_SETTINGS
                        "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        else -> Settings.ACTION_SETTINGS
                    }
                    go(Intent(act))
                }
            }
        } catch (e: Exception) {
            return "I couldn't do that on this phone."
        }
        return null
    }
}

/* ------------------------------------------------------------------ */
/* Always-on listener: wake phrase, conversation, speaking              */
/* ------------------------------------------------------------------ */
class FridayService : Service() {
    private enum class S { WAKE, COMMAND, BUSY }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var s = S.WAKE
    private var woke = false
    private var wakeUi = false
    private var errs = 0
    private var onSpeechDone: (() -> Unit)? = null
    private val actions by lazy { Actions(this) }
    private val dismiss = Runnable {
        if (s == S.WAKE && wakeUi) {
            wakeUi = false
            Bus.activity?.finish()
        }
    }

    companion object {
        @Volatile
        var instance: FridayService? = null
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.getDefault())
                tts?.setPitch(0.9f)
                tts?.setSpeechRate(0.95f)
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Bus.speaking(true)
                Bus.status("Speaking")
            }

            override fun onDone(id: String?) {
                finishSpeech()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                finishSpeech()
            }
        })
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startFg()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (rec == null) listen(S.WAKE)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        rec?.destroy()
        rec = null
        tts?.shutdown()
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("friday", "Friday listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FridayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_delete)
        val n = Notification.Builder(this, "friday")
            .setContentTitle("Friday is listening")
            .setContentText("Say \"Hey Friday\". Tap to open.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(stopIcon, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    /* ---------- listening ---------- */
    private fun listen(mode: S) {
        s = mode
        woke = false
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Bus.status("Speech service missing. Install the Google app")
                return@post
            }
            try {
                val r = rec ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                    it.setRecognitionListener(listener)
                    rec = it
                }
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                if (mode == S.WAKE) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                r.startListening(i)
                Bus.status(if (mode == S.WAKE) "Say \"Hey Friday\"" else "Listening")
            } catch (e: Exception) {
                retry(mode, 1000)
            }
        }
    }

    private fun retry(mode: S, ms: Long) {
        main.postDelayed({
            if (instance != null && s != S.BUSY) listen(mode)
        }, ms)
    }

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEvent(t: Int, p: Bundle?) {}

        override fun onRmsChanged(rms: Float) {
            if (s == S.COMMAND) Bus.mic(((rms + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onEndOfSpeech() {
            Bus.mic(0f)
        }

        override fun onError(e: Int) {
            Bus.mic(0f)
            if (s == S.BUSY) return
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Bus.status("Microphone permission needed")
                return
            }
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                rec?.destroy()
                rec = null
            }
            if (s == S.COMMAND) {
                backToWake()
            } else {
                retry(S.WAKE, minOf(300L + errs * 300L, 3000L))
                errs++
            }
        }

        override fun onPartialResults(b: Bundle?) {
            if (s == S.WAKE && !woke && first(b).lowercase().contains("friday")) {
                woke = true
                errs = 0
                openUi()
            }
        }

        override fun onResults(b: Bundle?) {
            errs = 0
            finalText(first(b))
        }
    }

    private fun finalText(text: String) {
        val lc = text.lowercase().trim()
        if (s == S.WAKE) {
            val i = lc.indexOf("friday")
            if (i < 0) {
                listen(S.WAKE)
                return
            }
            val cmd = lc.substring(i + 6).trim(' ', ',', '.', '!', '?')
            openUi()
            if (cmd.length >= 2) {
                handle(cmd)
            } else {
                s = S.BUSY
                speak("Yes?") { listen(S.COMMAND) }
            }
        } else if (s == S.COMMAND) {
            if (lc.isEmpty()) backToWake() else handle(text.trim())
        }
    }

    /* ---------- thinking and doing ---------- */
    private fun handle(cmd: String) {
        s = S.BUSY
        main.removeCallbacks(dismiss)
        Bus.status("Thinking")
        Thread {
            val r = Gemini.ask(applicationContext, cmd)
            main.post { respond(r) }
        }.start()
    }

    private fun respond(r: Reply) {
        val a = r.action
        speak(r.say) {
            val err = if (a != null) actions.run(a) else null
            if (err != null) {
                speak(err) { backToWake() }
            } else if (a == null) {
                listen(S.COMMAND)
            } else {
                backToWake()
            }
        }
    }

    private fun backToWake() {
        listen(S.WAKE)
        if (wakeUi) main.postDelayed(dismiss, 8000)
    }

    fun tapToTalk() {
        main.post {
            if (s == S.BUSY) return@post
            rec?.cancel()
            s = S.BUSY
            speak("Yes?") { listen(S.COMMAND) }
        }
    }

    /* ---------- showing and speaking ---------- */
    private fun openUi() {
        main.removeCallbacks(dismiss)
        wakeUi = true
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("wake", true)
            )
        } catch (e: Exception) {
        }
    }

    private fun speak(text: String, done: () -> Unit) {
        if (text.isBlank() || !ttsReady) {
            done()
            return
        }
        rec?.cancel()
        onSpeechDone = done
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "friday")
    }

    private fun finishSpeech() {
        Bus.speaking(false)
        main.post {
            val cb = onSpeechDone
            onSpeechDone = null
            cb?.invoke()
        }
    }
}

/* ------------------------------------------------------------------ */
/* Screen: the hologram, plus one-time setup                            */
/* ------------------------------------------------------------------ */
class MainActivity : Activity() {
    lateinit var web: WebView
    private var step = 0

    private fun perms(): Array<String> {
        val l = arrayListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) l.add("android.permission.POST_NOTIFICATIONS")
        return l.toTypedArray()
    }

    inner class Bridge {
        @JavascriptInterface
        fun listenNow() {
            FridayService.instance?.tapToTalk()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.setOnLongClickListener {
            askKey(null)
            true
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/friday.html")
        Bus.activity = this
        hideBars()
        if (!intent.getBooleanExtra("wake", false)) nextStep()
    }

    override fun onResume() {
        super.onResume()
        hideBars()
    }

    override fun onDestroy() {
        if (Bus.activity === this) Bus.activity = null
        web.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    /** One-time setup, one step at a time: permissions, overlay, battery, key, start listening. */
    @Suppress("DEPRECATION")
    private fun nextStep() {
        when (step++) {
            0 -> requestPermissions(perms(), 1)
            1 -> if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Turn on \"Display over other apps\" for Friday", Toast.LENGTH_LONG).show()
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 2)
            } else {
                nextStep()
            }
            2 -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivityForResult(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")), 3)
                } else {
                    nextStep()
                }
            }
            3 -> if (Prefs.key(this).isEmpty()) askKey { nextStep() } else nextStep()
            else -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Friday needs the microphone permission to listen", Toast.LENGTH_LONG).show()
                } else {
                    startForegroundService(Intent(this, FridayService::class.java))
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        nextStep()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        nextStep()
    }

    private fun askKey(done: (() -> Unit)?) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(48, 24, 48, 0)
        val k = EditText(this)
        k.hint = "Gemini API key"
        k.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        k.setText(Prefs.key(this))
        val m = EditText(this)
        m.hint = "Model"
        m.setText(Prefs.model(this))
        box.addView(k)
        box.addView(m)
        AlertDialog.Builder(this)
            .setTitle("Friday settings")
            .setMessage("Paste your free Gemini API key. Press and hold the screen any time to change it.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Prefs.save(this, k.text.toString(), m.text.toString())
                done?.invoke()
            }
            .setNegativeButton("Later") { _, _ -> done?.invoke() }
            .show()
    }
}
