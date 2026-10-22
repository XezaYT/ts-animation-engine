package com.tsengine.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var engine: IsometricEngineView
    private val importer = FigmaAssetImporter()

    private val pickPuppet = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importAsset(uri, asPuppet = true)
    }
    private val pickTile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importAsset(uri, asPuppet = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        engine = IsometricEngineView(this)
        setContentView(engine)
        hideSystemBars()
        requestMaxRefreshRate()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        requestMaxRefreshRate()
        engine.onHostResume()
    }

    override fun onPause() {
        engine.onHostPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Immersive sticky: bars stay hidden and only reappear transiently on edge swipe. */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Asks the window for the display mode with the highest refresh rate at the current resolution (144Hz on the GT 30). */
    @Suppress("DEPRECATION")
    private fun requestMaxRefreshRate() {
        val display = windowManager.defaultDisplay ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: current
        val lp = window.attributes
        lp.preferredDisplayModeId = best.modeId
        lp.preferredRefreshRate = best.refreshRate
        window.attributes = lp
    }

    // Volume Up: import puppet (short) / export PNG snapshot (long)
    // Volume Down: import floor tile (short) / play-pause timeline (long)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                exportSnapshot()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                engine.togglePlay()
                true
            }
            else -> super.onKeyLongPress(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.isTracking && !event.isCanceled) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) pickPuppet.launch("*/*") else pickTile.launch("*/*")
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun importAsset(uri: Uri, asPuppet: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("Cannot open file")
                val trimmed = text.trimStart()
                if (trimmed.startsWith("{")) {
                    val o = JSONObject(trimmed)
                    val isNodeTree = o.has("document") || o.has("nodes") || o.has("children") || o.has("type")
                    if (!isNodeTree) {
                        importer.loadTokens(trimmed)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Design tokens loaded", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val asset = importer.importFigmaJson(trimmed)
                    deliver(asset, asPuppet)
                } else {
                    deliver(importer.importSvg(trimmed), asPuppet)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun deliver(asset: VectorAsset, asPuppet: Boolean) {
        withContext(Dispatchers.Main) {
            if (asPuppet) engine.setPuppetAsset(asset) else engine.setFloorTileAsset(asset)
            Toast.makeText(
                this@MainActivity,
                if (asPuppet) "Puppet imported" else "Floor tile imported",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exportSnapshot() {
        engine.captureFrame { bmp ->
            if (bmp == null) {
                Toast.makeText(this, "Snapshot failed", Toast.LENGTH_SHORT).show()
                return@captureFrame
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val name = "tsengine_${System.currentTimeMillis()}.png"
                val ok = try {
                    savePng(bmp, name)
                    true
                } catch (e: Exception) {
                    false
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Saved $name" else "Export failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun savePng(bmp: Bitmap, name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TSEngine")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
            dir.mkdirs()
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
