package com.vexa.meet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vexa.meet.R

enum class MeetingEntryScreen { HOME, JOIN }

private val Background = Color(0xFF080D15)
private val Panel = Color(0xFF101823)
private val Blue = Color(0xFF246BFD)
private val Muted = Color(0xFFA8B6CA)
private val Outline = Color(0xFF4B5D76)

@Composable
fun MeetingEntryScreens(
    screen: MeetingEntryScreen,
    roomId: String,
    error: String?,
    busy: Boolean,
    onRoomIdChange: (String) -> Unit,
    onShowJoin: () -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onJoin: () -> Unit
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue,
            background = Background,
            surface = Panel,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Muted
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            BoxWithConstraints(contentAlignment = Alignment.TopCenter) {
                val availableHeight = maxHeight
                val scrollState = key(screen) { rememberScrollState() }
                val compact = maxHeight < 820.dp
                val heroHeight = (maxHeight * if (compact) 0.25f else 0.30f).coerceIn(150.dp, 280.dp)
                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        .verticalScroll(scrollState)
                        .heightIn(min = availableHeight)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    if (screen == MeetingEntryScreen.HOME) {
                        Column {
                            Spacer(Modifier.height(if (compact) 12.dp else 36.dp))
                            BrandTitle()
                            Text(stringResource(R.string.meeting_tagline), color = Muted, fontSize = 18.sp)
                            Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
                            Image(
                                painter = painterResource(R.drawable.meeting_hero),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().height(heroHeight)
                            )
                            Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
                            Text(
                                stringResource(R.string.meeting_welcome_title),
                                modifier = Modifier.fillMaxWidth(),
                                fontSize = 25.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, lineHeight = 32.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.meeting_welcome_description),
                                modifier = Modifier.fillMaxWidth(),
                                color = Muted, fontSize = 17.sp, lineHeight = 26.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(if (compact) 24.dp else 36.dp))
                        }
                        Column {
                            MeetingButton(stringResource(R.string.start_meeting), busy, onStart)
                            OrDivider(compact)
                            OutlinedMeetingButton(
                                stringResource(R.string.have_room_id),
                                R.drawable.ic_meeting_keypad, !busy, onShowJoin
                            )
                            Spacer(Modifier.height(if (compact) 24.dp else 36.dp))
                        }
                        Text(
                            stringResource(R.string.meeting_footer),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center
                        )
                    } else {
                        val focusManager = LocalFocusManager.current
                        val submit = {
                            focusManager.clearFocus()
                            onJoin()
                        }
                        Column {
                            TextButton(
                                onClick = onBack, enabled = !busy,
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                            ) {
                                Icon(painterResource(R.drawable.ic_meeting_back), contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.meeting_back), fontSize = 16.sp)
                            }
                            Spacer(Modifier.height(32.dp))
                            BrandTitle()
                            Text(stringResource(R.string.join_meeting_title), color = Muted, fontSize = 20.sp)
                            Spacer(Modifier.height(42.dp))
                            Surface(
                                shape = RoundedCornerShape(20.dp), color = Panel,
                                border = BorderStroke(1.dp, Color(0xFF202D40))
                            ) {
                                Column(Modifier.padding(20.dp)) {
                                    Text(stringResource(R.string.room_id_label), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                    Spacer(Modifier.height(16.dp))
                                    OutlinedTextField(
                                        value = roomId, onValueChange = onRoomIdChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !busy, singleLine = true, isError = error != null,
                                        placeholder = { Text(stringResource(R.string.room_id_hint), fontSize = 15.sp) },
                                        leadingIcon = { Text("#", color = Muted, fontSize = 25.sp) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = Outline,
                                            focusedBorderColor = Blue,
                                            unfocusedContainerColor = Background,
                                            focusedContainerColor = Background
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.None,
                                            autoCorrectEnabled = false,
                                            keyboardType = KeyboardType.Ascii,
                                            imeAction = ImeAction.Go
                                        ),
                                        keyboardActions = KeyboardActions(onGo = { if (!busy) submit() }),
                                        supportingText = if (error != null) ({ Text(error) }) else null
                                    )
                                    Spacer(Modifier.height(26.dp))
                                    MeetingButton(stringResource(R.string.join_meeting), busy, submit)
                                }
                            }
                            OrDivider()
                            OutlinedMeetingButton(
                                stringResource(R.string.start_new_meeting),
                                R.drawable.ic_videocam, !busy, onStart
                            )
                            Spacer(Modifier.height(48.dp))
                            Surface(
                                shape = RoundedCornerShape(20.dp), color = Panel,
                                border = BorderStroke(1.dp, Color(0xFF202D40))
                            ) {
                                Row(Modifier.padding(20.dp)) {
                                    Icon(
                                        painterResource(R.drawable.ic_meeting_lightbulb),
                                        contentDescription = null, modifier = Modifier.size(27.dp)
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column {
                                        Text(stringResource(R.string.room_id_help_title), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Spacer(Modifier.height(6.dp))
                                        Text(stringResource(R.string.room_id_help_body), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandTitle() {
    val wordmarkBlue = colorResource(R.color.brand_wordmark_blue)
    Text(
        buildAnnotatedString {
            append("Vexa")
            withStyle(SpanStyle(color = wordmarkBlue)) { append("Meet") }
        },
        fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun MeetingButton(label: String, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Button(
        onClick = onClick, enabled = !busy, shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFF287CFF), Color(0xFF2460EF))), shape)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_videocam), contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun OutlinedMeetingButton(label: String, icon: Int, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun OrDivider(compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 18.dp else 24.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Color(0xFF263346))
        Text(stringResource(R.string.meeting_or), Modifier.padding(horizontal = 18.dp), color = Muted, fontSize = 14.sp)
        HorizontalDivider(Modifier.weight(1f), color = Color(0xFF263346))
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomePreview() {
    MeetingEntryScreens(MeetingEntryScreen.HOME, "", null, false, {}, {}, {}, {}, {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun JoinPreview() {
    MeetingEntryScreens(MeetingEntryScreen.JOIN, "", null, false, {}, {}, {}, {}, {})
}
