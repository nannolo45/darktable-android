// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Brief P3l: the server side of R11 (PLAN.md 4.5) as the stub LPL keeps it, written from the contract alone (LPL's own
 * implementation is built at the same time on its branch `shared-r11`). [StubLpl] routes the HTTP calls here when its
 * `r11` switch is on; tests also call [put], [delete] and [resolve] directly to play another device (a PC).
 */
class StubShared(private val deviceName: (String) -> String) {
    class Item(
        val id: Long, val kind: String, val operation: String, val opVersion: Int, var name: String,
        var rev: Int, var seq: Long, var hash: String, var changedAt: Double, var deviceId: String, var body: JsonObject,
        var deleted: Boolean = false,
    )

    class Candidate(val agent: String, val itemId: Long, val hash: String?, val changedAt: Double, val body: JsonObject?)

    val items = ArrayList<Item>()
    private var seq = 0L
    private var nextId = 1L
    /** conflict candidates per agent and item */
    val candidates = LinkedHashMap<Pair<String, Long>, Candidate>()
    /** the last `PUT /api/shared/state` per agent */
    val states = LinkedHashMap<String, JsonObject>()
    /** a PC's policy is LPL's setting; the phone's comes with its state */
    val policies = LinkedHashMap<String, String>()
    /** every call in order: "PUT name", "DELETE name", "RESOLVE name choice", "STATE agent" */
    val log = ArrayList<String>()

    private fun meta(i: Item) = buildJsonObject {
        put("id", i.id); put("kind", i.kind); put("operation", i.operation); put("op_version", i.opVersion); put("name", i.name)
        put("rev", i.rev); put("seq", i.seq); put("hash", i.hash); put("changed_at", i.changedAt); put("device_id", i.deviceId)
        put("device_name", deviceName(i.deviceId)); put("deleted", i.deleted); put("size", i.body.toString().toByteArray().size)
    }

    private fun bump(i: Item) { i.rev++; i.seq = ++seq }

    @Synchronized fun item(id: Long) = items.firstOrNull { it.id == id }
    @Synchronized fun itemNamed(name: String) = items.firstOrNull { it.name == name }

    @Synchronized
    fun list(agent: String, since: Long): JsonObject = buildJsonObject {
        put("seq", seq)
        put("items", JsonArray(items.filter { it.seq > since }.map { meta(it) }))
        put("conflicts", JsonArray(candidates.values.filter { it.agent == agent }.map { c ->
            buildJsonObject { put("item_id", c.itemId); put("changed_at", c.changedAt); put("deleted", c.hash == null) }
        }))
    }

    @Synchronized
    fun get(id: Long): JsonObject? = item(id)?.let { i -> JsonObject(meta(i) + ("body" to i.body)) }

    private fun stored(i: Item, decided: String?) = 200 to buildJsonObject {
        put("id", i.id); put("rev", i.rev); put("seq", i.seq); put("decided", decided?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun conflict(i: Item) = 409 to buildJsonObject { put("error", "changed_elsewhere"); put("item", meta(i)) }

    private fun keyOf(kind: String, op: String, v: Int, name: String) =
        items.firstOrNull { it.kind == kind && it.name == name && (kind == "style" || (it.operation == op && it.opVersion == v)) }

    @Synchronized
    fun put(agent: String, req: JsonObject): Pair<Int, JsonObject> {
        val kind = (req["kind"] as JsonPrimitive).content
        val op = (req["operation"] as? JsonPrimitive)?.content ?: ""
        val v = (req["op_version"] as? JsonPrimitive)?.content?.toInt() ?: 0
        val name = (req["name"] as JsonPrimitive).content
        val hash = (req["hash"] as JsonPrimitive).content
        val at = (req["changed_at"] as JsonPrimitive).content.toDouble()
        val base = (req["base_rev"] as JsonPrimitive).content.toInt()
        val body = req["body"] as JsonObject
        log += "PUT $name"
        // LPL recomputes the hash from the body
        if (SharedHash.hash(body) != hash) return 400 to buildJsonObject { put("error", "hash_mismatch") }
        val i = keyOf(kind, op, v, name)
        if (i == null) {
            val n = Item(nextId++, kind, op, v, name, 1, ++seq, hash, at, agent, body)
            items += n
            return stored(n, null)
        }
        // a deleted item comes back only from base_rev = the deletion's rev
        if (base == i.rev || (hash == i.hash && !i.deleted)) {
            if (hash == i.hash && !i.deleted) return stored(i, null)
            i.hash = hash; i.body = body; i.changedAt = at; i.deviceId = agent; i.deleted = false; bump(i)
            candidates.remove(agent to i.id)
            return stored(i, null)
        }
        if (policies[agent] == "newest") {
            if (at > i.changedAt) {
                i.hash = hash; i.body = body; i.changedAt = at; i.deviceId = agent; i.deleted = false; bump(i)
                return stored(i, "mine")
            }
            return stored(i, "theirs")
        }
        candidates[agent to i.id] = Candidate(agent, i.id, hash, at, body)
        return conflict(i)
    }

    @Synchronized
    fun delete(agent: String, id: Long, baseRev: Int, at: Double = System.currentTimeMillis() / 1000.0): Pair<Int, JsonObject> {
        val i = item(id) ?: return 404 to buildJsonObject { put("detail", "no such item") }
        log += "DELETE ${i.name}"
        if (baseRev == i.rev) {
            i.deleted = true; i.changedAt = at; i.deviceId = agent; bump(i)
            candidates.remove(agent to i.id)
            return stored(i, null)
        }
        if (policies[agent] == "newest") {
            if (at > i.changedAt) { i.deleted = true; i.changedAt = at; i.deviceId = agent; bump(i); return stored(i, "mine") }
            return stored(i, "theirs")
        }
        candidates[agent to i.id] = Candidate(agent, i.id, null, at, null)
        return conflict(i)
    }

    @Synchronized
    fun resolve(id: Long, agent: String, choice: String): Pair<Int, JsonObject> {
        val i = item(id) ?: return 404 to buildJsonObject { put("detail", "no such item") }
        val c = candidates.remove(agent to id) ?: return 404 to buildJsonObject { put("detail", "no open conflict") }
        log += "RESOLVE ${i.name} $choice"
        val changed = ArrayList<Item>()
        when (choice) {
            "theirs" -> {}
            "mine", "both" -> {
                if (choice == "both") {
                    if (c.hash == null || i.deleted) return 400 to buildJsonObject { put("detail", "keep both is not for a deletion") }
                    // a name already taken gets " 2", " 3", ...
                    val base = "${i.name} (${deviceName(i.deviceId)})"
                    var copyName = base
                    var k = 2
                    while (items.any { it.kind == i.kind && it.name == copyName && it.operation == i.operation && it.opVersion == i.opVersion }) copyName = "$base ${k++}"
                    val copyBody = renamed(i.body, copyName)
                    val n = Item(nextId++, i.kind, i.operation, i.opVersion, copyName, 1, ++seq, SharedHash.hash(copyBody)!!, i.changedAt, i.deviceId, copyBody)
                    items += n
                    changed += n
                }
                if (c.hash == null) { i.deleted = true } else { i.hash = c.hash; i.body = c.body!!; i.deleted = false }
                i.changedAt = c.changedAt; i.deviceId = agent; bump(i)
                changed += i
            }
            else -> return 400 to buildJsonObject { put("detail", "bad choice") }
        }
        return 200 to buildJsonObject { put("items", JsonArray(changed.map { meta(it) })) }
    }

    @Synchronized
    fun state(agent: String, o: JsonObject) {
        states[agent] = o
        (o["policy"] as? JsonPrimitive)?.content?.let { policies[agent] = it }
        log += "STATE $agent"
    }

    companion object {
        /** A body under another name (what "keep both" stores for the previous content). */
        fun renamed(body: JsonObject, name: String): JsonObject {
            val k = if ((body["kind"] as JsonPrimitive).content == "style") "style" else "row"
            val part = body[k] as JsonObject
            return JsonObject(body + (k to JsonObject(part + ("name" to JsonPrimitive(name)))))
        }
    }
}
