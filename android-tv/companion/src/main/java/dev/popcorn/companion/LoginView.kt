package dev.popcorn.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginView(initialServer: String, error: String, onScan: () -> Unit, onLogin: (String, String, String) -> Unit) {
    var server by remember(initialServer) { mutableStateOf(initialServer) }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Bg).padding(20.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Popcorn Remote", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Black)
            PopTextField(server, { server = it }, "Server")
            PopTextField(username, { username = it }, "Username")
            PopTextField(password, { password = it }, "Password", password = true)
            Button(onClick = { onLogin(server, username, password) }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                Text("Sign In", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Text("Scan Shield QR")
            }
            if (error.isNotBlank()) Text(error, color = if (error.contains("approved", true)) Accent else ErrorRed)
        }
    }
}

@Composable
fun PopTextField(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Muted, fontSize = 14.sp) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(color = TextColor, fontSize = 17.sp, fontWeight = FontWeight.Medium),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextColor,
            unfocusedTextColor = TextColor,
            focusedContainerColor = Surface1,
            unfocusedContainerColor = Surface1,
            focusedBorderColor = Blue,
            unfocusedBorderColor = Line,
            cursorColor = Blue,
            focusedLabelColor = Blue,
            unfocusedLabelColor = Muted,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    )
}
