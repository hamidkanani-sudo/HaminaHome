package com.hamina.home.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamina.home.HaminaApp
import com.hamina.home.data.model.SmbServer
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Full-screen, D-pad friendly form for adding / editing an SMB server.
 * Replaces AlertDialog + EditText which only focuses the first field on Android TV.
 */
@Composable
fun AddServerScreen(
    existing: SmbServer? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: "My PC") }
    var host by remember { mutableStateOf(existing?.host ?: "192.168.1.") }
    var share by remember { mutableStateOf(existing?.share.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var domain by remember { mutableStateOf(existing?.domain.orEmpty()) }
    var port by remember { mutableStateOf((existing?.port ?: 445).toString()) }

    val focusName = remember { FocusRequester() }
    val focusHost = remember { FocusRequester() }
    val focusShare = remember { FocusRequester() }
    val focusUser = remember { FocusRequester() }
    val focusPass = remember { FocusRequester() }
    val focusDomain = remember { FocusRequester() }
    val focusPort = remember { FocusRequester() }
    val focusSave = remember { FocusRequester() }
    val focusCancel = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusName.requestFocus()
    }

    BackHandler { onDismiss() }

    fun save() {
        val hostValue = host.trim()
        val shareValue = share.trim() // optional: empty = list all shares from root
        if (hostValue.isBlank()) {
            Toast.makeText(context, "IP / hostname is required", Toast.LENGTH_SHORT).show()
            return
        }
        val portValue = port.trim().toIntOrNull() ?: 445
        val server = SmbServer(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Server" },
            host = hostValue,
            share = shareValue,
            username = username.trim(),
            password = password,
            domain = domain.trim(),
            port = portValue,
            isFavorite = existing?.isFavorite ?: false
        )
        scope.launch {
            if (existing == null) {
                HaminaApp.instance.serverRepository.addServer(server)
            } else {
                HaminaApp.instance.serverRepository.updateServer(server)
            }
            Toast.makeText(
                context,
                if (existing == null) "Server added" else "Server updated",
                Toast.LENGTH_SHORT
            ).show()
            onSaved()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(32.dp)
    ) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .padding(end = 24.dp)
        ) {
            Text(
                text = if (existing == null) "Add SMB Server" else "Edit SMB Server",
                color = Color(0xFF42A5F5),
                fontSize = 26.sp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "D-pad: move between fields\nCenter: edit / confirm\nBack: cancel",
                color = Color(0xFFAAAAAA),
                fontSize = 14.sp
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            TvTextField(
                label = "Display name",
                value = name,
                onValueChange = { name = it },
                focusRequester = focusName
            )
            Spacer(Modifier.height(14.dp))
            TvTextField(
                label = "IP or hostname *",
                value = host,
                onValueChange = { host = it },
                focusRequester = focusHost,
                keyboardType = KeyboardType.Uri
            )
            Spacer(Modifier.height(14.dp))
            TvTextField(
                label = "Share name (optional – leave empty for all shares)",
                value = share,
                onValueChange = { share = it },
                focusRequester = focusShare
                // empty share → browse all shares on server
            )
            Spacer(Modifier.height(14.dp))
            TvTextField(
                label = "Username (optional)",
                value = username,
                onValueChange = { username = it },
                focusRequester = focusUser
            )
            Spacer(Modifier.height(14.dp))
            TvTextField(
                label = "Password (optional)",
                value = password,
                onValueChange = { password = it },
                focusRequester = focusPass,
                isPassword = true
            )
            Spacer(Modifier.height(14.dp))
            TvTextField(
                label = "Domain (optional)",
                value = domain,
                onValueChange = { domain = it },
                focusRequester = focusDomain
            )
            Spacer(Modifier.height(14.dp))
            TvTextField(
                label = "Port",
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                focusRequester = focusPort,
                keyboardType = KeyboardType.Number
            )

            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvActionButton(
                    text = "Save",
                    focusRequester = focusSave,
                    primary = true,
                    onClick = { save() }
                )
                TvActionButton(
                    text = "Cancel",
                    focusRequester = focusCancel,
                    primary = false,
                    onClick = onDismiss
                )
                if (existing != null) {
                    val focusDelete = remember { FocusRequester() }
                    TvActionButton(
                        text = "Delete",
                        focusRequester = focusDelete,
                        primary = false,
                        onClick = {
                            scope.launch {
                                HaminaApp.instance.smbManager.evict(existing.id)
                                HaminaApp.instance.serverRepository.deleteServer(existing.id)
                                Toast.makeText(context, "Connection deleted", Toast.LENGTH_SHORT).show()
                                onSaved()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color(0xFF42A5F5) else Color(0xFF333333)
    val bg = if (focused) Color(0xFF1A2332) else Color(0xFF161616)

    Column {
        Text(text = label, color = Color(0xFF90CAF9), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            cursorBrush = SolidColor(Color(0xFF42A5F5)),
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .background(bg)
                .border(2.dp, borderColor)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(text = label, color = Color(0xFF555555), fontSize = 18.sp)
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
private fun TvActionButton(
    text: String,
    focusRequester: FocusRequester,
    primary: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color(0xFF1565C0)
        primary -> Color(0xFF0D47A1)
        else -> Color(0xFF2A2A2A)
    }
    val borderColor = if (focused) Color(0xFF42A5F5) else Color.Transparent

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick)
            .background(bg)
            .border(2.dp, borderColor)
            .padding(horizontal = 28.dp, vertical = 14.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 17.sp)
    }
}
