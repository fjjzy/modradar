package com.modradar.app.scan.axml

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 轻量 AXML（Android 二进制 XML）解析器，零第三方依赖。
 * 用于解析 APK 内的 AndroidManifest.xml（编译后为二进制格式）。
 */
class AxmlParser private constructor(private val buf: ByteBuffer) {

    companion object {
        private const val RES_STRING_POOL = 0x0001
        private const val RES_XML_START_ELEMENT = 0x0102
        private const val RES_XML_END_ELEMENT = 0x0103
        private const val RES_XML_RESOURCE_MAP = 0x0180

        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val TYPE_INT_BOOLEAN = 0x12

        fun parse(bytes: ByteArray): AxmlNode? =
            AxmlParser(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)).parseDocument()
    }

    private var strings: List<String> = emptyList()

    private fun parseDocument(): AxmlNode? {
        if (buf.capacity() < 8) return null
        buf.getShort() // RES_XML_TYPE
        buf.getShort() // header size
        buf.getInt() // file size

        var root: AxmlNode? = null
        val stack = ArrayDeque<AxmlNode>()

        while (buf.remaining() >= 8) {
            val chunkStart = buf.position()
            val type = buf.getShort().toInt() and 0xFFFF
            val headerSize = buf.getShort().toInt() and 0xFFFF
            val chunkSize = buf.getInt()
            if (chunkSize < 8 || chunkStart + chunkSize > buf.capacity()) break

            when (type) {
                RES_STRING_POOL -> strings = parseStringPool(chunkStart, headerSize, chunkSize)
                RES_XML_START_ELEMENT -> {
                    val node = parseStartElement(chunkStart, headerSize)
                    val parent = stack.lastOrNull()
                    if (parent != null) parent.children.add(node) else if (root == null) root = node
                    stack.addLast(node)
                }
                RES_XML_END_ELEMENT -> if (stack.isNotEmpty()) stack.removeLast()
                RES_XML_RESOURCE_MAP -> Unit
                else -> Unit
            }
            buf.position(chunkStart + chunkSize)
        }
        return root
    }

    private fun parseStringPool(chunkStart: Int, headerSize: Int, chunkSize: Int): List<String> {
        val count = buf.getInt(chunkStart + 8)
        val flags = buf.getInt(chunkStart + 16)
        val stringsStart = buf.getInt(chunkStart + 20)
        val isUtf8 = (flags and 0x100) != 0
        val offsetsBase = chunkStart + headerSize
        val dataBase = chunkStart + stringsStart
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = buf.getInt(offsetsBase + i * 4)
            out.add(readString(dataBase + off, isUtf8))
        }
        return out
    }

    private fun readString(pos: Int, isUtf8: Boolean): String {
        if (pos < 0 || pos >= buf.capacity()) return ""
        return if (isUtf8) {
            var p = pos
            var r = readUleb128(p); p = r.second // UTF-16 长度（忽略）
            r = readUleb128(p); p = r.second
            val byteLen = r.first
            if (p + byteLen > buf.capacity()) return ""
            val bytes = ByteArray(byteLen)
            val dup = buf.duplicate()
            dup.position(p)
            dup.get(bytes)
            String(bytes, Charsets.UTF_8)
        } else {
            val r = readUleb128(pos)
            val charLen = r.first
            var p = r.second
            val byteLen = charLen * 2
            if (p + byteLen > buf.capacity()) return ""
            val chars = CharArray(charLen)
            val dup = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until charLen) {
                dup.position(p + i * 2)
                chars[i] = dup.short.toInt().toChar()
            }
            String(chars)
        }
    }

    private fun readUleb128(pos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var p = pos
        while (p < buf.capacity()) {
            val b = buf.get(p).toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            p++
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) break
        }
        return result to p
    }

    private fun str(idx: Int): String = strings.getOrNull(idx) ?: ""

    private fun parseStartElement(chunkStart: Int, headerSize: Int): AxmlNode {
        val nsIdx = buf.getInt(chunkStart + 16)
        val nameIdx = buf.getInt(chunkStart + 20)
        val attrStart = buf.getShort(chunkStart + 24).toInt() and 0xFFFF
        val attrCount = buf.getShort(chunkStart + 28).toInt() and 0xFFFF
        val attrsBase = chunkStart + headerSize + attrStart
        val attrs = ArrayList<AxmlAttribute>(attrCount)
        for (i in 0 until attrCount) {
            val p = attrsBase + i * 20
            if (p + 20 > buf.capacity()) break
            val aNs = str(buf.getInt(p))
            val aName = str(buf.getInt(p + 4))
            val rawIdx = buf.getInt(p + 8)
            val dataType = buf.get(p + 15).toInt() and 0xFF
            val data = buf.getInt(p + 16)
            val raw = if (rawIdx >= 0) str(rawIdx) else null
            val resolved = if (!raw.isNullOrEmpty()) {
                raw
            } else {
                when (dataType) {
                    TYPE_STRING -> str(data)
                    TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
                    TYPE_INT_DEC -> data.toString()
                    TYPE_INT_HEX -> "0x" + Integer.toHexString(data)
                    TYPE_REFERENCE -> "@ref/0x" + Integer.toHexString(data)
                    else -> null
                }
            }
            attrs.add(
                AxmlAttribute(
                    namespace = aNs.ifEmpty { null },
                    name = aName,
                    rawValue = raw,
                    type = dataType,
                    data = data,
                    value = resolved,
                )
            )
        }
        return AxmlNode(name = str(nameIdx), namespace = str(nsIdx).ifEmpty { null }, attributes = attrs)
    }

    /** 把 typed value 还原成可读字符串。 */
    fun resolveValue(attr: AxmlAttribute): String? {
        attr.rawValue?.let { if (it.isNotEmpty()) return it }
        return when (attr.type) {
            TYPE_STRING -> str(attr.data)
            TYPE_INT_BOOLEAN -> if (attr.data != 0) "true" else "false"
            TYPE_INT_DEC -> attr.data.toString()
            TYPE_INT_HEX -> "0x" + Integer.toHexString(attr.data)
            TYPE_REFERENCE -> "@ref/0x" + Integer.toHexString(attr.data)
            else -> null
        }
    }
}

data class AxmlAttribute(
    val namespace: String?,
    val name: String,
    val rawValue: String?,
    val type: Int,
    val data: Int,
    val value: String? = null,
)

class AxmlNode(
    val name: String,
    val namespace: String?,
    val attributes: List<AxmlAttribute>,
    val children: MutableList<AxmlNode> = mutableListOf(),
) {
    fun child(tag: String): List<AxmlNode> = children.filter { it.name == tag }

    fun attr(name: String): AxmlAttribute? = attributes.firstOrNull { it.name == name }
}
