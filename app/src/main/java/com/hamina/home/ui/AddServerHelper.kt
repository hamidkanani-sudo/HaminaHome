package com.hamina.home.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.hamina.home.HaminaApp
import com.hamina.home.data.model.SmbServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Simple dialog-based server adder for first-time setup.
 * Can later be replaced with a full Compose form.
 */
object AddServerHelper {

    fun showAddDialog(context: Context, onAdded: () -> Unit) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        fun field(hint: String, default: String = ""): EditText {
            return EditText(context).apply {
                this.hint = hint
                setText(default)
                setSingleLine()
                layout.addView(this)
            }
        }

        val name = field("Display name", "My PC")
        val host = field("IP or hostname", "192.168.1.")
        val share = field("Share name", "Videos")
        val user = field("Username (optional)")
        val pass = field("Password (optional)")

        AlertDialog.Builder(context)
            .setTitle("Add SMB Server")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val server = SmbServer(
                    id = UUID.randomUUID().toString(),
                    name = name.text.toString().ifBlank { "Server" },
                    host = host.text.toString().trim(),
                    share = share.text.toString().trim(),
                    username = user.text.toString().trim(),
                    password = pass.text.toString()
                )
                if (server.host.isBlank() || server.share.isBlank()) {
                    Toast.makeText(context, "Host and Share are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                CoroutineScope(Dispatchers.Main).launch {
                    HaminaApp.instance.serverRepository.addServer(server)
                    Toast.makeText(context, "Server added", Toast.LENGTH_SHORT).show()
                    onAdded()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
