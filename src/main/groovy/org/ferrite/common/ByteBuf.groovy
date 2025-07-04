package org.ferrite.common

import groovy.transform.CompileStatic

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * High-performance byte buffer with independent reader and writer indices.
 * Provides big-endian serialization primitives, dynamic growth, and zero-copy slicing.
 */
@CompileStatic
class ByteBuf {
    private byte[] data
    private int readerIndex
    private int writerIndex
    private int markedReaderIndex
    private int markedWriterIndex

    ByteBuf(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Capacity must be non-negative: ${initialCapacity}")
        }
        this.data = new byte[initialCapacity]
        this.readerIndex = 0
        this.writerIndex = 0
        this.markedReaderIndex = 0
        this.markedWriterIndex = 0
    }

    ByteBuf(byte[] array) {
        if (array == null) {
            throw new IllegalArgumentException("Array cannot be null")
        }
        this.data = array
        this.readerIndex = 0
        this.writerIndex = array.length
        this.markedReaderIndex = 0
        this.markedWriterIndex = 0
    }

    static ByteBuf allocate(int initialCapacity) {
        new ByteBuf(initialCapacity)
    }

    static ByteBuf wrap(byte[] array) {
        new ByteBuf(array)
    }

    static ByteBuf wrap(ByteBuffer buffer) {
        byte[] copy = new byte[buffer.remaining()]
        int pos = buffer.position()
        buffer.get(copy)
        buffer.position(pos)
        new ByteBuf(copy)
    }

    int capacity() {
        data.length
    }

    int readerIndex() {
        readerIndex
    }

    ByteBuf readerIndex(int newIndex) {
        if (newIndex < 0 || newIndex > writerIndex) {
            throw new IndexOutOfBoundsException("readerIndex (${newIndex}) must be between 0 and writerIndex (${writerIndex})")
        }
        this.readerIndex = newIndex
        this
    }

    int writerIndex() {
        writerIndex
    }

    ByteBuf writerIndex(int newIndex) {
        if (newIndex < readerIndex || newIndex > data.length) {
            throw new IndexOutOfBoundsException("writerIndex (${newIndex}) must be between readerIndex (${readerIndex}) and capacity (${data.length})")
        }
        this.writerIndex = newIndex
        this
    }

    int readableBytes() {
        writerIndex - readerIndex
    }

    boolean isReadable() {
        writerIndex > readerIndex
    }

    int writableBytes() {
        data.length - writerIndex
    }

    void markReaderIndex() {
        markedReaderIndex = readerIndex
    }

    void resetReaderIndex() {
        readerIndex(markedReaderIndex)
    }

    void markWriterIndex() {
        markedWriterIndex = writerIndex
    }

    void resetWriterIndex() {
        writerIndex(markedWriterIndex)
    }

    ByteBuf clear() {
        readerIndex = 0
        writerIndex = 0
        this
    }

    ByteBuf ensureWritable(int minBytes) {
        if (minBytes <= 0) return this
        if (writableBytes() >= minBytes) return this

        int newCapacity = Math.max(data.length * 2, writerIndex + minBytes)
        byte[] newData = new byte[newCapacity]
        System.arraycopy(data, 0, newData, 0, writerIndex)
        this.data = newData
        this
    }

    ByteBuf writeByte(int b) {
        ensureWritable(1)
        data[writerIndex++] = (byte) (b & 0xFF)
        this
    }

    byte readByte() {
        checkReadable(1)
        data[readerIndex++]
    }

    int readUnsignedByte() {
        readByte() & 0xFF
    }

    ByteBuf writeShort(int s) {
        ensureWritable(2)
        data[writerIndex++] = (byte) ((s >>> 8) & 0xFF)
        data[writerIndex++] = (byte) (s & 0xFF)
        this
    }

    short readShort() {
        checkReadable(2)
        short s = (short) (((data[readerIndex++] & 0xFF) << 8) | (data[readerIndex++] & 0xFF))
        s
    }

    ByteBuf writeInt(int v) {
        ensureWritable(4)
        data[writerIndex++] = (byte) ((v >>> 24) & 0xFF)
        data[writerIndex++] = (byte) ((v >>> 16) & 0xFF)
        data[writerIndex++] = (byte) ((v >>> 8) & 0xFF)
        data[writerIndex++] = (byte) (v & 0xFF)
        this
    }

    int readInt() {
        checkReadable(4)
        ((data[readerIndex++] & 0xFF) << 24) |
                ((data[readerIndex++] & 0xFF) << 16) |
                ((data[readerIndex++] & 0xFF) << 8) |
                (data[readerIndex++] & 0xFF)
    }

    ByteBuf writeLong(long v) {
        ensureWritable(8)
        data[writerIndex++] = (byte) ((v >>> 56) & 0xFF)
        data[writerIndex++] = (byte) ((v >>> 48) & 0xFF)
        data[writerIndex++] = (byte) ((v >>> 40) & 0xFF)
        data[writerIndex++] = (byte) ((v >>> 32) & 0xFF)
        data[writerIndex++] = (byte) ((v >>> 24) & 0xFF)
        data[writerIndex++] = (byte) ((v >>> 16) & 0xFF)
        data[writerIndex++] = (byte) ((v >>> 8) & 0xFF)
        data[writerIndex++] = (byte) (v & 0xFF)
        this
    }

    long readLong() {
        checkReadable(8)
        (((long) data[readerIndex++] & 0xFFL) << 56) |
                (((long) data[readerIndex++] & 0xFFL) << 48) |
                (((long) data[readerIndex++] & 0xFFL) << 40) |
                (((long) data[readerIndex++] & 0xFFL) << 32) |
                (((long) data[readerIndex++] & 0xFFL) << 24) |
                (((long) data[readerIndex++] & 0xFFL) << 16) |
                (((long) data[readerIndex++] & 0xFFL) << 8) |
                ((long) data[readerIndex++] & 0xFFL)
    }

    ByteBuf writeDouble(double d) {
        writeLong(Double.doubleToRawLongBits(d))
    }

    double readDouble() {
        Double.longBitsToDouble(readLong())
    }

    ByteBuf writeBytes(byte[] src) {
        if (src == null) return this
        writeBytes(src, 0, src.length)
    }

    ByteBuf writeBytes(byte[] src, int offset, int length) {
        if (src == null || length <= 0) return this
        ensureWritable(length)
        System.arraycopy(src, offset, data, writerIndex, length)
        writerIndex += length
        this
    }

    ByteBuf writeBytes(ByteBuf src) {
        if (src == null) return this
        int len = src.readableBytes()
        ensureWritable(len)
        System.arraycopy(src.data, src.readerIndex, data, writerIndex, len)
        src.readerIndex += len
        writerIndex += len
        this
    }

    void readBytes(byte[] dst, int offset, int length) {
        checkReadable(length)
        System.arraycopy(data, readerIndex, dst, offset, length)
        readerIndex += length
    }

    byte[] readBytes(int length) {
        byte[] dst = new byte[length]
        readBytes(dst, 0, length)
        dst
    }

    ByteBuf writeStringUtf8(String str) {
        if (str == null) {
            writeInt(-1)
            return this
        }
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8)
        writeInt(bytes.length)
        writeBytes(bytes)
        this
    }

    String readStringUtf8() {
        int len = readInt()
        if (len < 0) return null
        byte[] bytes = readBytes(len)
        new String(bytes, StandardCharsets.UTF_8)
    }

    byte[] toByteArray() {
        int len = readableBytes()
        byte[] result = new byte[len]
        System.arraycopy(data, readerIndex, result, 0, len)
        result
    }

    ByteBuffer toNioByteBuffer() {
        ByteBuffer.wrap(data, readerIndex, readableBytes()).asReadOnlyBuffer()
    }

    private void checkReadable(int length) {
        if (readableBytes() < length) {
            throw new IndexOutOfBoundsException("Buffer underflow: requested ${length} bytes but only ${readableBytes()} readable")
        }
    }
}
