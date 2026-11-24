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
import kotlin.concurrent.thread

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
            hint = "مفتاح Gemini API"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        modelBox = EditText(this).apply {
            hint = "اسم الموديل"
            setText(prefs.getString("model", DEFAULT_MODEL))
            setSingleLine()
        }

        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(1 to "توليد صورة من فكرة", 2 to "تحسين صورة (ضوء، ألوان، وضوح)", 3 to "تعديل صورة بالأمر (نحّي / زيد)")
            .forEach { (id, label) ->
                modeGroup.addView(RadioButton(this).apply { this.id = id; text = label })
            }
        modeGroup.check(1)

        promptBox = EditText(this).apply {
            hint = "اكتب الفكرة أو الأمر هنا"
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

        listOf(keyBox, modelBox, modeGroup, promptBox, pickBtn, runBtn, status, preview, saveBtn)
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
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun setStatus(s: String) { status.text = s }
    private fun setStatusAsync(s: String) { runOnUiThread { setStatus(s) } }

    private fun run() {
        val key = keyBox.text.toString().trim()
        val model = modelBox.text.toString().trim().ifEmpty { DEFAULT_MODEL }
        prefs.edit().putString("key", key).putString("model", model).apply()

        val mode = modeGroup.checkedRadioButtonId
        val userPrompt = promptBox.text.toString().trim()
        val img = inputBitmap

        if (key.isEmpty()) { setStatus("حط مفتاح Gemini أولا"); return }
        if (mode == 1 && userPrompt.isEmpty()) { setStatus("اكتب الفكرة"); return }
        if (mode != 1 && img == null) { setStatus("اختار تصويرة أولا"); return }
        if (mode == 3 && userPrompt.isEmpty()) { setStatus("اكتب شنو تحب تبدّل"); return }

        val prompt = if (mode == 2) ENHANCE_PROMPT + (if (userPrompt.isNotEmpty()) " $userPrompt" else "") else userPrompt

        runBtn.isEnabled = false
        saveBtn.isEnabled = false
        setStatus("نخدم... (ممكن ياخذ دقيقة)")
        thread {
            try {
                val bmp = callGemini(key, model, prompt, if (mode == 1) null else img)
                runOnUiThread {
                    resultBitmap = bmp
                    preview.setImageBitmap(bmp)
                    saveBtn.isEnabled = true
                    setStatus("خلصت ✅")
                }
            } catch (e: Exception) {
                setStatusAsync("خطأ: ${e.message}")
            } finally {
                runOnUiThread { runBtn.isEnabled = true }
            }
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
