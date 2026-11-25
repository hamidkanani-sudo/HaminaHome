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

/** Dialogs for creating, editing and deleting saved SMB connections. */
object AddServerHelper {

    fun showAddDialog(context: Context, onAdded: () -> Unit) = showEditor(context, null, onAdded)

    fun showServerOptions(context: Context, server: SmbServer, onChanged: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(server.name)
            .setItems(arrayOf("Edit connection", "Delete connection")) { _, which ->
                when (which) {
                    0 -> showEditor(context, server, onChanged)
                    1 -> AlertDialog.Builder(context)
                        .setTitle("Delete ${server.name}?")
                        .setMessage("This only removes the saved connection. Files on the server will not be deleted.")
                        .setPositiveButton("Delete") { _, _ ->
                            CoroutineScope(Dispatchers.Main).launch {
                                HaminaApp.instance.serverRepository.deleteServer(server.id)
                                Toast.makeText(context, "Connection deleted", Toast.LENGTH_SHORT).show()
                                onChanged()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditor(context: Context, existing: SmbServer?, onSaved: () -> Unit) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        fun field(hint: String, value: String = ""): EditText = EditText(context).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            layout.addView(this)
        }

        val name = field("Display name", existing?.name ?: "My PC")
        val host = field("IP or hostname", existing?.host ?: "192.168.1.")
        val share = field("Share name", existing?.share ?: "Videos")
        val user = field("Username (optional)", existing?.username.orEmpty())
        val pass = field("Password (optional)", existing?.password.orEmpty())
        val domain = field("Domain (optional)", existing?.domain.orEmpty())

        AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Add SMB Server" else "Edit SMB Server")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val hostValue = host.text.toString().trim()
                val shareValue = share.text.toString().trim()
                if (hostValue.isBlank() || shareValue.isBlank()) {
                    Toast.makeText(context, "Host and Share are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val server = SmbServer(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name.text.toString().trim().ifBlank { "Server" },
                    host = hostValue,
                    share = shareValue,
                    username = user.text.toString().trim(),
                    password = pass.text.toString(),
                    domain = domain.text.toString().trim(),
                    port = existing?.port ?: 445,
                    isFavorite = existing?.isFavorite ?: false
                )
                CoroutineScope(Dispatchers.Main).launch {
                    if (existing == null) HaminaApp.instance.serverRepository.addServer(server)
                    else HaminaApp.instance.serverRepository.updateServer(server)
                    Toast.makeText(context, if (existing == null) "Server added" else "Server updated", Toast.LENGTH_SHORT).show()
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
