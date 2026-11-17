package com.example.secretgallery

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.content.ContentUris
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var vault: VaultCrypto
    private lateinit var root: LinearLayout
    private var pendingDeleteUris: List<Uri> = emptyList()
    private var pendingDeleteName = ""

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        importSelected(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = VaultCrypto(this)
        showGate()
    }

    private fun base(title: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 34, 24, 24)
            setBackgroundColor(0xFF0B1020.toInt())
        }
        val heading = TextView(this).apply {
            text = title
            textSize = 25f
            setTextColor(0xFFF0F3FF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 18)
        }
        root.addView(heading)
        setContentView(root)
    }

    private fun label(text: String, size: Float = 14f) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFFB9C5DE.toInt())
            setPadding(0, 8, 0, 8)
        })
    }

    private fun button(text: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 7; bottomMargin = 7 })
    }

    private fun pinInput(hint: String): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            inputType =  android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF77839B.toInt())
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        return input
    }

    private fun showGate() {
        base("SECRET GALLERY")
        label("PRIVATE PHOTO & VIDEO VAULT")
        label(if (vault.isSetup()) "Enter your PIN to unlock your encrypted vault." else "Create an 8–12 digit PIN. Do not forget it.")
        val pin = pinInput("8–12 digit PIN")
        if (!vault.isSetup()) {
            button("Create vault") {
                try {
                    vault.create(pin.text.toString())
                    showVault()
                } catch (e: Exception) { toast(e.message ?: "Could not create vault") }
            }
        } else {
            button("Unlock vault") {
                if (vault.unlock(pin.text.toString())) showVault()
                else toast("Incorrect PIN or vault data unavailable.")
            }
        }
        label("Security: photos are encrypted with AES-256-GCM and stored inside this app's private storage.")
        label("Important: an 8-digit PIN is still weaker than a long password. Keep your phone protected and never forget the PIN.")
    }

    private fun showVault() {
        base("My Private Vault")
        val items = try { vault.loadItems() } catch (e: Exception) {
            toast(e.message ?: "Could not read vault")
            showGate()
            return
        }
        label("${items.size} private item(s)")
        button("＋ Import photos / videos") {
            pickFiles.launch(arrayOf("image/*", "video/*"))
        }
        button("Lock vault") {
            vault.lock()
            showGate()
        }
        if (items.isEmpty()) label("Your vault is empty. Import photos or videos to get started.")
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 15, 0, 15)
            }
            row.addView(TextView(this).apply {
                text = "${item.name}\n${item.mime}"
                textSize = 14f
                setTextColor(0xFFE6ECFA.toInt())
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val restore = Button(this).apply {
                text = "Restore"
                setOnClickListener { restoreItem(item) }
            }
            val delete = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete permanently?")
                        .setMessage("This removes the encrypted copy from Secret Gallery.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            vault.deleteFile(item.id)
                            showVault()
                        }.show()
                }
            }
            actions.addView(restore, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(delete, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(actions)
            root.addView(row)
        }
        label("Importing does not remove the original until Android displays its deletion approval and you approve it.")
    }

    private fun importSelected(uris: List<Uri>) {
        var imported = 0
        val deleteUris = mutableListOf<Uri>()
        for (uri in uris) {
            try {
                val name = displayName(uri) ?: "Private item"
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
                val id = UUID.randomUUID().toString()
                val item = VaultItem(id, name, mime, System.currentTimeMillis())
                val input = contentResolver.openInputStream(uri) ?: continue
                input.use { vault.importStream(it, item) }
                vault.addItem(item)
                imported++
                // Resolve the document-picker URI to its underlying MediaStore URI when possible.
                mediaStoreUri(uri, mime)?.let { deleteUris.add(it) }
            } catch (e: Exception) {
                toast("An item could not be imported: ${e.message ?: "unknown error"}")
            }
        }
        showVault()
        if (imported == 0) {
            toast("No items were imported.")
            return
        }
        if (deleteUris.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
            try {
                val request = MediaStore.createDeleteRequest(contentResolver, deleteUris)
                pendingDeleteUris = deleteUris
                startIntentSenderForResult(request.intentSender, 4102, null, 0, 0, 0)
            } catch (e: Exception) {
                toast("$imported item(s) encrypted. Android deletion approval could not be opened; originals remain in Gallery.")
            }
        } else {
            toast("$imported item(s) encrypted. These source files cannot be deleted through this provider; originals remain.")
        }
    }


    private fun mediaStoreUri(uri: Uri, mime: String): Uri? {
        return try {
            if (uri.authority == "media") return uri
            if (uri.authority != "com.android.providers.media.documents") return null
            val documentId = DocumentsContract.getDocumentId(uri)
            val parts = documentId.split(":")
            if (parts.size != 2) return null
            val id = parts[1].toLongOrNull() ?: return null
            val collection = if (mime.startsWith("video/"))
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ContentUris.withAppendedId(collection, id)
        } catch (_: Exception) {
            null
        }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }

    @Deprecated("Handled for Android's MediaStore delete confirmation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4102) {
            if (resultCode == Activity.RESULT_OK) {
                toast("Android approved deletion. Imported originals should disappear from shared Gallery.")
            } else {
                toast("Deletion was not approved. Encrypted copies are safe, but originals remain in Gallery.")
            }
            pendingDeleteUris = emptyList()
        }
    }

    private fun restoreItem(item: VaultItem) {
        try {
            val collection = if (item.mime.startsWith("video/"))
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (item.mime.startsWith("video/")) "Movies/Restored from Secret Gallery"
                    else "Pictures/Restored from Secret Gallery")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collection, values) ?: error("Could not create gallery item.")
            contentResolver.openOutputStream(uri)?.use { vault.decryptTo(item.id, it) }
                ?: error("Could not write restored file.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            toast("Restored to your gallery.")
        } catch (e: Exception) {
            toast("Restore failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
