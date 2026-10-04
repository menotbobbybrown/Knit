package app.getknit.knit.notifications

import androidx.core.content.pm.ShortcutInfoCompat

/**
 * The platform's shortcut store as the tests need it to behave, where Robolectric's shadow does not: removing the
 * long-lived copy takes the dynamic and cached one and leaves a pinned one, disabling greys a pinned one and drops the
 * rest, an update refreshes only what exists, and a push over a disabled pinned shortcut replaces it enabled — the
 * platform's replace keeps the pinned flag, not the disabled one.
 */
internal class FakeShortcutStore : ShortcutStore {
    class Record(
        var shortcut: ShortcutInfoCompat,
        var live: Boolean,
        var pinned: Boolean = false,
        var enabled: Boolean = true,
    )

    val records = LinkedHashMap<String, Record>()

    /** Set to make [update] answer as the platform does when its background rate limit is spent. */
    var refuseUpdates = false

    var updates = 0
        private set

    fun pin(id: String) {
        records.getValue(id).pinned = true
    }

    fun label(id: String): String =
        records
            .getValue(id)
            .shortcut.shortLabel
            .toString()

    override fun read(): List<ShortcutState> =
        records.values.map {
            ShortcutState(it.shortcut.id, it.live, it.pinned, it.enabled, it.shortcut.fingerprint())
        }

    override fun push(shortcut: ShortcutInfoCompat) {
        val record = records[shortcut.id]
        if (record == null) {
            records[shortcut.id] = Record(shortcut, live = true)
        } else {
            record.shortcut = shortcut
            record.live = true
            record.enabled = true
        }
    }

    override fun removeLive(ids: List<String>) {
        for (id in ids) {
            val record = records[id] ?: continue
            if (record.pinned) record.live = false else records.remove(id)
        }
    }

    override fun disable(
        ids: List<String>,
        message: CharSequence,
    ) {
        for (id in ids) {
            val record = records[id] ?: continue
            if (record.pinned) {
                record.live = false
                record.enabled = false
            } else {
                records.remove(id)
            }
        }
    }

    override fun enable(shortcuts: List<ShortcutInfoCompat>) {
        for (shortcut in shortcuts) records[shortcut.id]?.enabled = true
    }

    override fun update(shortcuts: List<ShortcutInfoCompat>): Boolean {
        if (refuseUpdates) return false
        updates++
        for (shortcut in shortcuts) records[shortcut.id]?.shortcut = shortcut
        return true
    }
}
