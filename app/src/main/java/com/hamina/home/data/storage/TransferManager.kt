package com.hamina.home.data.storage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hamina.home.data.cache.CacheManager
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Runs copy, move and delete operations in the background, one after another, so the user can
 * keep browsing. The current progress is exposed as [state] for the bar at the top of the screen.
 */
class TransferManager(
    private val ops: StorageOps,
    private val cache: CacheManager
) {

    class ClipItem(val server: SmbServer, val item: MediaItem)

    class TransferState(
        val title: String,
        val detail: String,
        val doneBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val isBytes: Boolean,
        val queued: Int,
        val finished: Boolean = false,
        val error: String? = null
    ) {
        /** 0..1, or null while the total is not known yet. */
        val fraction: Float?
            get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    var state by mutableStateOf<TransferState?>(null)
        private set

    /** Called on the main thread when an operation ended, with the (serverId, folder) pairs it touched. */
    var onChanged: ((List<Pair<String, String>>) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var queued = 0
    private var currentJob: Job? = null
    private var token = 0

    private class Entry(val relative: String, val isDir: Boolean, val size: Long, val path: String)

    private inner class Run(val title: String, val isBytes: Boolean) {
        var detail = ""
        var total = 0L
        var done = 0L
        var summary = ""
        val affected = LinkedHashSet<Pair<String, String>>()
        private val startNs = System.nanoTime()
        private var lastNs = 0L

        fun publish(force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && now - lastNs < 150_000_000L) return
            lastNs = now
            val seconds = maxOf(0.001, (now - startNs) / 1_000_000_000.0)
            state = TransferState(
                title = title,
                detail = detail,
                doneBytes = done,
                totalBytes = total,
                bytesPerSecond = if (isBytes) (done / seconds).toLong() else 0L,
                isBytes = isBytes,
                queued = queued
            )
        }
    }

    // ---------- public API ----------

    fun copy(items: List<ClipItem>, destServer: SmbServer, destPath: String, move: Boolean) {
        if (items.isEmpty()) return
        val what = if (items.size == 1) "\"${items[0].item.name}\"" else "${items.size} items"
        enqueue((if (move) "Moving " else "Copying ") + what, true) { run ->
            doCopy(run, items, destServer, destPath, move)
        }
    }

    fun delete(items: List<ClipItem>) {
        if (items.isEmpty()) return
        val what = if (items.size == 1) "\"${items[0].item.name}\"" else "${items.size} items"
        enqueue("Deleting $what", false) { run ->
            run.total = items.size.toLong()
            for (clip in items) {
                coroutineContext.ensureActive()
                run.detail = clip.item.name
                run.publish(true)
                ops.delete(clip.server, clip.item.path, clip.item.isDirectory)
                run.done++
                run.affected.add(clip.server.id to parentOf(clip.item.path))
            }
            run.summary = "Deleted $what"
        }
    }

    fun cancel() {
        currentJob?.cancel()
    }

    // ---------- queue ----------

    private fun enqueue(title: String, isBytes: Boolean, work: suspend (Run) -> Unit) {
        queued++
        scope.launch {
            mutex.withLock {
                queued--
                currentJob = coroutineContext[Job]
                val run = Run(title, isBytes)
                token++
                run.publish(true)
                try {
                    work(run)
                    finish(run, null)
                } catch (e: CancellationException) {
                    finish(run, "Cancelled")
                    throw e
                } catch (e: Throwable) {
                    finish(run, e.message ?: "The operation failed")
                }
            }
        }
    }

    private suspend fun finish(run: Run, error: String?) {
        withContext(NonCancellable) {
            for ((serverId, path) in run.affected) cache.invalidateFolder(serverId, path)
            state = TransferState(
                title = if (error == null) run.summary.ifEmpty { "Done" } else run.title,
                detail = error ?: "",
                doneBytes = run.done,
                totalBytes = run.total,
                bytesPerSecond = 0L,
                isBytes = run.isBytes,
                queued = queued,
                finished = true,
                error = error
            )
            withContext(Dispatchers.Main) { onChanged?.invoke(run.affected.toList()) }
            val myToken = token
            scope.launch {
                delay(if (error == null) 3500 else 8000)
                if (token == myToken && queued == 0 && state?.finished == true) state = null
            }
        }
    }

    // ---------- copy / move ----------

    private suspend fun doCopy(
        run: Run,
        items: List<ClipItem>,
        destServer: SmbServer,
        destPath: String,
        move: Boolean
    ) {
        run.detail = "Preparing…"
        run.publish(true)
        val taken = ops.list(destServer, destPath).map { it.name.lowercase() }.toMutableSet()
        run.affected.add(destServer.id to destPath)

        class Plan(val clip: ClipItem, val targetName: String, val entries: List<Entry>)

        val plans = ArrayList<Plan>()
        for (clip in items) {
            coroutineContext.ensureActive()
            val item = clip.item
            val sameSource = clip.server.id == destServer.id
            val sameFolder = sameSource && parentOf(item.path) == destPath

            if (move && sameFolder) continue // already there
            if (sameSource && item.isDirectory &&
                (destPath == item.path || destPath.startsWith(item.path + "/"))
            ) {
                throw IOException("A folder cannot be copied into itself")
            }

            val targetName = uniqueName(item.name, taken)
            taken.add(targetName.lowercase())

            if (move && sameSource) {
                // Same source: a rename is instant. Fall back to copy + delete if it is refused.
                try {
                    run.detail = item.name
                    run.publish(true)
                    ops.move(clip.server, item.path, ops.join(destPath, targetName))
                    run.affected.add(clip.server.id to parentOf(item.path))
                    continue
                } catch (_: Exception) {
                    // fall through to the slow path
                }
            }
            val entries = expand(clip.server, item)
            run.total += entries.sumOf { it.size }
            plans.add(Plan(clip, targetName, entries))
        }
        run.publish(true)

        for (plan in plans) {
            val item = plan.clip.item
            for (entry in plan.entries) {
                coroutineContext.ensureActive()
                val destRelative = plan.targetName + entry.relative.removePrefix(item.name)
                val destFull = ops.join(destPath, destRelative)
                run.detail = entry.relative
                run.publish(true)
                if (entry.isDir) {
                    ops.mkdir(destServer, destFull)
                } else {
                    copyFile(run, plan.clip.server, entry.path, destServer, destFull)
                }
            }
            if (move) {
                ops.delete(plan.clip.server, item.path, item.isDirectory)
                run.affected.add(plan.clip.server.id to parentOf(item.path))
            }
        }
        run.summary = (if (move) "Moved " else "Copied ") +
            (if (items.size == 1) "\"${items[0].item.name}\"" else "${items.size} items")
    }

    private suspend fun copyFile(
        run: Run,
        srcServer: SmbServer,
        srcPath: String,
        destServer: SmbServer,
        destPath: String
    ) {
        val input = ops.openRead(srcServer, srcPath)
        var out: OutputStream? = null
        try {
            out = ops.openWrite(destServer, destPath)
            val buffer = ByteArray(512 * 1024)
            var position = 0L
            val size = input.size
            while (position < size) {
                coroutineContext.ensureActive()
                val want = minOf(buffer.size.toLong(), size - position).toInt()
                val n = input.read(position, buffer, 0, want)
                if (n <= 0) throw IOException("Unexpected end of $srcPath")
                out.write(buffer, 0, n)
                position += n
                run.done += n
                run.publish()
            }
            out.close()
            out = null
        } catch (e: Throwable) {
            try {
                out?.close()
            } catch (_: Exception) {
            }
            // Do not leave a half-written file behind.
            try {
                ops.delete(destServer, destPath, false)
            } catch (_: Exception) {
            }
            throw e
        } finally {
            input.close()
        }
    }

    private fun expand(server: SmbServer, item: MediaItem): List<Entry> {
        val out = ArrayList<Entry>()
        fun walk(path: String, relative: String) {
            out.add(Entry(relative, true, 0L, path))
            for (child in ops.list(server, path)) {
                val childRelative = "$relative/${child.name}"
                if (child.isDirectory) {
                    walk(child.path, childRelative)
                } else {
                    out.add(Entry(childRelative, false, child.size, child.path))
                }
            }
        }
        if (item.isDirectory) {
            walk(item.path, item.name)
        } else {
            out.add(Entry(item.name, false, item.size, item.path))
        }
        return out
    }

    private fun uniqueName(name: String, taken: Set<String>): String {
        if (!taken.contains(name.lowercase())) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        var candidate = "$base ($n)$ext"
        while (taken.contains(candidate.lowercase())) {
            n++
            candidate = "$base ($n)$ext"
        }
        return candidate
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")
}
