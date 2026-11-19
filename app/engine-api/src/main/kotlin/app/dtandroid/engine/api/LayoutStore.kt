// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.engine.api

/**
 * A module layout as darktable keeps it: a row of its presets table for the
 * `modulegroups` lib module (name, the layout string, write protection).
 * Built-in layouts are write-protected; darktable rebuilds them at every start
 * from its pixel workflow preference.
 */
data class StoredLayout(val name: String, val params: String, val writeProtected: Boolean)

/**
 * Where module layouts live (engine-api.md section 3, "Module layouts"). The
 * screens use only this interface. Now an app-private file ([FileLayoutStore]);
 * later the real engine keeps them in darktable's own database and configuration,
 * as desktop does, and an implementation over engine calls replaces the file one
 * without any change to the screens. Functions are `suspend` because that
 * implementation runs on the engine thread.
 *
 * Behaviour follows desktop darktable (`src/libs/modulegroups.c`, `src/common/presets.c`):
 * - [list] gives built-in layouts first, then the user's, each by name (`dt_presets_version_names`);
 * - built-in layouts cannot be written, renamed or deleted ([LayoutStoreException]);
 * - writing a user layout under an existing user name replaces it (`INSERT OR REPLACE`);
 * - [current] falls back to [fallbackName] ("workflow: scene-referred") when the
 *   current layout no longer exists, and deleting the current layout makes the fallback current.
 */
interface LayoutStore {
    /** The layout darktable falls back to. */
    val fallbackName: String

    suspend fun list(): List<StoredLayout>

    suspend fun read(name: String): StoredLayout?

    /** Create or replace a user layout. */
    suspend fun write(name: String, params: String)

    suspend fun delete(name: String)

    /** `dt_presets_rename`: keeps the layout's place as current. */
    suspend fun rename(from: String, to: String)

    /** The current layout's name (darktable's `plugins/darkroom/modulegroups_preset`), never one that does not exist. */
    suspend fun current(): String

    suspend fun setCurrent(name: String)

    /**
     * darktable's pixel workflow preference (`plugins/darkroom/workflow`, "auto-apply pixel
     * workflow defaults"), which chooses among the built-in layouts' variants. With the real
     * engine this is darktable's own configuration value and it also decides which tone
     * mapper new edits get; in the file store it only selects the built-in variants.
     */
    suspend fun workflow(): String

    suspend fun setWorkflow(workflow: String)
}

class LayoutStoreException(message: String) : Exception(message)
