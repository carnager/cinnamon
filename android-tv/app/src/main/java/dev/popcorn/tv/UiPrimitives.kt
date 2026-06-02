package dev.popcorn.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun FocusButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (primary) {
                    if (focused) Accent else AccentDim
                } else {
                    if (focused) Surface3 else Surface2
                }
            )
            .border(2.dp, if (focused) FocusGlow else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.Black else TextColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun Poster(session: Session?, itemId: Long, modifier: Modifier, version: Long = 0) {
    val url = imageUrl(session, itemId, "poster", version)
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            SizedAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 260, heightPx = 390)
        } else {
            Text("?", color = Muted, fontSize = 20.sp)
        }
    }
}

@Composable
fun SizedAsyncImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    widthPx: Int,
    heightPx: Int,
) {
    val context = LocalContext.current
    val request = remember(model, widthPx, heightPx) {
        ImageRequest.Builder(context)
            .data(model)
            .size(widthPx, heightPx)
            .crossfade(false)
            .allowHardware(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
fun Pill(text: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Accent else if (focused) Surface2 else Color.Transparent)
            .border(1.dp, if (focused && !selected) FocusGlow else if (!selected) Line else Color.Transparent, RoundedCornerShape(999.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, color = if (selected) Color.Black else TextColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (badge != null) {
            Text(badge, color = if (selected) Color.Black.copy(alpha = .6f) else Muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun RatingBadge(rating: Double, small: Boolean = false) {
    Text(
        "\u2605 ${"%.1f".format(rating)}",
        color = Gold,
        fontWeight = FontWeight.Bold,
        fontSize = if (small) 9.sp else 11.sp,
        modifier = Modifier
            .background(Gold.copy(alpha = .12f), RoundedCornerShape(3.dp))
            .padding(horizontal = if (small) 4.dp else 5.dp, vertical = 1.dp),
    )
}

@Composable
fun SourceRatingBadge(label: String, value: String) {
    Row(
        modifier = Modifier
            .background(Surface2, RoundedCornerShape(3.dp))
            .border(1.dp, Line, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        Text(value, color = TextColor, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}

@Composable
fun PosterRating(rating: Double) {
    Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            "\u2605 ${"%.1f".format(rating)}",
            color = Gold, fontWeight = FontWeight.Bold, fontSize = 9.sp,
            modifier = Modifier.background(Color.Black.copy(alpha = .7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun TvTextField(value: String, label: String, password: Boolean = false, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Muted, fontSize = 11.sp) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextColor,
            unfocusedTextColor = TextColor,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Line,
            cursorColor = Accent,
            focusedLabelColor = Accent,
            unfocusedLabelColor = Muted,
            focusedContainerColor = Bg,
            unfocusedContainerColor = Bg,
        ),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.then(if (modifier == Modifier) Modifier.fillMaxWidth() else Modifier),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
    )
}

@Composable
fun LoadingView(error: String) {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        if (error.isBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                Text("Loading\u2026", color = Muted, fontSize = 13.sp)
            }
        } else {
            Text(error, color = ErrorRed, fontSize = 14.sp)
        }
    }
}
