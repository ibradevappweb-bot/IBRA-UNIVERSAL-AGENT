package com.ibra.universalagent

enum class MemoryType { SESSION, PREFERENCE, FACT, TASK, OUTCOME }

data class MemoryEntry(
    val type: MemoryType,
    val key: String,
    val value: String,
    val importance: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
)

interface MemoryStore {
    fun put(entry: MemoryEntry)
    fun get(type: MemoryType, key: String): MemoryEntry?
    fun find(type: MemoryType? = null): List<MemoryEntry>
    fun remove(type: MemoryType, key: String): Boolean
    fun clear(type: MemoryType? = null)
}

class InMemoryMemoryStore : MemoryStore {
    private val entries = linkedMapOf<String, MemoryEntry>()

    private fun id(type: MemoryType, key: String) =
        type.name + ":" + key.trim().lowercase()

    override fun put(entry: MemoryEntry) {
        require(entry.key.isNotBlank())
        entries[id(entry.type, entry.key)] = entry
    }

    override fun get(type: MemoryType, key: String) =
        entries[id(type, key)]

    override fun find(type: MemoryType?) =
        entries.values
            .filter { type == null || it.type == type }
            .sortedByDescending { it.importance }

    override fun remove(type: MemoryType, key: String) =
        entries.remove(id(type, key)) != null

    override fun clear(type: MemoryType?) {
        if (type == null) entries.clear()
        else entries.entries.removeIf { it.value.type == type }
    }
}

class MemoryEngine(private val store: MemoryStore = InMemoryMemoryStore()) {
    fun remember(
        type: MemoryType,
        key: String,
        value: String,
        importance: Int = 1
    ) {
        store.put(
            MemoryEntry(
                type = type,
                key = key,
                value = value,
                importance = importance.coerceIn(1, 5)
            )
        )
    }

    fun recall(type: MemoryType? = null): List<MemoryEntry> =
        store.find(type)

    fun find(type: MemoryType, key: String): MemoryEntry? =
        store.get(type, key)

    fun forget(type: MemoryType, key: String): Boolean =
        store.remove(type, key)

    fun clear(type: MemoryType? = null) =
        store.clear(type)
}
