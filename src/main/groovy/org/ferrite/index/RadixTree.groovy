package org.ferrite.index

import groovy.transform.CompileStatic

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Compressed prefix radix tree for metric name hierarchy indexing and prefix scans.
 * Supports efficient autocompletion, wildcards, and metric hierarchy navigation.
 */
@CompileStatic
class RadixTree<T> {
    private final Node<T> root = new Node<T>("", null)
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock()
    private int size = 0

    void put(String key, T value) {
        if (key == null) return
        lock.writeLock().lock()
        try {
            if (putInternal(root, key, value)) {
                size++
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    T get(String key) {
        if (key == null) return null
        lock.readLock().lock()
        try {
            Node<T> current = root
            int idx = 0
            while (idx < key.length()) {
                Node<T> child = findChildWithPrefix(current, key.charAt(idx))
                if (child == null) return null

                String edge = child.edgeLabel
                int matchLen = commonPrefixLength(key, idx, edge, 0)
                if (matchLen < edge.length()) return null

                idx += matchLen
                current = child
            }
            return current.isTerminal ? current.value : null
        } finally {
            lock.readLock().unlock()
        }
    }

    List<Map.Entry<String, T>> scanPrefix(String prefix) {
        lock.readLock().lock()
        try {
            List<Map.Entry<String, T>> results = new ArrayList<>()
            Node<T> current = root
            int idx = 0
            StringBuilder matchedPrefix = new StringBuilder()

            while (idx < prefix.length()) {
                Node<T> child = findChildWithPrefix(current, prefix.charAt(idx))
                if (child == null) return Collections.emptyList()

                String edge = child.edgeLabel
                int matchLen = commonPrefixLength(prefix, idx, edge, 0)
                matchedPrefix.append(edge.substring(0, matchLen))

                if (matchLen < edge.length()) {
                    if (idx + matchLen == prefix.length()) {
                        // Prefix matched inside this edge: collect subtree from child
                        collectSubtree(child, matchedPrefix.substring(0, matchedPrefix.length() - matchLen), results)
                        return results
                    }
                    return Collections.emptyList()
                }

                idx += matchLen
                current = child
            }

            collectSubtree(current, matchedPrefix.toString(), results)
            return results
        } finally {
            lock.readLock().unlock()
        }
    }

    int size() {
        size
    }

    private boolean putInternal(Node<T> parent, String key, T value) {
        int idx = 0
        Node<T> current = parent

        while (idx < key.length()) {
            char firstChar = key.charAt(idx)
            Node<T> child = findChildWithPrefix(current, firstChar)

            if (child == null) {
                // Insert new branch
                Node<T> newNode = new Node<T>(key.substring(idx), value)
                newNode.isTerminal = true
                current.children.add(newNode)
                return true
            }

            int matchLen = commonPrefixLength(key, idx, child.edgeLabel, 0)

            if (matchLen < child.edgeLabel.length()) {
                // Split edge
                String common = child.edgeLabel.substring(0, matchLen)
                String remainingChildEdge = child.edgeLabel.substring(matchLen)

                Node<T> splitNode = new Node<T>(common, null)
                child.edgeLabel = remainingChildEdge

                current.children.remove(child)
                current.children.add(splitNode)
                splitNode.children.add(child)

                if (idx + matchLen == key.length()) {
                    splitNode.value = value
                    splitNode.isTerminal = true
                    return true
                } else {
                    String remainingNewKey = key.substring(idx + matchLen)
                    Node<T> newNode = new Node<T>(remainingNewKey, value)
                    newNode.isTerminal = true
                    splitNode.children.add(newNode)
                    return true
                }
            }

            idx += matchLen
            current = child
        }

        boolean wasNew = !current.isTerminal
        current.value = value
        current.isTerminal = true
        wasNew
    }

    private void collectSubtree(Node<T> node, String currentPrefix, List<Map.Entry<String, T>> results) {
        String fullPath = currentPrefix + node.edgeLabel
        if (node.isTerminal) {
            results.add(new AbstractMap.SimpleImmutableEntry<>(fullPath, node.value))
        }
        for (Node<T> child : node.children) {
            collectSubtree(child, fullPath, results)
        }
    }

    private Node<T> findChildWithPrefix(Node<T> node, char c) {
        for (Node<T> child : node.children) {
            if (child.edgeLabel.length() > 0 && child.edgeLabel.charAt(0) == c) {
                return child
            }
        }
        null
    }

    private static int commonPrefixLength(String s1, int offset1, String s2, int offset2) {
        int len = 0
        int maxLen = Math.min(s1.length() - offset1, s2.length() - offset2)
        while (len < maxLen && s1.charAt(offset1 + len) == s2.charAt(offset2 + len)) {
            len++
        }
        len
    }

    static class Node<V> {
        String edgeLabel
        V value
        boolean isTerminal = false
        final List<Node<V>> children = new ArrayList<>()

        Node(String edgeLabel, V value) {
            this.edgeLabel = edgeLabel
            this.value = value
        }
    }
}
