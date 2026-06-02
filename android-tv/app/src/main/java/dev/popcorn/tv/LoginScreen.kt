package dev.popcorn.tv

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.UUID

@Composable
fun LoginView(initialServer: String, error: String, onLogin: (String, String, String) -> Unit, onQrLogin: (Session) -> Unit) {
    var server by remember { mutableStateOf(initialServer) }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("choose") }
    var setupPayload by remember { mutableStateOf("") }
    var setupAddress by remember { mutableStateOf("") }
    var setupJob by remember { mutableStateOf<Job?>(null) }
    var setupSocket by remember { mutableStateOf<ServerSocket?>(null) }
    var qrError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun startPhoneSetup() {
        setupJob?.cancel()
        runCatching { setupSocket?.close() }
        setupPayload = ""
        setupAddress = ""
        setupJob = scope.launch {
            qrError = ""
            val code = UUID.randomUUID().toString().replace("-", "")
            val host = withContext(Dispatchers.IO) { localIPv4Address() }
            if (host.isBlank()) {
                qrError = "Could not find Shield network address"
                return@launch
            }
            val socket = withContext(Dispatchers.IO) { ServerSocket(0) }
            setupSocket = socket
            val callback = "http://$host:${socket.localPort}/pair"
            setupAddress = callback
            setupPayload = JSONObject()
                .put("type", "popcorn-shield-setup")
                .put("callback", callback)
                .put("code", code)
                .put("deviceName", shieldDeviceName())
                .toString()
            runCatching {
                withContext(Dispatchers.IO) { socket.use { it.acceptShieldSetup(code) } }
            }.onSuccess {
                onQrLogin(it)
            }.onFailure {
                if (qrError.isBlank()) qrError = it.message ?: "Phone setup failed"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            setupJob?.cancel()
            runCatching { setupSocket?.close() }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(if (mode == "qr") 440.dp else 360.dp)
                .clip(CardShape)
                .background(SurfaceColor)
                .border(1.dp, Line, CardShape)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Popcorn", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))

            when (mode) {
                "manual" -> {
                    TvTextField(server, "Server URL", onChange = { server = it })
                    TvTextField(username, "Username", onChange = { username = it })
                    TvTextField(password, "Password", password = true, onChange = { password = it })
                    Spacer(Modifier.height(2.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        shape = RoundedCornerShape(6.dp),
                        onClick = { onLogin(server, username, password) },
                    ) {
                        Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextColor),
                        shape = RoundedCornerShape(6.dp),
                        onClick = { mode = "choose" },
                    ) {
                        Text("Back", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                "qr" -> {
                    if (setupPayload.isNotBlank()) {
                        QRCode(payload = setupPayload, modifier = Modifier.size(210.dp))
                        Text("Scan with Popcorn Remote", color = Muted, fontSize = 12.sp)
                        Text(setupAddress, color = TextColor, fontSize = 12.sp, textAlign = TextAlign.Center)
                    } else {
                        Box(Modifier.size(210.dp).clip(RoundedCornerShape(8.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                            Text("Create a phone login QR", color = Muted, fontSize = 13.sp)
                        }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        shape = RoundedCornerShape(6.dp),
                        onClick = { startPhoneSetup() },
                    ) {
                        Text(if (setupPayload.isBlank()) "Create QR Code" else "Refresh QR Code", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextColor),
                        shape = RoundedCornerShape(6.dp),
                        onClick = {
                            setupPayload = ""
                            setupAddress = ""
                            setupJob?.cancel()
                            runCatching { setupSocket?.close() }
                            mode = "choose"
                        },
                    ) {
                        Text("Back", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                else -> {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        shape = RoundedCornerShape(6.dp),
                        onClick = {
                            mode = "qr"
                            startPhoneSetup()
                        },
                    ) {
                        Text("Login with QR Code", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextColor),
                        shape = RoundedCornerShape(6.dp),
                        onClick = { mode = "manual" },
                    ) {
                        Text("Login Manually", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            if (error.isNotBlank()) {
                Text(error, color = ErrorRed, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            if (qrError.isNotBlank()) {
                Text(qrError, color = ErrorRed, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun QRCode(payload: String, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) { qrBitmap(payload, 512) }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR login", modifier = Modifier.fillMaxSize())
    }
}

private fun qrBitmap(payload: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (y in 0 until size) {
        for (x in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}

private fun ServerSocket.acceptShieldSetup(expectedCode: String): Session {
    val socket = accept()
    socket.use {
        it.soTimeout = 30000
        val reader = it.getInputStream().bufferedReader()
        val requestLine = reader.readLine().orEmpty()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: ""
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        val bodyChars = CharArray(contentLength.coerceAtLeast(0))
        var read = 0
        while (read < bodyChars.size) {
            val n = reader.read(bodyChars, read, bodyChars.size - read)
            if (n < 0) break
            read += n
        }
        fun respond(status: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            it.getOutputStream().write(
                ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            it.getOutputStream().write(bytes)
            it.getOutputStream().flush()
        }
        if (!requestLine.startsWith("POST /pair ")) {
            respond("404 Not Found", """{"error":"not found"}""")
            error("invalid setup request")
        }
        val json = JSONObject(String(bodyChars, 0, read))
        if (json.optString("code") != expectedCode) {
            respond("403 Forbidden", """{"error":"invalid code"}""")
            error("invalid setup code")
        }
        val server = json.optString("server").trimEnd('/')
        val token = json.optString("token")
        val username = json.optString("username")
        if (server.isBlank() || token.isBlank()) {
            respond("400 Bad Request", """{"error":"server and token are required"}""")
            error("phone did not send server and token")
        }
        respond("200 OK", """{"ok":true}""")
        return Session(server, token, username)
    }
}

private fun localIPv4Address(): String {
    return runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                val host = address.hostAddress.orEmpty()
                if (host.indexOf(':') < 0 && !address.isLoopbackAddress && !host.startsWith("169.254.")) {
                    return@runCatching host
                }
            }
        }
        ""
    }.getOrDefault("")
}
