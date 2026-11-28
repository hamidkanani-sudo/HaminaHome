package com.hamina.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hamina.home.data.model.MediaItem

/** Shows whichever dialog the view model asks for. Each one is its own window, so focus stays inside. */
@Composable
fun BrowserDialogs(vm: BrowserViewModel) {
    when (val d = vm.dialog) {
        null -> Unit
        is BrowserDialog.Actions -> ActionsDialog(vm)
        is BrowserDialog.Rename -> TextInputDialog(
            title = "Rename",
            initial = d.item.name,
            confirmLabel = "Rename",
            selectBaseNameOnly = !d.item.isDirectory,
            onConfirm = { vm.rename(d.item, it) },
            onDismiss = { vm.dismissDialog() }
        )
        is BrowserDialog.NewFolder -> TextInputDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            selectBaseNameOnly = false,
            onConfirm = { vm.createFolder(it) },
            onDismiss = { vm.dismissDialog() }
        )
        is BrowserDialog.ConfirmDelete -> ConfirmDeleteDialog(
            items = d.items,
            onConfirm = { vm.confirmDelete(d.items) },
            onDismiss = { vm.dismissDialog() }
        )
        is BrowserDialog.Message -> MessageDialog(d.text) { vm.dismissDialog() }
    }
}

@Composable
private fun DialogShell(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(460.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0F151D))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    color = HaminaColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                content()
            }
        }
    }
}

@Composable
private fun DialogRow(
    icon: ImageVector,
    label: String,
    tint: Color = HaminaColors.Accent,
    requester: FocusRequester? = null,
    onClick: () -> Unit
) {
    FocusSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        focusRequester = requester,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        onClick = onClick
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Text(text = label, color = HaminaColors.TextPrimary, fontSize = 16.sp)
        }
    }
}

@Composable
private fun FocusFirst(requester: FocusRequester) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        try {
            requester.requestFocus()
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun ActionsDialog(vm: BrowserViewModel) {
    val first = remember { FocusRequester() }
    FocusFirst(first)

    val targets = vm.targetItems()
    val selected = vm.selectedItem
    val title = when {
        vm.marked.isNotEmpty() -> "${vm.marked.size} selected"
        selected != null -> selected.name
        else -> "Actions"
    }

    DialogShell(title = title, onDismiss = { vm.dismissDialog() }) {
        var firstRow = true
        fun next(): FocusRequester? = if (firstRow) {
            firstRow = false
            first
        } else {
            null
        }

        if (targets.isNotEmpty()) {
            DialogRow(Icons.Rounded.ContentCopy, "Copy", requester = next()) { vm.copyToClipboard(false) }
            DialogRow(Icons.Rounded.ContentCut, "Cut (move)", requester = next()) { vm.copyToClipboard(true) }
        }
        if (vm.canPasteHere()) {
            val verb = if (vm.clipboardMove) "Move" else "Paste"
            DialogRow(
                Icons.Rounded.ContentPaste,
                "$verb here  (${vm.clipboard.size})",
                tint = HaminaColors.Success,
                requester = next()
            ) { vm.paste() }
        }
        if (selected != null) {
            val isMarked = vm.marked.contains(selected.id)
            DialogRow(
                Icons.Rounded.CheckCircle,
                if (isMarked) "Unmark" else "Mark (select several)",
                requester = next()
            ) { vm.toggleMark() }
        }
        if (targets.size == 1) {
            DialogRow(Icons.Rounded.DriveFileRenameOutline, "Rename", requester = next()) { vm.askRename() }
        }
        if (targets.isNotEmpty()) {
            DialogRow(Icons.Rounded.Delete, "Delete", tint = HaminaColors.Danger, requester = next()) {
                vm.askDelete()
            }
        }
        DialogRow(Icons.Rounded.CreateNewFolder, "New folder", requester = next()) { vm.askNewFolder() }
        if (vm.marked.isNotEmpty()) {
            DialogRow(Icons.Rounded.Close, "Clear selection", tint = HaminaColors.TextSecondary, requester = next()) {
                vm.clearMarks()
            }
        }
        if (vm.clipboard.isNotEmpty()) {
            DialogRow(Icons.Rounded.Close, "Clear clipboard", tint = HaminaColors.TextSecondary, requester = next()) {
                vm.clearClipboard()
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(items: List<MediaItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    FocusFirst(cancel)
    val title = if (items.size == 1) "Delete \"${items[0].name}\"?" else "Delete ${items.size} items?"
    DialogShell(title = title, onDismiss = onDismiss) {
        val preview = items.take(4).joinToString("\n") { it.name }
        Text(
            text = preview + if (items.size > 4) "\n…" else "",
            color = HaminaColors.TextSecondary,
            fontSize = 14.sp,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "This cannot be undone. Folders are deleted with everything inside.",
            color = HaminaColors.Danger,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        DialogRow(Icons.Rounded.Close, "Cancel", tint = HaminaColors.TextSecondary, requester = cancel) { onDismiss() }
        DialogRow(Icons.Rounded.Delete, "Delete", tint = HaminaColors.Danger) { onConfirm() }
    }
}

@Composable
private fun MessageDialog(text: String, onDismiss: () -> Unit) {
    val ok = remember { FocusRequester() }
    FocusFirst(ok)
    DialogShell(title = "Hamina Home", onDismiss = onDismiss) {
        Text(text = text, color = HaminaColors.TextPrimary, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        DialogRow(Icons.Rounded.CheckCircle, "OK", requester = ok) { onDismiss() }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    selectBaseNameOnly: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dot = initial.lastIndexOf('.')
    val selectionEnd = if (selectBaseNameOnly && dot > 0) dot else initial.length
    var value by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, selectionEnd)))
    }
    val fieldFocus = remember { FocusRequester() }
    FocusFirst(fieldFocus)

    DialogShell(title = title, onDismiss = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(HaminaColors.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = HaminaColors.TextPrimary, fontSize = 18.sp),
                cursorBrush = SolidColor(HaminaColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (value.text.isNotBlank()) onConfirm(value.text)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
            )
        }
        Text(
            text = "Press OK on the field to open the keyboard",
            color = HaminaColors.TextSecondary,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(4.dp))
        DialogRow(Icons.Rounded.CheckCircle, confirmLabel, tint = HaminaColors.Success) {
            if (value.text.isNotBlank()) onConfirm(value.text)
        }
        DialogRow(Icons.Rounded.Close, "Cancel", tint = HaminaColors.TextSecondary) { onDismiss() }
    }
}
