package com.fuzzhawk.schnecke

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.fuzzhawk.schnecke.ui.Host
import com.fuzzhawk.schnecke.ui.SchneckeApp
import com.fuzzhawk.schnecke.ui.SchneckeTheme

class MainActivity : ComponentActivity(), Host {
    private val vm: MainViewModel by viewModels()

    private var pendingMicAction: (() -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) action?.invoke()
        else Toast.makeText(this, "Schnecke needs the microphone to hear your taps.", Toast.LENGTH_LONG).show()
    }

    private var pendingWav: ((Uri, String) -> Unit)? = null
    private val openWav = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = pendingWav
        pendingWav = null
        if (uri != null && cb != null) cb(uri, displayName(uri))
    }

    private val saveLaunchers = ExportKind.entries.associateWith { kind ->
        registerForActivityResult(ActivityResultContracts.CreateDocument(kind.mime)) { uri -> if (uri != null) vm.copyExportTo(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            SchneckeTheme {
                SchneckeApp(vm, this)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.stop()
        vm.saveNow()
    }

    override fun withMicPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingMicAction = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun pickWav(onPicked: (Uri, String) -> Unit) {
        pendingWav = onPicked
        openWav.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "audio/*"))
    }

    override fun saveExport() {
        val r = vm.state.value.exportResult ?: return
        saveLaunchers.getValue(r.kind).launch(r.file.name)
    }

    override fun shareExport() {
        val r = vm.state.value.exportResult ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", r.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = r.kind.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, r.file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ${r.file.name}"))
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment ?: "sample.wav"
    }
}
