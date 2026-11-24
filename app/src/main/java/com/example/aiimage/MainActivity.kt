package com.example.aiimage

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash-image"
        const val MAX_TRIES = 6
        const val PICK_CODE = 1
        const val ENHANCE_PROMPT =
            "Enhance this image: increase sharpness, reduce noise and grain, improve lighting and colors. " +
            "Keep the composition, subject and details exactly the same."
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var keyBox: EditText
    private lateinit var modelBox: EditText
    private lateinit var geminiBox: CheckBox
    private lateinit var promptBox: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var runBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private var inputBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        keyBox = EditText(this).apply {
            hint = "مفتاح Gemini (اختياري)"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        modelBox = EditText(this).apply {
            hint = "اسم موديل Gemini"
            setText(prefs.getString("model", DEFAULT_MODEL))
            setSingleLine()
        }
        geminiBox = CheckBox(this).apply {
            text = "استعمل Gemini للتوليد (يحتاج حساب مدفوع)"
            isChecked = prefs.getBoolean("gemini_gen", false)
        }

        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            1 to "توليد صورة من فكرة (مجاني)",
            2 to "تحسين صورة: ضوء، ألوان، وضوح (مجاني، في التليفون)",
            3 to "تعديل صورة بالأمر (Gemini، يحتاج حساب مدفوع)",
            4 to "تحسين بالذكاء الاصطناعي (Gemini، يحتاج حساب مدفوع)"
        ).forEach { (id, label) ->
            modeGroup.addView(RadioButton(this).apply { this.id = id; text = label })
        }
        modeGroup.check(1)

        promptBox = EditText(this).apply {
            hint = "اكتب الفكرة أو الأمر هنا (بالإنجليزية أحسن)"
            minLines = 2
        }

        val pickBtn = Button(this).apply {
            text = "اختار تصويرة من المعرض"
            setOnClickListener {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                startActivityForResult(i, PICK_CODE)
            }
        }
        runBtn = Button(this).apply { text = "ابدا"; setOnClickListener { run() } }
        saveBtn = Button(this).apply {
            text = "احفظ في المعرض"
            isEnabled = false
            setOnClickListener { save() }
        }
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(450)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        listOf(keyBox, modelBox, geminiBox, modeGroup, promptBox, pickBtn, runBtn, status, preview, saveBtn)
            .forEach {
                root.addView(it, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_CODE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val bmp = loadBitmap(uri)
            if (bmp == null) { setStatus("ما نجمتش نقرا الصورة"); return }
            inputBitmap = bmp
            preview.setImageBitmap(bmp)
            setStatus("الصورة جاهزة")
        }
    }

    private fun loadBitmap(uri: Uri, maxSide: Int = 1536): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun setStatus(s: String) { status.text = s }
    private fun setStatusAsync(s: String) { runOnUiThread { setStatus(s) } }

    private fun runAsync(work: () -> Bitmap) {
        runBtn.isEnabled = false
        saveBtn.isEnabled = false
        setStatus("نخدم... (ممكن ياخذ دقيقة)")
        thread {
            try {
                val bmp = work()
                runOnUiThread {
                    resultBitmap = bmp
                    preview.setImageBitmap(bmp)
                    saveBtn.isEnabled = true
                    setStatus("خلصت ✅")
                }
            } catch (e: Throwable) {
                setStatusAsync("خطأ: ${e.message}")
            } finally {
                runOnUiThread { runBtn.isEnabled = true }
            }
        }
    }

    private fun run() {
        val key = keyBox.text.toString().trim()
        val model = modelBox.text.toString().trim().ifEmpty { DEFAULT_MODEL }
        prefs.edit()
            .putString("key", key)
            .putString("model", model)
            .putBoolean("gemini_gen", geminiBox.isChecked)
            .apply()

        val mode = modeGroup.checkedRadioButtonId
        val userPrompt = promptBox.text.toString().trim()
        val img = inputBitmap
        val useGemini = mode == 3 || mode == 4 || (mode == 1 && geminiBox.isChecked)

        if (useGemini && key.isEmpty()) { setStatus("حط مفتاح Gemini أولا"); return }
        if (mode == 1 && userPrompt.isEmpty()) { setStatus("اكتب الفكرة"); return }
        if (mode != 1 && img == null) { setStatus("اختار تصويرة أولا"); return }
        if (mode == 3 && userPrompt.isEmpty()) { setStatus("اكتب شنو تحب تبدّل"); return }

        when (mode) {
            1 -> runAsync {
                if (geminiBox.isChecked) callGemini(key, model, userPrompt, null)
                else callPollinations(userPrompt)
            }
            2 -> runAsync { Enhancer.enhance(img!!) }
            3 -> runAsync { callGemini(key, model, userPrompt, img) }
            else -> runAsync {
                callGemini(key, model, ENHANCE_PROMPT + (if (userPrompt.isNotEmpty()) " $userPrompt" else ""), img)
            }
        }
    }

    private fun callPollinations(prompt: String): Bitmap {
        val encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val seed = System.currentTimeMillis() % 100000
        val url = "https://image.pollinations.ai/prompt/$encoded?width=1024&height=1024&model=flux&nologo=true&seed=$seed"
        var attempt = 0
        while (true) {
            attempt++
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 180000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            if (code in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw Exception("السيرفر ما رجّعش صورة")
            }
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if ((code == 429 || code == 502 || code == 503 || code == 504) && attempt < MAX_TRIES) {
                for (s in 20 downTo 1) {
                    setStatusAsync("السيرفر المجاني مشغول (كود $code). نعاود بعد $s ثانية - محاولة $attempt/$MAX_TRIES")
                    Thread.sleep(1000)
                }
                continue
            }
            throw Exception("HTTP $code: ${err.take(200)}")
        }
    }

    private fun callGemini(key: String, model: String, prompt: String, img: Bitmap?): Bitmap {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (img != null) {
            val bos = ByteArrayOutputStream()
            img.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            parts.put(
                JSONObject().put(
                    "inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                )
            )
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
            .toString()

        var attempt = 0
        while (true) {
            attempt++
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.connectTimeout = 30000
            conn.readTimeout = 180000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            conn.disconnect()

            if (code in 200..299) return parseImage(text)
            if (code == 429 && (text.contains("limit: 0") || text.contains("\"quotaValue\": \"0\""))) {
                throw Exception("الموديل هذا ما عندوش حصة مجانية (limit 0). استعمل الأوضاع المجانية ولا بدّل الموديل ولا فعّل الدفع.")
            }
            if ((code == 429 || code == 503) && attempt < MAX_TRIES) {
                for (s in 30 downTo 1) {
                    setStatusAsync("الخادم مشغول (كود $code). نعاود بعد $s ثانية - محاولة $attempt/$MAX_TRIES")
                    Thread.sleep(1000)
                }
                continue
            }
            throw Exception("HTTP $code: ${text.take(300)}")
        }
    }

    private fun parseImage(json: String): Bitmap {
        val cands = JSONObject(json).optJSONArray("candidates")
            ?: throw Exception("ما رجعش نتيجة (يمكن الطلب مرفوض)")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw Exception("ما رجعش نتيجة (يمكن الطلب مرفوض)")
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
            if (inl != null) {
                val bytes = Base64.decode(inl.getString("data"), Base64.DEFAULT)
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw Exception("الصورة المرجّعة خاربة")
            }
        }
        throw Exception("الموديل رجّع نص برك بلا صورة")
    }

    private fun save() {
        val bmp = resultBitmap ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ai_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIImage")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { setStatus("ما نجمتش نحفظ"); return }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        setStatus("تحفظت في المعرض (Pictures/AIImage)")
    }
}

object Enhancer {

    fun enhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        autoLevels(px)
        saturate(px, 1.12f)
        val out = unsharp(px, w, h, 2, 0.7f)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun autoLevels(px: IntArray) {
        val hist = IntArray(256)
        for (p in px) {
            val l = (0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)).toInt()
            hist[l]++
        }
        val clip = px.size / 100
        var acc = 0
        var lo = 0
        while (lo < 255 && acc + hist[lo] <= clip) { acc += hist[lo]; lo++ }
        acc = 0
        var hi = 255
        while (hi > 0 && acc + hist[hi] <= clip) { acc += hist[hi]; hi-- }
        if (hi - lo < 50) return
        val scale = 255f / (hi - lo)
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        val mean = sum.toFloat() / px.size
        val gamma = if (mean < 110f) 0.85 else 1.0
        val lut = IntArray(256) { v ->
            val x = ((v - lo) * scale).coerceIn(0f, 255f) / 255f
            val y = Math.pow(x.toDouble(), gamma).toFloat()
            (y * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        for (i in px.indices) {
            val p = px[i]
            val r = lut[(p shr 16) and 255]
            val g = lut[(p shr 8) and 255]
            val b = lut[p and 255]
            px[i] = (p and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun saturate(px: IntArray, s: Float) {
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val nr = (l + (r - l) * s).toInt().coerceIn(0, 255)
            val ng = (l + (g - l) * s).toInt().coerceIn(0, 255)
            val nb = (l + (b - l) * s).toInt().coerceIn(0, 255)
            px[i] = (p and (0xFF shl 24)) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    private fun unsharp(px: IntArray, w: Int, h: Int, radius: Int, amount: Float): IntArray {
        val out = IntArray(px.size) { 0xFF shl 24 }
        val chan = IntArray(px.size)
        val tmp = IntArray(px.size)
        val blur = IntArray(px.size)
        for (c in 0..2) {
            val shift = 16 - 8 * c
            for (i in px.indices) chan[i] = (px[i] shr shift) and 255
            boxBlur(chan, tmp, blur, w, h, radius)
            for (i in px.indices) {
                val o = chan[i]
                val diff = o - blur[i]
                val v = if (abs(diff) < 3) o else (o + amount * diff).toInt().coerceIn(0, 255)
                out[i] = out[i] or (v shl shift)
            }
        }
        return out
    }

    private fun boxBlur(src: IntArray, tmp: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / d
                val add = (x + r + 1).coerceAtMost(w - 1)
                val rem = (x - r).coerceAtLeast(0)
                sum += src[row + add] - src[row + rem]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / d
                val add = (y + r + 1).coerceAtMost(h - 1)
                val rem = (y - r).coerceAtLeast(0)
                sum += tmp[add * w + x] - tmp[rem * w + x]
            }
        }
    }
}
