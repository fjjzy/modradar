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

        fun parse(bytes: ByteArray): AxmlNode? = runCatching {
            AxmlParser(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)).parseDocument()
        }.getOrNull()

        /** 清单分析用到的 android:* 属性 ID（frameworks/base/core/res/res/values/public.xml）。 */
        private val ATTR_NAMES = mapOf(
            0x01010000 to "theme", 0x01010001 to "label", 0x01010002 to "icon", 0x01010003 to "name",
            0x01010006 to "permission", 0x01010007 to "readPermission", 0x01010008 to "writePermission",
            0x01010009 to "protectionLevel", 0x0101000b to "sharedUserId", 0x0101000c to "hasCode",
            0x0101000e to "enabled", 0x0101000f to "debuggable", 0x01010010 to "exported",
            0x01010011 to "process", 0x01010018 to "authorities", 0x01010024 to "value",
            0x01010025 to "resource", 0x0101020c to "minSdkVersion", 0x0101021b to "versionCode",
            0x0101021c to "versionName", 0x01010270 to "targetSdkVersion", 0x01010271 to "maxSdkVersion",
            0x01010280 to "allowBackup", 0x010104ec to "usesCleartextTraffic",
            0x01010527 to "networkSecurityConfig",
        )
    }

    private var strings: List<String> = emptyList()

    /** 资源映射表：字符串下标 → 系统属性 ID（attr 名以 ID 为准，混淆/裁剪后字符串可能为空或伪造）。 */
    private var resIds: IntArray = IntArray(0)

    private fun parseDocument(): AxmlNode? {
        if (buf.capacity() < 8) return null
        val headerSize = buf.getShort(2).toInt() and 0xFFFF
        buf.position(if (headerSize in 8..buf.capacity()) headerSize else 8)

        var root: AxmlNode? = null
        val stack = ArrayDeque<AxmlNode>()

        while (buf.remaining() >= 8) {
            val chunkStart = buf.position()
            val type = buf.getShort().toInt() and 0xFFFF
            val chunkHeader = buf.getShort().toInt() and 0xFFFF
            val chunkSize = buf.getInt()
            if (chunkSize < 8 || chunkStart.toLong() + chunkSize > buf.capacity()) break

            runCatching {
                when (type) {
                    RES_STRING_POOL -> strings = parseStringPool(chunkStart, chunkHeader)
                    RES_XML_RESOURCE_MAP -> resIds = IntArray((chunkSize - chunkHeader) / 4) {
                        buf.getInt(chunkStart + chunkHeader + it * 4)
                    }
                    RES_XML_START_ELEMENT -> {
                        val node = parseStartElement(chunkStart)
                        val parent = stack.lastOrNull()
                        if (parent != null) parent.children.add(node) else if (root == null) root = node
                        stack.addLast(node)
                    }
                    RES_XML_END_ELEMENT -> if (stack.isNotEmpty()) stack.removeLast()
                    else -> Unit
                }
            }
            buf.position(chunkStart + chunkSize)
        }
        return root
    }

    private fun parseStringPool(chunkStart: Int, headerSize: Int): List<String> {
        val count = buf.getInt(chunkStart + 8)
        val flags = buf.getInt(chunkStart + 16)
        val stringsStart = buf.getInt(chunkStart + 20)
        val isUtf8 = (flags and 0x100) != 0
        val offsetsBase = chunkStart + headerSize
        val dataBase = chunkStart + stringsStart
        if (count < 0 || offsetsBase.toLong() + count * 4L > buf.capacity()) return emptyList()
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = buf.getInt(offsetsBase + i * 4)
            out.add(runCatching { readString(dataBase + off, isUtf8) }.getOrDefault(""))
        }
        return out
    }

    private fun readString(pos: Int, isUtf8: Boolean): String {
        if (pos < 0 || pos >= buf.capacity()) return ""
        return if (isUtf8) {
            // u8 字符数 + u8 字节数，高位为 1 时各自扩展一个字节
            var p = pos
            p += if (buf.get(p).toInt() and 0x80 != 0) 2 else 1
            var byteLen = buf.get(p).toInt() and 0xFF
            if (byteLen and 0x80 != 0) {
                byteLen = ((byteLen and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF)
                p += 2
            } else {
                p += 1
            }
            if (p + byteLen > buf.capacity()) return ""
            val bytes = ByteArray(byteLen)
            for (i in 0 until byteLen) bytes[i] = buf.get(p + i)
            String(bytes, Charsets.UTF_8)
        } else {
            // u16 字符数，高位为 1 时扩展一个 u16
            var p = pos
            var charLen = buf.getShort(p).toInt() and 0xFFFF
            p += 2
            if (charLen and 0x8000 != 0) {
                charLen = ((charLen and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF)
                p += 2
            }
            if (p.toLong() + charLen * 2L > buf.capacity()) return ""
            val chars = CharArray(charLen) { buf.getShort(p + it * 2).toInt().toChar() }
            String(chars)
        }
    }

    private fun str(idx: Int): String = strings.getOrNull(idx) ?: ""

    private fun attrName(idx: Int): String {
        val id = resIds.getOrNull(idx)
        if (id != null) ATTR_NAMES[id]?.let { return it }
        return str(idx)
    }

    private fun parseStartElement(chunkStart: Int): AxmlNode {
        // ResXMLTree_node(16 字节) 之后是 ResXMLTree_attrExt
        val ext = chunkStart + 16
        val nsIdx = buf.getInt(ext)
        val nameIdx = buf.getInt(ext + 4)
        val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
        val attrSize = (buf.getShort(ext + 10).toInt() and 0xFFFF).takeIf { it >= 20 } ?: 20
        val attrCount = buf.getShort(ext + 12).toInt() and 0xFFFF
        val attrsBase = ext + attrStart
        val attrs = ArrayList<AxmlAttribute>(attrCount)
        for (i in 0 until attrCount) {
            val p = attrsBase + i * attrSize
            if (p + 20 > buf.capacity()) break
            val aNs = str(buf.getInt(p))
            val aName = attrName(buf.getInt(p + 4))
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
