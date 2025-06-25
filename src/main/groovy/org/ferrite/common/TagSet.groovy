package org.ferrite.common

import groovy.transform.CompileStatic

/**
 * An immutable, canonically-sorted collection of key-value tag dimensions.
 * Ensures deterministic serialization and equality checks across distributed nodes.
 */
@CompileStatic
class TagSet implements Comparable<TagSet>, Serializable {
    private static final long serialVersionUID = 1L

    public static final TagSet EMPTY = new TagSet(Collections.emptyMap())

    private final String[] keys
    private final String[] values
    private final int hashCodeCache
    private String canonicalStringCache

    TagSet(Map<String, String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            this.keys = new String[0]
            this.values = new String[0]
            this.hashCodeCache = 0
            this.canonicalStringCache = ""
            return
        }

        // Canonical sorted order by key
        List<Map.Entry<String, String>> entries = new ArrayList<>(rawTags.entrySet())
        entries.sort { a, b -> a.key <=> b.key }

        int size = entries.size()
        this.keys = new String[size]
        this.values = new String[size]

        int h = 1
        for (int i = 0; i < size; i++) {
            Map.Entry<String, String> entry = entries.get(i)
            String k = entry.key != null ? entry.key.trim() : ""
            String v = entry.value != null ? entry.value.trim() : ""
            this.keys[i] = k
            this.values[i] = v
            h = 31 * h + (k.hashCode() ^ v.hashCode())
        }
        this.hashCodeCache = h
    }

    private TagSet(String[] keys, String[] values, int hashCodeCache) {
        this.keys = keys
        this.values = values
        this.hashCodeCache = hashCodeCache
    }

    static TagSet of(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) return EMPTY
        new TagSet(tags)
    }

    static TagSet of(String... kvs) {
        if (kvs == null || kvs.length == 0) return EMPTY
        if (kvs.length % 2 != 0) {
            throw new IllegalArgumentException("Key-value arguments must be even, got: ${kvs.length}")
        }
        Map<String, String> map = new HashMap<>()
        for (int i = 0; i < kvs.length; i += 2) {
            map.put(kvs[i], kvs[i + 1])
        }
        new TagSet(map)
    }

    int size() {
        keys.length
    }

    boolean isEmpty() {
        keys.length == 0
    }

    String get(String key) {
        int idx = binarySearchKey(key)
        idx >= 0 ? values[idx] : null
    }

    boolean containsKey(String key) {
        binarySearchKey(key) >= 0
    }

    String getKeyAt(int index) {
        keys[index]
    }

    String getValueAt(int index) {
        values[index]
    }

    TagSet withTag(String key, String value) {
        Map<String, String> current = new LinkedHashMap<>(toMap())
        current.put(key, value)
        new TagSet(current)
    }

    TagSet withoutTag(String key) {
        if (!containsKey(key)) return this
        Map<String, String> current = new LinkedHashMap<>(toMap())
        current.remove(key)
        new TagSet(current)
    }

    Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>()
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], values[i])
        }
        Collections.unmodifiableMap(map)
    }

    String toCanonicalString() {
        if (canonicalStringCache == null) {
            StringBuilder sb = new StringBuilder()
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) sb.append(",")
                sb.append(keys[i]).append("=").append(values[i])
            }
            canonicalStringCache = sb.toString()
        }
        canonicalStringCache
    }

    boolean matches(Map<String, String> filter) {
        if (filter == null || filter.isEmpty()) return true
        for (Map.Entry<String, String> entry : filter.entrySet()) {
            String val = get(entry.key)
            if (val == null || !val.equals(entry.value)) {
                return false
            }
        }
        true
    }

    private int binarySearchKey(String key) {
        if (key == null) return -1
        int low = 0
        int high = keys.length - 1
        while (low <= high) {
            int mid = (low + high) >>> 1
            int cmp = keys[mid].compareTo(key)
            if (cmp < 0) {
                low = mid + 1
            } else if (cmp > 0) {
                high = mid - 1
            } else {
                return mid
            }
        }
        -1
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) return true
        if (!(o instanceof TagSet)) return false
        TagSet other = (TagSet) o
        if (this.hashCodeCache != other.hashCodeCache) return false
        if (this.keys.length != other.keys.length) return false
        for (int i = 0; i < keys.length; i++) {
            if (!this.keys[i].equals(other.keys[i]) || !this.values[i].equals(other.values[i])) {
                return false
            }
        }
        true
    }

    @Override
    int hashCode() {
        hashCodeCache
    }

    @Override
    int compareTo(TagSet o) {
        return this.toCanonicalString().compareTo(o.toCanonicalString())
    }

    @Override
    String toString() {
        "{" + toCanonicalString() + "}"
    }
}
