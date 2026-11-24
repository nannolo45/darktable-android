// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * Brief P3l, request R11 (PLAN.md 4.5): styles, presets and module layouts shared through LightPhotoLibrary.
 * This file: the item model and the server calls. SharedSync.kt: what the phone keeps and the rules.
 */

/** The kinds that travel, with their `kind` value. */
enum class SharedKind(val json: String) {
    STYLE("style"), PRESET("preset"), LAYOUT("layout");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.json == s }
    }
}

/** An item's key (4.5 "Item key"): kind + operation + op_version + name; a style has operation "" and op_version 0. */
data class SharedKey(val kind: SharedKind, val operation: String, val opVersion: Int, val name: String) {
    companion object {
        /** The key a body carries; null for a body that is not one of the three kinds or has no name. */
        fun of(body: JsonObject): SharedKey? {
            val kind = SharedKind.of(body["kind"].str()) ?: return null
            if (kind == SharedKind.STYLE) {
                val name = (body["style"] as? JsonObject)?.get("name").str() ?: return null
                return SharedKey(kind, "", 0, name)
            }
            val row = body["row"] as? JsonObject ?: return null
            val v = (row["op_version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return SharedKey(kind, row["operation"].str() ?: return null, v, row["name"].str() ?: return null)
        }

        fun style(name: String) = SharedKey(SharedKind.STYLE, "", 0, name)
    }
}

/** One item as this device has it now: its key, its own hash (4.5) and its body. */
data class SharedLocal(val key: SharedKey, val hash: String, val body: JsonObject)

/** What the server says about an item (`GET /api/shared` without the body). */
data class SharedMeta(
    val id: Long,
    val key: SharedKey,
    val rev: Int,
    val seq: Long,
    val hash: String,
    val changedAt: Double,
    val deviceId: String,
    val deviceName: String,
    val deleted: Boolean,
    val size: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("kind", key.kind.json); put("operation", key.operation); put("op_version", key.opVersion)
        put("name", key.name); put("rev", rev); put("seq", seq); put("hash", hash); put("changed_at", changedAt)
        put("device_id", deviceId); put("device_name", deviceName); put("deleted", deleted); put("size", size)
    }

    companion object {
        fun fromJson(e: JsonElement?): SharedMeta? {
            val o = e as? JsonObject ?: return null
            val kind = SharedKind.of(o["kind"].str()) ?: return null
            return SharedMeta(
                id = o["id"].long() ?: return null,
                key = SharedKey(kind, o["operation"].str() ?: "", o["op_version"].num()?.toInt() ?: 0, o["name"].str() ?: return null),
                rev = o["rev"].num()?.toInt() ?: 0,
                seq = o["seq"].long() ?: 0,
                hash = o["hash"].str() ?: "",
                changedAt = o["changed_at"].num() ?: 0.0,
                deviceId = o["device_id"].str() ?: "",
                deviceName = o["device_name"].str() ?: "",
                deleted = o["deleted"].bool() == true,
                size = o["size"].long() ?: 0,
            )
        }
    }
}

/** An open conflict of this device (`conflicts` of `GET /api/shared`): [deleted] = the other side deleted the item. */
data class SharedConflict(val itemId: Long, val changedAt: Double, val deleted: Boolean) {
    fun toJson(): JsonObject = buildJsonObject { put("item_id", itemId); put("changed_at", changedAt); put("deleted", deleted) }

    companion object {
        fun fromJson(e: JsonElement?): SharedConflict? {
            val o = e as? JsonObject ?: return null
            return SharedConflict(o["item_id"].long() ?: return null, o["changed_at"].num() ?: 0.0, o["deleted"].bool() == true)
        }
    }
}

/** The answer to `GET /api/shared`. */
class SharedList(val seq: Long, val items: List<SharedMeta>, val conflicts: List<SharedConflict>)

/** What a PUT or DELETE came to. */
sealed interface SharedWrite {
    /** stored ([decided] = what the server decided at once under "newest wins": null, "mine" or "theirs") */
    data class Stored(val id: Long, val rev: Int, val seq: Long, val decided: String?) : SharedWrite
    /** 409 `changed_elsewhere`: the server keeps this device's copy as its conflict candidate */
    data class Conflict(val item: SharedMeta?) : SharedWrite
}

/** The per-item state a device reports (`PUT /api/shared/state`). */
enum class SharedState(val json: String) { OK("ok"), KEPT("kept"), UNUSABLE("unusable"), CONFLICT("conflict");
    companion object { fun of(s: String?) = entries.firstOrNull { it.json == s } }
}

/** "Same item changed on two devices": ask (the default) or newest wins. */
enum class SharedPolicy(val json: String) { ASK("ask"), NEWEST("newest");
    companion object { fun of(s: String?) = entries.firstOrNull { it.json == s } ?: ASK }
}

private fun q(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

/** `GET /api/shared?agent=<id>&since=<seq>` */
fun LplClient.sharedList(agent: String, since: Long): SharedList {
    val o = callJson("GET", "/api/shared?agent=${q(agent)}&since=$since")
    val items = o["items"].arr() ?: throw LplException(LplFailure.BAD_ANSWER, "GET /api/shared: no item list")
    return SharedList(o["seq"].long() ?: since, items.mapNotNull { SharedMeta.fromJson(it) }, o["conflicts"].arr().orEmpty().mapNotNull { SharedConflict.fromJson(it) })
}

/** `GET /api/shared/{id}`: the meta and the body of the current revision. */
fun LplClient.sharedGet(id: Long): Pair<SharedMeta, JsonObject> {
    val o = callJson("GET", "/api/shared/$id")
    val meta = SharedMeta.fromJson(o) ?: throw LplException(LplFailure.BAD_ANSWER, "GET /api/shared/$id: not an item")
    val body = o["body"] as? JsonObject ?: throw LplException(LplFailure.BAD_ANSWER, "GET /api/shared/$id: no body")
    return meta to body
}

private fun writeAnswer(label: String, st: Int, o: JsonObject): SharedWrite =
    if (st == 409) {
        if (o["error"].str() != "changed_elsewhere") throw LplException(LplFailure.REFUSED, "$label: 409 ${o["error"].str() ?: ""}", 409)
        SharedWrite.Conflict(SharedMeta.fromJson(o["item"]))
    } else SharedWrite.Stored(
        o["id"].long() ?: throw LplException(LplFailure.BAD_ANSWER, "$label: no id"),
        o["rev"].num()?.toInt() ?: 0, o["seq"].long() ?: 0, o["decided"].str(),
    )

/** `PUT /api/shared?agent=<id>` */
fun LplClient.sharedPut(agent: String, local: SharedLocal, changedAt: Double, baseRev: Int): SharedWrite {
    val body = buildJsonObject {
        put("kind", local.key.kind.json); put("operation", local.key.operation); put("op_version", local.key.opVersion)
        put("name", local.key.name); put("hash", local.hash); put("changed_at", changedAt); put("base_rev", baseRev)
        put("body", local.body)
    }
    val (st, o) = callJsonStatus("PUT", "/api/shared?agent=${q(agent)}", body.toString().toByteArray())
    return writeAnswer("PUT /api/shared", st, o)
}

/** `DELETE /api/shared/{id}?agent=<id>&base_rev=<n>&changed_at=<seconds>` */
fun LplClient.sharedDelete(agent: String, id: Long, baseRev: Int, changedAt: Double): SharedWrite {
    // changed_at (seconds since 1970): when the deletion was seen, so that "newest wins" compares fairly
    val (st, o) = callJsonStatus("DELETE", "/api/shared/$id?agent=${q(agent)}&base_rev=$baseRev&changed_at=${LplClient.formatTs(changedAt)}")
    return writeAnswer("DELETE /api/shared", st, o)
}

/** `POST /api/shared/{id}/resolve`: the items that changed. */
fun LplClient.sharedResolve(agent: String, id: Long, choice: String): List<SharedMeta> {
    val body = buildJsonObject { put("agent", agent); put("choice", choice) }
    val (_, o) = callJsonStatus("POST", "/api/shared/$id/resolve", body.toString().toByteArray(), allow = emptySet())
    return o["items"].arr().orEmpty().mapNotNull { SharedMeta.fromJson(it) }
}

/** `PUT /api/shared/state?agent=<id>`: the whole list each time. */
fun LplClient.sharedState(agent: String, items: Map<Long, Pair<Int, SharedState>>, policy: SharedPolicy) {
    val body = buildJsonObject {
        putJsonObject("items") {
            for ((id, v) in items) putJsonObject(id.toString()) {
                put("rev", v.first)
                // a conflict is the server's to know; the device's state for it stays "ok" at its revision
                put("state", if (v.second == SharedState.CONFLICT) SharedState.OK.json else v.second.json)
            }
        }
        put("policy", policy.json)
    }
    callJsonStatus("PUT", "/api/shared/state?agent=${q(agent)}", body.toString().toByteArray(), allow = emptySet())
}

/** Brief P3l: a module layout as an R11 body (4.5): the row `dt_presets_add_lib` stores for a user layout on desktop. */
object SharedLayout {
    const val OPERATION = "modulegroups"
    /** `DT_MODULE(1)` of src/libs/modulegroups.c */
    const val OP_VERSION = 1

    /**
     * The body of layout [name] whose stored string is [params]. `op_params` is that string's UTF-8 bytes without a
     * terminating zero, as desktop stores it (`dt_lib_presets_add(..., tx, strlen(tx), ...)` in modulegroups.c); the
     * other columns are the values `dt_presets_add_lib` (src/common/presets.c) gives a layout.
     */
    fun body(name: String, params: String): JsonObject = buildJsonObject {
        put("kind", SharedKind.LAYOUT.json)
        putJsonObject("row") {
            put("name", name); put("description", ""); put("operation", OPERATION); put("op_version", OP_VERSION)
            put("op_params", SharedHash.hex(params.toByteArray(Charsets.UTF_8)))
            put("enabled", 1); put("blendop_params", JsonNull); put("blendop_version", 0)
            put("multi_priority", JsonNull); put("multi_name", JsonNull); put("multi_name_hand_edited", JsonNull)
            put("model", "%"); put("maker", "%"); put("lens", "%")
            put("iso_min", SharedHash.real(0.0)); put("iso_max", SharedHash.real(340282346638528859812000000000000000000.0))
            put("exposure_min", SharedHash.real(0.0)); put("exposure_max", SharedHash.real(10000000.0))
            put("aperture_min", SharedHash.real(0.0)); put("aperture_max", SharedHash.real(100000000.0))
            put("focal_length_min", SharedHash.real(0.0)); put("focal_length_max", SharedHash.real(1000.0))
            put("autoapply", 0); put("filter", 0); put("def", 0); put("format", 0)
        }
    }

    /** The local item of a layout. */
    fun local(name: String, params: String): SharedLocal {
        val b = body(name, params)
        return SharedLocal(SharedKey(SharedKind.LAYOUT, OPERATION, OP_VERSION, name), SharedHash.hash(b)!!, b)
    }

    /**
     * The layout string of a layout body, as darktable reads `op_params` (a C string: up to the first zero byte);
     * null if the body is not a `modulegroups` row of version [OP_VERSION] with hex parameters.
     */
    fun params(body: JsonObject): String? {
        val row = body["row"] as? JsonObject ?: return null
        if (row["operation"].str() != OPERATION || (row["op_version"] as? JsonPrimitive)?.content?.toIntOrNull() != OP_VERSION) return null
        val bytes = SharedHash.unhex(row["op_params"].str() ?: return null) ?: return null
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return bytes.copyOf(end).decodeToString()
    }
}

/** The JSON array `dtb_shared_snapshot` gives (and the fake engine) as local items; a missing hash is computed here. */
fun sharedLocalsOf(snapshot: JsonArray): List<SharedLocal> = snapshot.mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    val body = o["body"] as? JsonObject ?: return@mapNotNull null
    val key = SharedKey.of(body) ?: return@mapNotNull null
    SharedLocal(key, o["hash"].str() ?: SharedHash.hash(body) ?: return@mapNotNull null, body)
}
