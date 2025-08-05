package org.ferrite.index

import groovy.transform.CompileStatic
import org.ferrite.common.ByteBuf

import java.util.Arrays

/**
 * Compressed bitmap implementation partitioning 32-bit integers into 16-bit chunks.
 * Dynamically converts between sparse array containers and dense bitset containers
 * to maximize performance and minimize memory consumption.
 */
@CompileStatic
class RoaringBitmap implements Cloneable, Serializable {
    private static final long serialVersionUID = 1L
    private static final int ARRAY_THRESHOLD = 4096

    private int[] keys // High 16 bits of integer keys
    private Container[] containers
    private int size

    RoaringBitmap() {
        this.keys = new int[4]
        this.containers = new Container[4]
        this.size = 0
    }

    void add(int value) {
        int high = (value >>> 16) & 0xFFFF
        int low = value & 0xFFFF

        int index = binarySearchKey(high)
        if (index >= 0) {
            Container c = containers[index].add(low)
            containers[index] = c
        } else {
            int insertPos = -index - 1
            insertContainerAt(insertPos, high, new ArrayContainer().add(low))
        }
    }

    boolean contains(int value) {
        int high = (value >>> 16) & 0xFFFF
        int low = value & 0xFFFF

        int index = binarySearchKey(high)
        if (index < 0) return false
        containers[index].contains(low)
    }

    int cardinality() {
        int count = 0
        for (int i = 0; i < size; i++) {
            count += containers[i].cardinality()
        }
        count
    }

    boolean isEmpty() {
        size == 0
    }

    int[] toArray() {
        int total = cardinality()
        int[] result = new int[total]
        int pos = 0
        for (int i = 0; i < size; i++) {
            int high = keys[i] << 16
            int[] lowValues = containers[i].toArray()
            for (int j = 0; j < lowValues.length; j++) {
                result[pos++] = high | lowValues[j]
            }
        }
        result
    }

    RoaringBitmap and(RoaringBitmap other) {
        RoaringBitmap result = new RoaringBitmap()
        int i1 = 0
        int i2 = 0
        while (i1 < this.size && i2 < other.size) {
            int k1 = this.keys[i1]
            int k2 = other.keys[i2]
            if (k1 == k2) {
                Container c = this.containers[i1].and(other.containers[i2])
                if (!c.isEmpty()) {
                    result.appendContainer(k1, c)
                }
                i1++
                i2++
            } else if (k1 < k2) {
                i1++
            } else {
                i2++
            }
        }
        result
    }

    RoaringBitmap or(RoaringBitmap other) {
        RoaringBitmap result = new RoaringBitmap()
        int i1 = 0
        int i2 = 0
        while (i1 < this.size || i2 < other.size) {
            if (i1 < this.size && i2 < other.size) {
                int k1 = this.keys[i1]
                int k2 = other.keys[i2]
                if (k1 == k2) {
                    result.appendContainer(k1, this.containers[i1].or(other.containers[i2]))
                    i1++
                    i2++
                } else if (k1 < k2) {
                    result.appendContainer(k1, this.containers[i1].cloneContainer())
                    i1++
                } else {
                    result.appendContainer(k2, other.containers[i2].cloneContainer())
                    i2++
                }
            } else if (i1 < this.size) {
                result.appendContainer(this.keys[i1], this.containers[i1].cloneContainer())
                i1++
            } else {
                result.appendContainer(other.keys[i2], other.containers[i2].cloneContainer())
                i2++
            }
        }
        result
    }

    private int binarySearchKey(int key) {
        int low = 0
        int high = size - 1
        while (low <= high) {
            int mid = (low + high) >>> 1
            int midKey = keys[mid]
            if (midKey < key) {
                low = mid + 1
            } else if (midKey > key) {
                high = mid - 1
            } else {
                return mid
            }
        }
        -(low + 1)
    }

    private void insertContainerAt(int index, int key, Container container) {
        ensureCapacity(size + 1)
        if (index < size) {
            System.arraycopy(keys, index, keys, index + 1, size - index)
            System.arraycopy(containers, index, containers, index + 1, size - index)
        }
        keys[index] = key
        containers[index] = container
        size++
    }

    private void appendContainer(int key, Container container) {
        ensureCapacity(size + 1)
        keys[size] = key
        containers[size] = container
        size++
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > keys.length) {
            int newCap = Math.max(keys.length * 2, minCapacity)
            int[] newKeys = new int[newCap]
            Container[] newContainers = new Container[newCap]
            System.arraycopy(keys, 0, newKeys, 0, size)
            System.arraycopy(containers, 0, newContainers, 0, size)
            this.keys = newKeys
            this.containers = newContainers
        }
    }

    static interface Container extends Serializable {
        Container add(int x)
        boolean contains(int x)
        int cardinality()
        boolean isEmpty()
        int[] toArray()
        Container and(Container other)
        Container or(Container other)
        Container cloneContainer()
    }

    static class ArrayContainer implements Container {
        private short[] content
        private int cardinality

        ArrayContainer() {
            this.content = new short[4]
            this.cardinality = 0
        }

        ArrayContainer(short[] content, int cardinality) {
            this.content = content
            this.cardinality = cardinality
        }

        @Override
        Container add(int x) {
            short s = (short) x
            int idx = binarySearch(s)
            if (idx >= 0) return this
            if (cardinality >= ARRAY_THRESHOLD) {
                BitsetContainer bc = new BitsetContainer()
                for (int i = 0; i < cardinality; i++) {
                    bc.add(content[i] & 0xFFFF)
                }
                bc.add(x)
                return bc
            }
            int insertPos = -idx - 1
            if (cardinality >= content.length) {
                short[] newContent = new short[content.length * 2]
                System.arraycopy(content, 0, newContent, 0, cardinality)
                this.content = newContent
            }
            if (insertPos < cardinality) {
                System.arraycopy(content, insertPos, content, insertPos + 1, cardinality - insertPos)
            }
            content[insertPos] = s
            cardinality++
            this
        }

        @Override
        boolean contains(int x) {
            binarySearch((short) x) >= 0
        }

        @Override
        int cardinality() {
            cardinality
        }

        @Override
        boolean isEmpty() {
            cardinality == 0
        }

        @Override
        int[] toArray() {
            int[] res = new int[cardinality]
            for (int i = 0; i < cardinality; i++) {
                res[i] = content[i] & 0xFFFF
            }
            res
        }

        @Override
        Container and(Container other) {
            if (other instanceof ArrayContainer) {
                ArrayContainer ac = (ArrayContainer) other
                ArrayContainer res = new ArrayContainer()
                int i1 = 0, i2 = 0
                while (i1 < this.cardinality && i2 < ac.cardinality) {
                    int v1 = this.content[i1] & 0xFFFF
                    int v2 = ac.content[i2] & 0xFFFF
                    if (v1 == v2) {
                        res.add(v1)
                        i1++
                        i2++
                    } else if (v1 < v2) {
                        i1++
                    } else {
                        i2++
                    }
                }
                return res
            } else {
                BitsetContainer bc = (BitsetContainer) other
                ArrayContainer res = new ArrayContainer()
                for (int i = 0; i < cardinality; i++) {
                    int val = content[i] & 0xFFFF
                    if (bc.contains(val)) {
                        res.add(val)
                    }
                }
                return res
            }
        }

        @Override
        Container or(Container other) {
            if (other instanceof ArrayContainer) {
                ArrayContainer ac = (ArrayContainer) other
                ArrayContainer res = new ArrayContainer()
                int i1 = 0, i2 = 0
                while (i1 < this.cardinality || i2 < ac.cardinality) {
                    if (i1 < this.cardinality && i2 < ac.cardinality) {
                        int v1 = this.content[i1] & 0xFFFF
                        int v2 = ac.content[i2] & 0xFFFF
                        if (v1 == v2) {
                            res.add(v1)
                            i1++
                            i2++
                        } else if (v1 < v2) {
                            res.add(v1)
                            i1++
                        } else {
                            res.add(v2)
                            i2++
                        }
                    } else if (i1 < this.cardinality) {
                        res.add(this.content[i1++] & 0xFFFF)
                    } else {
                        res.add(ac.content[i2++] & 0xFFFF)
                    }
                }
                return res
            } else {
                return other.or(this)
            }
        }

        @Override
        Container cloneContainer() {
            short[] copy = new short[cardinality]
            System.arraycopy(content, 0, copy, 0, cardinality)
            new ArrayContainer(copy, cardinality)
        }

        private int binarySearch(short key) {
            int low = 0
            int high = cardinality - 1
            int target = key & 0xFFFF
            while (low <= high) {
                int mid = (low + high) >>> 1
                int midVal = content[mid] & 0xFFFF
                if (midVal < target) {
                    low = mid + 1
                } else if (midVal > target) {
                    high = mid - 1
                } else {
                    return mid
                }
            }
            -(low + 1)
        }
    }

    static class BitsetContainer implements Container {
        private final long[] bitmap = new long[1024]
        private int cardinality = 0

        @Override
        Container add(int x) {
            int idx = x >>> 6
            long mask = 1L << (x & 0x3F)
            if ((bitmap[idx] & mask) == 0L) {
                bitmap[idx] |= mask
                cardinality++
            }
            this
        }

        @Override
        boolean contains(int x) {
            int idx = x >>> 6
            (bitmap[idx] & (1L << (x & 0x3F))) != 0L
        }

        @Override
        int cardinality() {
            cardinality
        }

        @Override
        boolean isEmpty() {
            cardinality == 0
        }

        @Override
        int[] toArray() {
            int[] res = new int[cardinality]
            int pos = 0
            for (int i = 0; i < 1024; i++) {
                long w = bitmap[i]
                while (w != 0L) {
                    long t = w & -w
                    int bit = Long.numberOfTrailingZeros(w)
                    res[pos++] = (i << 6) | bit
                    w ^= t
                }
            }
            res
        }

        @Override
        Container and(Container other) {
            if (other instanceof BitsetContainer) {
                BitsetContainer bc = (BitsetContainer) other
                BitsetContainer res = new BitsetContainer()
                for (int i = 0; i < 1024; i++) {
                    long w = this.bitmap[i] & bc.bitmap[i]
                    res.bitmap[i] = w
                    res.cardinality += Long.bitCount(w)
                }
                return res
            } else {
                return other.and(this)
            }
        }

        @Override
        Container or(Container other) {
            BitsetContainer res = new BitsetContainer()
            if (other instanceof BitsetContainer) {
                BitsetContainer bc = (BitsetContainer) other
                for (int i = 0; i < 1024; i++) {
                    long w = this.bitmap[i] | bc.bitmap[i]
                    res.bitmap[i] = w
                    res.cardinality += Long.bitCount(w)
                }
            } else {
                for (int i = 0; i < 1024; i++) {
                    res.bitmap[i] = this.bitmap[i]
                }
                res.cardinality = this.cardinality
                for (int val : other.toArray()) {
                    res.add(val)
                }
            }
            return res
        }

        @Override
        Container cloneContainer() {
            BitsetContainer copy = new BitsetContainer()
            System.arraycopy(bitmap, 0, copy.bitmap, 0, 1024)
            copy.cardinality = this.cardinality
            copy
        }
    }
}
