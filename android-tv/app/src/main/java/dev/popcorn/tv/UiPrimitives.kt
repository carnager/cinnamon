package dev.popcorn.tv

import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
fun CinnamonBrand(
    modifier: Modifier = Modifier,
    showName: Boolean = true,
    markSize: Int = 38,
    fontSize: Int = 20,
    // Corner brand over browsing screens: the name shows at app start, then
    // collapses into the mark, which sits above the side rail — the full
    // wordmark covered grid content scrolling beneath it.
    collapsible: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(markSize.dp)) {
            val stroke = (size.minDimension * .075f).coerceAtLeast(2f)
            listOf(.43f, .31f, .19f).forEach { radiusFraction ->
                val radius = size.minDimension * radiusFraction
                drawArc(
                    color = Accent,
                    startAngle = 42f,
                    sweepAngle = 276f,
                    useCenter = false,
                    topLeft = center - androidx.compose.ui.geometry.Offset(radius, radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showName && (!collapsible || BrandChrome.expanded),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally(),
        ) {
            Text(
                "Cinnamon",
                color = TextColor,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun FocusButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        primary && focused -> Accent
        primary -> AccentDim
        focused -> Surface3
        else -> Surface2
    }
    val border = when {
        focused -> FocusGlow
        primary -> Accent.copy(alpha = .4f)
        else -> Color.Transparent
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(2.dp, border, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.White else TextColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun Poster(session: Session?, itemId: Long, modifier: Modifier, version: Long = 0) {
    val url = imageUrl(session, itemId, "poster", version, ArtworkCard)
    Box(modifier.aspectRatio(2f / 3f).clip(CardShape).background(Surface2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            SizedAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 260, heightPx = 390, authToken = session?.token.orEmpty())
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
    authToken: String = "",
) {
    val context = LocalContext.current
    val request = remember(model, widthPx, heightPx, authToken) {
        val builder = ImageRequest.Builder(context)
            .data(model)
            .size(widthPx, heightPx)
            .crossfade(false)
            .allowHardware(true)
        if (authToken.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $authToken")
        }
        builder.build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
fun Pill(text: String, selected: Boolean, badge: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val background = Color.Transparent
    val border = when {
        focused -> Accent
        selected -> Accent.copy(alpha = .48f)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(11.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, color = TextColor, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        if (badge != null) {
            Text(badge, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun RatingBadge(rating: Double, small: Boolean = false) {
    Text(
        "\u2605 ${formatRating(rating)}",
        color = Accent,
        fontWeight = FontWeight.Bold,
        fontSize = if (small) 9.sp else 11.sp,
        modifier = Modifier
            .background(Accent.copy(alpha = .12f), RoundedCornerShape(4.dp))
            .padding(horizontal = if (small) 4.dp else 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SourceRatingBadge(label: String, value: String) {
    Row(
        modifier = Modifier
            .background(Surface2, RoundedCornerShape(4.dp))
            .border(1.dp, Line, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Teal, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Text(value, color = TextColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun TvTextField(value: String, label: String, password: Boolean = false, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Muted, fontSize = 12.sp) },
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
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
    )
}

@Composable
fun LoadingView(error: String, server: String = "", onRetry: () -> Unit = {}, onSignOut: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        if (error.isBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Text("Loading\u2026", color = Muted, fontSize = 14.sp)
            }
        } else {
            // A server that stays down used to leave only this red line: no
            // sign it was still trying, no way to change server or sign out.
            val retryFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Text(error, color = ErrorRed, fontSize = 16.sp)
                if (server.isNotBlank()) Text(server, color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusButton(label = "Retry now", primary = true, modifier = Modifier.focusRequester(retryFocus), onClick = onRetry)
                    FocusButton(label = "Change server / sign out", primary = false, onClick = onSignOut)
                }
            }
        }
    }
}

@Composable
fun MessageBanner(text: String, isError: Boolean) {
    if (text.isBlank()) return
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isError) Color(0xF0471A1A) else Color(0xF0172029))
                .border(1.dp, if (isError) ErrorRed.copy(alpha = .7f) else Color.White.copy(alpha = .16f), RoundedCornerShape(12.dp))
                .padding(horizontal = 22.dp, vertical = 14.dp),
        )
    }
}

private val decimalSeparator = java.text.DecimalFormatSymbols.getInstance().decimalSeparator

// "%.1f".format allocates a Formatter per call; posters call this for every
// card on every recomposition. Same output, locale separator included.
fun formatRating(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}$decimalSeparator${kotlin.math.abs(tenths % 10)}"
}

// App-wide: whether corner brands still show the name (see CinnamonBrand).
object BrandChrome {
    var expanded by mutableStateOf(true)
}
