package dev.popcorn.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginView(initialServer: String, error: String, onScan: () -> Unit, onLogin: (String, String, String) -> Unit) {
    var server by remember(initialServer) { mutableStateOf(initialServer) }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Accent.copy(alpha = .12f),
                    .28f to Bg,
                    1f to Bg,
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = 22.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CinnamonBrand(markSize = 62, fontSize = 29)
            Spacer(Modifier.height(10.dp))
            Text(
                "Your cinema, everywhere.",
                color = Muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = .2.sp,
            )
            Spacer(Modifier.height(32.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Surface1.copy(alpha = .94f), RoundedCornerShape(24.dp))
                    .border(1.dp, Line.copy(alpha = .72f), RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Text("Welcome back", color = TextColor, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("Connect to your Cinnamon server to browse and play.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(2.dp))
                PopTextField(server, { server = it }, "Server address")
                PopTextField(username, { username = it }, "Username")
                PopTextField(password, { password = it }, "Password", password = true)
                Button(
                    onClick = { onLogin(server, username, password) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Sign in", fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(1.dp).background(Line))
                    Text("or", color = Muted, fontSize = 12.sp)
                    Box(Modifier.weight(1f).height(1.dp).background(Line))
                }
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Line),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(19.dp), tint = Teal)
                    Text("  Scan TV sign-in code", color = TextColor, fontWeight = FontWeight.Bold)
                }
                StatusMessage(error, success = error.contains("approved", true), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(18.dp))
            Text("CINNAMON  •  PERSONAL MEDIA", color = Muted.copy(alpha = .62f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun PopTextField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Muted, fontSize = 13.sp) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextColor,
            unfocusedTextColor = TextColor,
            focusedContainerColor = Surface2,
            unfocusedContainerColor = Surface2,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Line,
            cursorColor = Accent,
            focusedLabelColor = Accent,
            unfocusedLabelColor = Muted,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}
