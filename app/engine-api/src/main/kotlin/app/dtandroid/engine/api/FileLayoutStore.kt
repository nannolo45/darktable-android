// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * [LayoutStore] in one app-private JSON file: the user's layouts, the current
 * layout's name and the workflow. Built-in layouts are not stored: they come from
 * [builtins] for the current workflow, as darktable rebuilds them at every start.
 * Writes go to a temporary file first, then replace the file.
 */
class FileLayoutStore(
    private val file: File,
    /** built-in layouts (name, params) for a workflow, in darktable's order */
    private val builtins: (workflow: String) -> List<Pair<String, String>>,
    private val defaultWorkflow: String,
    override val fallbackName: String,
) : LayoutStore {

    private val lock = Mutex()

    private data class Data(val workflow: String, val current: String, val user: Map<String, String>)

    private fun load(): Data {
        if (!file.isFile) return Data(defaultWorkflow, "", emptyMap())
        val o = try { Json.parseToJsonElement(file.readText()).jsonObject } catch (e: Exception) {
            throw LayoutStoreException("layout file ${file.name} is damaged: ${e.message}")
        }
        val user = LinkedHashMap<String, String>()
        (o["layouts"] as? JsonArray)?.forEach {
            val l = it.jsonObject
            user[l["name"]!!.jsonPrimitive.content] = l["params"]!!.jsonPrimitive.content
        }
        return Data(
            (o["workflow"] as? JsonPrimitive)?.content ?: defaultWorkflow,
            (o["current"] as? JsonPrimitive)?.content ?: "",
            user,
        )
    }

    private fun save(d: Data) {
        val o = buildJsonObject {
            put("format", 1)
            put("workflow", d.workflow)
            put("current", d.current)
            put("layouts", JsonArray(d.user.entries.sortedBy { it.key }.map { (n, p) ->
                buildJsonObject { put("name", n); put("params", p) }
            }))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw LayoutStoreException("cannot write ${file.name}")
        }
    }

    private fun builtinNames(d: Data) = builtins(d.workflow).map { it.first }.toSet()

    /** SQLite's default ordering of text: by bytes (UTF-8). */
    private val byBytes = Comparator<String> { a, b ->
        val x = a.encodeToByteArray(); val y = b.encodeToByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (c != 0) return@Comparator c
        }
        x.size - y.size
    }

    private fun listOf(d: Data): List<StoredLayout> {
        val b = builtins(d.workflow).map { StoredLayout(it.first, it.second, true) }.sortedWith(compareBy(byBytes) { it.name })
        val names = b.map { it.name }.toSet()
        val u = d.user.filterKeys { it !in names }.map { StoredLayout(it.key, it.value, false) }.sortedWith(compareBy(byBytes) { it.name })
        return b + u
    }

    override suspend fun list(): List<StoredLayout> = lock.withLock { listOf(load()) }

    override suspend fun read(name: String): StoredLayout? = lock.withLock { listOf(load()).firstOrNull { it.name == name } }

    override suspend fun write(name: String, params: String) = lock.withLock {
        val d = load()
        if (name.isEmpty()) throw LayoutStoreException("a layout needs a name")
        if (name in builtinNames(d)) throw LayoutStoreException("\"$name\" is a built-in layout and cannot be changed")
        save(d.copy(user = d.user + (name to params)))
    }

    override suspend fun delete(name: String) = lock.withLock {
        val d = load()
        if (name in builtinNames(d)) throw LayoutStoreException("\"$name\" is a built-in layout and cannot be deleted")
        if (name !in d.user) return@withLock
        save(d.copy(user = d.user - name, current = if (d.current == name) fallbackName else d.current))
    }

    override suspend fun rename(from: String, to: String) = lock.withLock {
        val d = load()
        val b = builtinNames(d)
        if (from in b) throw LayoutStoreException("\"$from\" is a built-in layout and cannot be renamed")
        if (to.isEmpty()) throw LayoutStoreException("a layout needs a name")
        if (to in b || (to in d.user && to != from)) throw LayoutStoreException("a layout named \"$to\" already exists")
        val p = d.user[from] ?: throw LayoutStoreException("no layout named \"$from\"")
        save(d.copy(user = (d.user - from) + (to to p), current = if (d.current == from) to else d.current))
    }

    override suspend fun current(): String = lock.withLock {
        val d = load()
        if (listOf(d).any { it.name == d.current }) d.current else fallbackName
    }

    override suspend fun setCurrent(name: String) = lock.withLock {
        val d = load()
        if (listOf(d).none { it.name == name }) throw LayoutStoreException("no layout named \"$name\"")
        save(d.copy(current = name))
    }

    override suspend fun workflow(): String = lock.withLock { load().workflow }

    override suspend fun setWorkflow(workflow: String) = lock.withLock { save(load().copy(workflow = workflow)) }
}
