package ir.cibuilder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        App(store)
                    }
                }
            }
        }
    }
}

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: "file"

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf("home") }
    var token by remember { mutableStateOf(store.token) }
    var repo by remember { mutableStateOf(store.repo) }
    var app by remember { mutableStateOf("") }
    var zip by remember { mutableStateOf<Uri?>(null) }
    var zipName by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("apk") }
    val status by BuildState.status.collectAsState()
    val buildLog by BuildState.log.collectAsState()
    val busy by BuildState.running.collectAsState()
    val outputs by BuildState.outputs.collectAsState()
    var refresh by remember { mutableStateOf(0) }

    // ---- انتخاب فایل‌ها ----
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            zip = uri; zipName = displayName(ctx, uri)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(store.export().toByteArray()) }
            toast(ctx, "بک‌آپ ذخیره شد")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                store.import(text)
                refresh++
                toast(ctx, "بازیابی انجام شد")
            } catch (e: Exception) {
                toast(ctx, "فایل بک‌آپ معتبر نیست")
            }
        }
    }
    var fileKey by remember { mutableStateOf("") }
    val fileSecretLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && app.isNotBlank() && fileKey.isNotBlank()) {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            store.put(app.trim(), fileKey.trim(), Base64.encodeToString(bytes, Base64.NO_WRAP))
            refresh++
            toast(ctx, "فایل به‌عنوان secret ذخیره شد")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (screen == "home") {
            Text("کامپایل روی گیت‌هاب", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("توکن گیت‌هاب") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = repo, onValueChange = { repo = it },
                label = { Text("ریپو (owner/repo)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedButton(onClick = {
                store.token = token.trim(); store.repo = repo.trim()
                toast(ctx, "ذخیره شد")
            }) { Text("ذخیرهٔ تنظیمات") }

            Spacer(Modifier.height(6.dp))

            OutlinedTextField(
                value = app, onValueChange = { app = it },
                label = { Text("نام برنامه") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            val apps = remember(refresh, screen) { store.apps() }
            if (apps.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    apps.forEach { a -> AssistChip(onClick = { app = a }, label = { Text(a) }) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }) { Text("انتخاب فایل زیپ") }
                Text(if (zipName.isEmpty()) "انتخاب نشده" else zipName, Modifier.padding(top = 10.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = target == "apk", onClick = { target = "apk" }, label = { Text("APK") })
                FilterChip(selected = target == "exe", onClick = { target = "exe" }, label = { Text("EXE") })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && zip != null && app.isNotBlank() && token.isNotBlank() && repo.isNotBlank(),
                    onClick = {
                        store.token = token.trim(); store.repo = repo.trim()
                        BuildService.start(ctx, app.trim(), zip!!, target)
                    }
                ) { Text(if (busy) "در حال کار…" else "کامپایل") }
                OutlinedButton(onClick = { screen = "secrets" }) { Text("Secretها") }
            }

            if (status.isNotEmpty()) {
                Text(status, fontSize = 13.sp)
            }

            outputs.forEach { (name, uri) ->
                Button(onClick = {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(i, name))
                }) { Text("اشتراک‌گذاری: $name") }
            }

            if (buildLog.isNotEmpty()) {
                Text("لاگ کامپایل", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(
                        buildLog.takeLast(20000),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
        } else {
            // ---------- صفحهٔ Secretها ----------
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { screen = "home" }) { Text("← بازگشت") }
                Text("Secret های برنامه", style = MaterialTheme.typography.titleLarge)
            }

            OutlinedTextField(
                value = app, onValueChange = { app = it },
                label = { Text("نام برنامه") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            val current = remember(refresh, app) { if (app.isBlank()) emptyMap() else store.get(app.trim()) }
            if (app.isNotBlank() && current.isEmpty()) Text("هنوز secret ای برای این برنامه نیست.")
            current.forEach { (k, v) ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("$k  (${v.length} نویسه)")
                    TextButton(onClick = { store.remove(app.trim(), k); refresh++ }) { Text("حذف") }
                }
            }

            var k by remember { mutableStateOf("") }
            var v by remember { mutableStateOf("") }
            OutlinedTextField(
                value = k, onValueChange = { k = it },
                label = { Text("نام (مثلاً KEYSTORE_PASSWORD)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = v, onValueChange = { v = it },
                label = { Text("مقدار") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = app.isNotBlank() && k.isNotBlank(),
                    onClick = { store.put(app.trim(), k.trim(), v); k = ""; v = ""; refresh++ }
                ) { Text("افزودن") }
                OutlinedButton(
                    enabled = app.isNotBlank() && k.isNotBlank(),
                    onClick = { fileKey = k; fileSecretLauncher.launch(arrayOf("*/*")) }
                ) { Text("افزودن فایل (مثل keystore)") }
            }
            Text(
                "برای امضای APK این‌ها را اضافه کنید: KEYSTORE_BASE64 (با «افزودن فایل»)، KEYSTORE_PASSWORD، KEY_ALIAS، KEY_PASSWORD",
                fontSize = 12.sp
            )

            if (app.isNotBlank()) {
                TextButton(onClick = { store.deleteApp(app.trim()); refresh++ }) { Text("حذف همهٔ secretهای این برنامه") }
            }

            Spacer(Modifier.height(8.dp))
            Text("بک‌آپ", style = MaterialTheme.typography.titleMedium)
            Text("فایل بک‌آپ رمزنگاری نیست؛ جای امنی نگه‌اش دارید.", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("cibuilder-secrets.json") }) { Text("دانلود بک‌آپ") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("بازیابی بک‌آپ") }
            }
        }
    }
}
