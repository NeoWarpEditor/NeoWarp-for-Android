package org.neowarp.android;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * NeoWarp 协作协议引擎（纯 Java，兼容桌面版 collaboration-server.js /
 * collaboration-project-payload.js / collaboration-project-policy.js /
 * windows/collaboration.js）。
 *
 * <p><b>所有 callback 均在网络线程调用，调用方需自行切 UI 线程。</b>
 * 本项目约定由 BridgeHub 包装 callback（其内部 evaluateJs 已自动切主线程），
 * 协议引擎本身只做网络 I/O 和调用 callback，不直接触碰任何 UI。</p>
 *
 * <p>仅依赖 JDK/Android 标准库；Java 8 语法。Host 作为 WebSocket 服务端，
 * Join 作为 WebSocket 客户端，二者在同一 TCP 端口上还提供 HTTP GET /discover。</p>
 */
public class CollabEngine {

    // ---- 认证常量（对齐 server.js:14-24 / collaboration.js:11-15）----
    public static final int AUTH_PROTOCOL_VERSION = 2;
    public static final int AUTH_KDF_ITERATIONS = 210000;
    public static final int AUTH_NONCE_BYTES = 32;
    public static final int AUTH_SALT_BYTES = 16;
    public static final long AUTH_CHALLENGE_TTL_MS = 15000;
    private static final int AUTH_MAX_INIT_PER_CONNECTION = 10;
    private static final int AUTH_MAX_TRIES_PER_CONNECTION = 3;
    private static final int AUTH_MAX_FAILURES_PER_IP = 5;
    private static final long AUTH_IP_BLOCK_MS = 60000;
    private static final long AUTH_FAILURE_DELAY_MS = 400;
    private static final int AUTH_MIN_ITERATIONS = 10000;
    private static final int AUTH_MAX_ITERATIONS = 1000000;

    public static final String MAGIC_STRING = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // ---- WebSocket 操作码（对齐 server.js:26-32）----
    public static final int OPCODE_CONT = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    // ---- sb3 载荷限制（对齐 payload.js:14-19）----
    public static final int MAX_PROJECT_JSON_BYTES = 32 * 1024 * 1024;
    public static final int MAX_PAYLOAD_TEXT_LENGTH = 96 * 1024 * 1024;
    private static final long ZIP_EOCD_SIGNATURE = 0x06054b50L;
    private static final long ZIP_CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long ZIP_LOCAL_SIGNATURE = 0x04034b50L;
    private static final String ZIP_ENTRY_NAME = "project.json";

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CollabEngine() {
    }

    // ====================================================================
    // 1. WebSocket 帧编解码
    // ====================================================================

    /** 一帧 WebSocket 数据。payload 已解掩码；bytesRead 为从缓冲区起点消费的字节数。 */
    public static final class Frame {
        public final boolean fin;
        public final int opcode;
        public final byte[] payload;
        public final int bytesRead;

        Frame(boolean fin, int opcode, byte[] payload, int bytesRead) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
            this.bytesRead = bytesRead;
        }
    }

    /**
     * 解析服务端视角的入站帧（客户端帧带 mask，需解掩码）。
     * 对齐 server.js:364-414。返回 null 表示帧尚不完整。
     */
    public static Frame parseFrame(byte[] buffer, int len) {
        return parseFrame(buffer, 0, len);
    }

    /** 带起始偏移的内部版本，避免大帧反复拷贝。 */
    private static Frame parseFrame(byte[] buffer, int offset, int len) {
        if (buffer == null || len < 2 || offset < 0 || offset + len > buffer.length) return null;

        int b0 = buffer[offset] & 0xff;
        int b1 = buffer[offset + 1] & 0xff;
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long payloadLength = b1 & 0x7f;

        int pos = offset + 2;
        if (payloadLength == 126) {
            if (len < 4) return null;
            payloadLength = ((long) (buffer[offset + 2] & 0xff) << 8) | (buffer[offset + 3] & 0xff);
            pos = offset + 4;
        } else if (payloadLength == 127) {
            if (len < 10) return null;
            long high = 0;
            for (int i = 0; i < 4; i++) high = (high << 8) | (buffer[offset + 2 + i] & 0xff);
            long low = 0;
            for (int i = 0; i < 4; i++) low = (low << 8) | (buffer[offset + 6 + i] & 0xff);
            payloadLength = (high << 32) | low; // 与 server.js 的 high*2^32+low 等价
            pos = offset + 10;
        }

        byte[] mask = null;
        if (masked) {
            if (len < (pos - offset) + 4) return null;
            mask = new byte[4];
            System.arraycopy(buffer, pos, mask, 0, 4);
            pos += 4;
        }

        if (payloadLength < 0 || payloadLength > Integer.MAX_VALUE) return null; // 防御超大帧
        int plen = (int) payloadLength;
        if (len - (pos - offset) < plen) return null;

        byte[] payload = new byte[plen];
        System.arraycopy(buffer, pos, payload, 0, plen);
        if (masked) {
            for (int i = 0; i < plen; i++) payload[i] ^= mask[i & 3];
        }

        return new Frame(fin, opcode, payload, (pos - offset) + plen);
    }

    /** 服务端发送帧：不掩码，长度 126/65535 分界。对齐 server.js:742-764。 */
    public static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        writeFrame(out, opcode, payload, null);
    }

    /** 客户端发送帧：带 4 字节随机掩码（Node ws 服务端强制要求客户端掩码）。 */
    public static void sendFrameMasked(OutputStream out, int opcode, byte[] payload) throws IOException {
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        writeFrame(out, opcode, payload, mask);
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] payload, byte[] mask) throws IOException {
        int n = payload.length;
        byte[] header;
        int hlen;
        if (n <= 125) {
            hlen = 2;
            header = new byte[2];
            header[1] = (byte) n;
        } else if (n <= 65535) {
            hlen = 4;
            header = new byte[4];
            header[1] = 126;
            header[2] = (byte) (n >>> 8);
            header[3] = (byte) n;
        } else {
            hlen = 10;
            header = new byte[10];
            header[1] = 127;
            header[6] = (byte) (n >>> 24);
            header[7] = (byte) (n >>> 16);
            header[8] = (byte) (n >>> 8);
            header[9] = (byte) n;
        }
        header[0] = (byte) (0x80 | opcode);
        if (mask != null) header[1] = (byte) (header[1] | 0x80);

        out.write(header);
        if (mask == null) {
            out.write(payload);
        } else {
            byte[] wire = new byte[n];
            for (int i = 0; i < n; i++) wire[i] = (byte) (payload[i] ^ mask[i & 3]);
            out.write(mask);
            out.write(wire);
        }
        out.flush();
    }

    /** 可增长的字节累加器，支持从头部消费，用于粘包/半包处理。 */
    private static final class ByteAccumulator {
        byte[] data = new byte[8192];
        int readPos = 0;
        int writePos = 0;

        void append(byte[] b, int off, int len) {
            if (len <= 0) return;
            if (writePos + len > data.length) {
                int size = writePos - readPos;
                if (readPos > 0 && size + len <= data.length) {
                    System.arraycopy(data, readPos, data, 0, size);
                    readPos = 0;
                    writePos = size;
                } else {
                    int cap = Math.max(data.length * 2, size + len);
                    byte[] nd = new byte[cap];
                    System.arraycopy(data, readPos, nd, 0, size);
                    data = nd;
                    readPos = 0;
                    writePos = size;
                }
            }
            System.arraycopy(b, off, data, writePos, len);
            writePos += len;
        }

        int size() {
            return writePos - readPos;
        }

        void consume(int n) {
            readPos += n;
            if (readPos == writePos) {
                readPos = 0;
                writePos = 0;
            }
        }
    }

    // ====================================================================
    // 2. 认证算法（PBKDF2-HMAC-SHA256 挑战-响应）
    // ====================================================================

    public static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    (password == null ? "" : password).toCharArray(), salt, iterations, 256);
            try {
                SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                return skf.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            return null;
        }
    }

    /** hex(HMAC(PBKDF2(password, salt, iter), nonce))，对齐 collaboration.js:17-37。 */
    public static String computeProof(String password, byte[] salt, int iter, byte[] nonce) {
        byte[] key = pbkdf2(password, salt, iter);
        if (key == null) return null;
        byte[] mac = hmacSha256(key, nonce);
        return mac == null ? null : toHex(mac);
    }

    /** hex(HMAC(key, nonce || ":server"))，对齐 collaboration.js:41-46 / server.js:634。 */
    public static String computeServerProof(byte[] key, byte[] nonce) {
        byte[] suffix = ":server".getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[nonce.length + suffix.length];
        System.arraycopy(nonce, 0, data, 0, nonce.length);
        System.arraycopy(suffix, 0, data, nonce.length, suffix.length);
        byte[] mac = hmacSha256(key, data);
        return mac == null ? null : toHex(mac);
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) return null;
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }

    public static byte[] fromHex(String hex) {
        if (hex == null || (hex.length() & 1) != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static boolean isHexOfLength(String s, int len) {
        if (s == null || s.length() != len) return false;
        for (int i = 0; i < len; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** 客户端校验 challenge 并保存 key/nonce，用于计算 proof 与验证 serverProof。 */
    private static final class AuthState {
        final byte[] key;
        final byte[] nonce;

        AuthState(byte[] key, byte[] nonce) {
            this.key = key;
            this.nonce = nonce;
        }

        String proof() {
            byte[] mac = hmacSha256(key, nonce);
            return mac == null ? null : toHex(mac);
        }

        boolean verifyServerProof(String serverProof) {
            if (!isHexOfLength(serverProof, 64)) return false;
            byte[] given = fromHex(serverProof);
            byte[] expectedHex = fromHex(computeServerProof(key, nonce));
            return constantTimeEquals(given, expectedHex);
        }
    }

    private static AuthState createAuthState(String password, JSONObject challenge) {
        String nonceHex = challenge.optString("nonce", "");
        String saltHex = challenge.optString("salt", "");
        int iterations = challenge.optInt("iterations", -1);
        if (!isHexOfLength(nonceHex, 64) || !isHexOfLength(saltHex, 32)) return null;
        if (iterations < AUTH_MIN_ITERATIONS || iterations > AUTH_MAX_ITERATIONS) return null;
        byte[] salt = fromHex(saltHex);
        byte[] nonce = fromHex(nonceHex);
        byte[] key = pbkdf2(password, salt, iterations);
        if (salt == null || nonce == null || key == null) return null;
        return new AuthState(key, nonce);
    }

    // ====================================================================
    // 3. sb3 载荷解码（手写 ZIP，对齐 collaboration-project-payload.js）
    // ====================================================================

    private static final class DecodeResult {
        final JSONObject projectJSON;
        final String error;

        DecodeResult(JSONObject projectJSON, String error) {
            this.projectJSON = projectJSON;
            this.error = error;
        }

        static DecodeResult fail(String error) {
            return new DecodeResult(null, error);
        }

        static DecodeResult ok(JSONObject projectJSON) {
            return new DecodeResult(projectJSON, null);
        }
    }

    /** 公共 API：成功返回工程 JSON，失败返回 null。 */
    public static JSONObject decodeProjectPayload(String payloadJson) {
        DecodeResult r = decodeProjectPayloadResult(payloadJson);
        return r.error == null ? r.projectJSON : null;
    }

    private static DecodeResult decodeProjectPayloadResult(String payloadJson) {
        if (payloadJson == null || payloadJson.length() == 0) return DecodeResult.fail("项目数据格式无效");
        if (payloadJson.length() > MAX_PAYLOAD_TEXT_LENGTH) return DecodeResult.fail("项目数据过大");

        JSONObject value;
        try {
            value = new JSONObject(payloadJson);
        } catch (JSONException e) {
            return DecodeResult.fail("项目数据不是有效的 JSON");
        }

        if (SB3_FORMAT.equals(value.optString("format", ""))) {
            String data = value.optString("data", "");
            byte[] zip = decodeBase64Strict(data);
            if (zip == null) return DecodeResult.fail("sb3 数据不是有效的 base64");
            return extractProjectJSONFromZip(zip);
        }

        if (!isProjectSnapshot(value)) return DecodeResult.fail("缺少有效的工程结构");
        return DecodeResult.ok(value);
    }

    private static final String SB3_FORMAT = "sb3";

    private static byte[] decodeBase64Strict(String text) {
        if (text == null || text.length() == 0) return null;
        if (text.length() > MAX_PAYLOAD_TEXT_LENGTH) return null;
        if (text.length() % 4 != 0) return null;
        int pad = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=') {
                pad++;
                if (pad > 2 || i < text.length() - 2) return null;
            } else {
                if (pad > 0) return null;
                boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9') || c == '+' || c == '/';
                if (!ok) return null;
            }
        }
        try {
            return Base64.decode(text, Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    private static DecodeResult extractProjectJSONFromZip(byte[] b) {
        try {
            int eocd = findEndOfCentralDirectory(b);
            if (eocd < 0) return DecodeResult.fail("不是有效的 sb3 压缩包");

            int totalEntries = readU16(b, eocd + 10);
            if (totalEntries == 0xffff) return DecodeResult.fail("不支持 zip64 存档");
            long centralOffset = readU32(b, eocd + 16);
            if (centralOffset >= b.length) return DecodeResult.fail("sb3 压缩包结构损坏");

            int offset = (int) centralOffset;
            CentralEntry found = null;
            for (int i = 0; i < totalEntries; i++) {
                CentralEntry cur = readCentralDirectoryEntry(b, offset);
                if (cur == null) return DecodeResult.fail("sb3 压缩包结构损坏");
                if (ZIP_ENTRY_NAME.equals(cur.name)) {
                    if (found != null) return DecodeResult.fail("sb3 压缩包含有重复的 project.json");
                    found = cur;
                }
                offset = cur.nextOffset;
            }
            if (found == null) return DecodeResult.fail("sb3 压缩包缺少 project.json");

            byte[] data = readEntryData(b, found);

            JSONObject projectJSON;
            try {
                projectJSON = new JSONObject(new String(data, StandardCharsets.UTF_8));
            } catch (Exception e) {
                return DecodeResult.fail("project.json 不是有效的 JSON");
            }
            if (!isProjectSnapshot(projectJSON)) return DecodeResult.fail("project.json 缺少有效的工程结构");
            return DecodeResult.ok(projectJSON);
        } catch (ZipFailure zf) {
            return DecodeResult.fail(zf.message);
        }
    }

    /** 解压 project.json；细分错误通过 ZipFailure 携带，由 extractProjectJSONFromZip 捕获。 */
    private static byte[] readEntryData(byte[] b, CentralEntry e) {
        if (e.compressedSize == 0xffffffffL || e.localHeaderOffset == 0xffffffffL) {
            throw new ZipFailure("不支持 zip64 存档");
        }
        if (e.uncompressedSize > MAX_PROJECT_JSON_BYTES) {
            throw new ZipFailure("project.json 过大");
        }

        int local = (int) e.localHeaderOffset;
        if (local + 30 > b.length || readU32(b, local) != ZIP_LOCAL_SIGNATURE) {
            throw new ZipFailure("sb3 压缩包结构损坏");
        }
        int localNameLen = readU16(b, local + 26);
        int localExtraLen = readU16(b, local + 28);
        long dataStartL = (long) local + 30 + localNameLen + localExtraLen;
        long dataEndL = dataStartL + e.compressedSize;
        if (dataStartL > b.length || dataEndL > b.length) {
            throw new ZipFailure("sb3 压缩包结构损坏");
        }
        int dataStart = (int) dataStartL;
        int compressed = (int) e.compressedSize;

        if (e.method == 0) {
            if (compressed > MAX_PROJECT_JSON_BYTES) throw new ZipFailure("project.json 过大");
            byte[] out = new byte[compressed];
            System.arraycopy(b, dataStart, out, 0, compressed);
            return out;
        }
        if (e.method != 8) throw new ZipFailure("不支持的 sb3 压缩方式");

        Inflater inf = new Inflater(true); // raw deflate，对齐 zlib.inflateRawSync
        try {
            inf.setInput(b, dataStart, compressed);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(compressed * 2, 1 << 16));
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n;
                try {
                    n = inf.inflate(buf);
                } catch (Exception ex) {
                    throw new ZipFailure("无法解压 project.json");
                }
                if (n > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_PROJECT_JSON_BYTES) throw new ZipFailure("project.json 过大");
                } else {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                    break;
                }
            }
            return bos.toByteArray();
        } finally {
            inf.end();
        }
    }

    private static final class ZipFailure extends RuntimeException {
        final String message;

        ZipFailure(String message) {
            super(message);
            this.message = message;
        }
    }

    private static int findEndOfCentralDirectory(byte[] b) {
        if (b.length < 22) return -1;
        int searchStart = Math.max(0, b.length - 22 - 0xffff);
        for (int offset = b.length - 22; offset >= searchStart; offset--) {
            if (readU32(b, offset) != ZIP_EOCD_SIGNATURE) continue;
            int commentLength = readU16(b, offset + 20);
            if (offset + 22 + commentLength > b.length) continue;
            return offset;
        }
        return -1;
    }

    private static final class CentralEntry {
        final String name;
        final int method;
        final long compressedSize;
        final long uncompressedSize;
        final long localHeaderOffset;
        final int nextOffset;

        CentralEntry(String name, int method, long compressedSize, long uncompressedSize,
                     long localHeaderOffset, int nextOffset) {
            this.name = name;
            this.method = method;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localHeaderOffset = localHeaderOffset;
            this.nextOffset = nextOffset;
        }
    }

    private static CentralEntry readCentralDirectoryEntry(byte[] b, int offset) {
        if (offset + 46 > b.length) return null;
        if (readU32(b, offset) != ZIP_CENTRAL_SIGNATURE) return null;

        int method = readU16(b, offset + 10);
        long compressedSize = readU32(b, offset + 20);
        long uncompressedSize = readU32(b, offset + 24);
        int nameLength = readU16(b, offset + 28);
        int extraLength = readU16(b, offset + 30);
        int commentLength = readU16(b, offset + 32);
        long localHeaderOffset = readU32(b, offset + 42);

        long nameEnd = (long) offset + 46 + nameLength;
        if (nameEnd > b.length) return null;
        String name = new String(b, offset + 46, nameLength, StandardCharsets.UTF_8);
        long next = nameEnd + extraLength + commentLength;
        if (next > Integer.MAX_VALUE) return null;
        return new CentralEntry(name, method, compressedSize, uncompressedSize, localHeaderOffset, (int) next);
    }

    private static int readU16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long readU32(byte[] b, int off) {
        return ((long) (b[off] & 0xff))
                | ((long) (b[off + 1] & 0xff) << 8)
                | ((long) (b[off + 2] & 0xff) << 16)
                | ((long) (b[off + 3] & 0xff) << 24);
    }

    /** targets 非空且至少一个 isStage===true。对齐 policy.js:44-49。 */
    public static boolean isProjectSnapshot(JSONObject project) {
        if (project == null) return false;
        Object targets = project.opt("targets");
        if (!(targets instanceof JSONArray) || ((JSONArray) targets).length() == 0) return false;
        JSONArray arr = (JSONArray) targets;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t != null && Boolean.TRUE.equals(t.opt("isStage"))) return true;
        }
        return false;
    }

    // ====================================================================
    // 4. 权限策略（对齐 collaboration-project-policy.js）
    // ====================================================================

    public static final String OPERATION_DELETE_SPRITE = "deleteSprite";
    public static final String OPERATION_ADD_EXTENSION = "addExtension";
    public static final String OPERATION_DELETE_EXTENSION = "deleteExtension";
    public static final String OPERATION_CHANGE_EXTENSION_URL = "changeExtensionURL";

    private static final Set<String> CORE_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "argument", "colour", "control", "data", "event", "looks", "math",
            "motion", "operator", "procedures", "sensing", "sound")));

    private static final Map<String, String> OPERATION_LABELS = new HashMap<>();
    private static final Map<String, String> OPERATION_PERMISSIONS = new HashMap<>();
    static {
        OPERATION_LABELS.put(OPERATION_DELETE_SPRITE, "删除角色");
        OPERATION_LABELS.put(OPERATION_ADD_EXTENSION, "添加扩展");
        OPERATION_LABELS.put(OPERATION_DELETE_EXTENSION, "删除扩展");
        OPERATION_LABELS.put(OPERATION_CHANGE_EXTENSION_URL, "修改扩展来源");
        OPERATION_PERMISSIONS.put(OPERATION_DELETE_SPRITE, "allowDeleteSprite");
        OPERATION_PERMISSIONS.put(OPERATION_ADD_EXTENSION, "allowAddExtension");
        OPERATION_PERMISSIONS.put(OPERATION_DELETE_EXTENSION, "allowDeleteExtension");
        OPERATION_PERMISSIONS.put(OPERATION_CHANGE_EXTENSION_URL, "allowAddExtension");
    }

    private static boolean isPlainObject(Object v) {
        return v instanceof JSONObject;
    }

    /** 递归按 key 排序的 JSON 序列化，对齐 policy.js:57-62。 */
    public static String stableStringify(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof String) return JSONObject.quote((String) v);
        if (v instanceof Boolean) return ((Boolean) v) ? "true" : "false";
        if (v instanceof Number) return numberToString((Number) v);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(stableStringify(a.opt(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                String k = keys.get(i);
                sb.append(JSONObject.quote(k)).append(':').append(stableStringify(o.opt(k)));
            }
            return sb.append('}').toString();
        }
        return JSONObject.quote(String.valueOf(v));
    }

    private static String numberToString(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992E15) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return n.toString();
    }

    /** 忽略 name 的角色指纹。对齐 policy.js:66-73。 */
    private static String getTargetFingerprint(JSONObject target) {
        JSONObject rest = new JSONObject();
        Iterator<String> it = target.keys();
        while (it.hasNext()) {
            String k = it.next();
            if ("name".equals(k)) continue;
            try {
                rest.put(k, target.opt(k));
            } catch (JSONException ignored) {
            }
        }
        return stableStringify(rest);
    }

    private static JSONArray getTargets(JSONObject project) {
        if (project != null) {
            Object t = project.opt("targets");
            if (t instanceof JSONArray) return (JSONArray) t;
        }
        return new JSONArray();
    }

    private static List<JSONObject> getSpriteTargets(JSONObject project) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray arr = getTargets(project);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t != null && !Boolean.TRUE.equals(t.opt("isStage"))) out.add(t);
        }
        return out;
    }

    private static Set<String> getSpriteNames(JSONObject project) {
        Set<String> names = new LinkedHashSet<>();
        for (JSONObject t : getSpriteTargets(project)) {
            Object nm = t.opt("name");
            if (nm instanceof String && !((String) nm).isEmpty()) names.add((String) nm);
        }
        return names;
    }

    private static String getExtensionIdForOpcode(Object opcodeObj) {
        if (!(opcodeObj instanceof String)) return null;
        String opcode = (String) opcodeObj;
        int index = opcode.indexOf('_');
        if (index == -1) return null;
        String prefix = opcode.substring(0, index).replaceAll("[^A-Za-z0-9_-]", "-");
        if (prefix.isEmpty() || CORE_EXTENSIONS.contains(prefix)) return null;
        return prefix;
    }

    private static Map<String, String> getExtensionURLMap(JSONObject project) {
        Map<String, String> urls = new LinkedHashMap<>();
        if (isPlainObject(project) && isPlainObject(project.opt("extensionURLs"))) {
            JSONObject eu = project.optJSONObject("extensionURLs");
            Iterator<String> it = eu.keys();
            while (it.hasNext()) {
                String id = it.next();
                Object url = eu.opt(id);
                if (url instanceof String && !((String) url).isEmpty()) urls.put(id, (String) url);
            }
        }
        return urls;
    }

    private static void collectBlockExtensionIds(Object blocks, Set<String> ids) {
        if (!isPlainObject(blocks)) return;
        JSONObject bo = (JSONObject) blocks;
        Iterator<String> it = bo.keys();
        while (it.hasNext()) {
            JSONObject block = bo.optJSONObject(it.next());
            if (block == null) continue;
            String id = getExtensionIdForOpcode(block.opt("opcode"));
            if (id != null) ids.add(id);
        }
    }

    private static Set<String> getExtensionIds(JSONObject project) {
        Set<String> ids = new LinkedHashSet<>();
        if (isPlainObject(project) && project.opt("extensions") instanceof JSONArray) {
            JSONArray ext = project.optJSONArray("extensions");
            for (int i = 0; i < ext.length(); i++) {
                Object id = ext.opt(i);
                if (id instanceof String && !((String) id).isEmpty()) ids.add((String) id);
            }
        }
        if (isPlainObject(project) && isPlainObject(project.opt("extensionURLs"))) {
            JSONObject eu = project.optJSONObject("extensionURLs");
            Iterator<String> it = eu.keys();
            while (it.hasNext()) {
                String id = it.next();
                if (!id.isEmpty()) ids.add(id);
            }
        }
        // 与 scratch-vm 一致：扫描所有 targets（含舞台）的 blocks 与 monitors
        JSONArray allTargets = getTargets(project);
        for (int i = 0; i < allTargets.length(); i++) {
            JSONObject t = allTargets.optJSONObject(i);
            if (t != null) collectBlockExtensionIds(t.opt("blocks"), ids);
        }
        if (isPlainObject(project) && project.opt("monitors") instanceof JSONArray) {
            JSONArray monitors = project.optJSONArray("monitors");
            for (int i = 0; i < monitors.length(); i++) {
                JSONObject monitor = monitors.optJSONObject(i);
                if (monitor == null) continue;
                String id = getExtensionIdForOpcode(monitor.opt("opcode"));
                if (id != null) ids.add(id);
            }
        }
        return ids;
    }

    private static void addOperation(JSONArray ops, String operation, String detail) {
        try {
            ops.put(new JSONObject().put("operation", operation).put("detail", detail == null ? "" : detail));
        } catch (JSONException ignored) {
        }
    }

    /** 语义 diff，对齐 policy.js:164-215。 */
    public static JSONArray diffProjectStates(JSONObject previous, JSONObject next) {
        JSONArray operations = new JSONArray();

        List<JSONObject> previousTargets = getSpriteTargets(previous);
        List<JSONObject> nextTargets = getSpriteTargets(next);
        Set<String> nextNames = getSpriteNames(next);
        Set<String> previousNames = new LinkedHashSet<>();
        for (JSONObject t : previousTargets) {
            Object nm = t.opt("name");
            if (nm instanceof String && !((String) nm).isEmpty()) previousNames.add((String) nm);
        }

        List<JSONObject> missingTargets = new ArrayList<>();
        for (JSONObject t : previousTargets) {
            Object nm = t.opt("name");
            if (nm instanceof String && !((String) nm).isEmpty() && !nextNames.contains(nm)) {
                missingTargets.add(t);
            }
        }
        if (!missingTargets.isEmpty()) {
            Map<String, String> nextNameByFingerprint = new HashMap<>();
            for (JSONObject t : nextTargets) {
                Object nm = t.opt("name");
                nextNameByFingerprint.put(getTargetFingerprint(t), nm instanceof String ? (String) nm : "");
            }
            for (JSONObject t : missingTargets) {
                String matchName = nextNameByFingerprint.get(getTargetFingerprint(t));
                if (matchName != null && !previousNames.contains(matchName)) continue; // 改名
                Object nm = t.opt("name");
                addOperation(operations, OPERATION_DELETE_SPRITE, nm instanceof String ? (String) nm : "");
            }
        }

        Set<String> previousExtensions = getExtensionIds(previous);
        Set<String> nextExtensions = getExtensionIds(next);
        for (String id : nextExtensions) {
            if (!previousExtensions.contains(id)) addOperation(operations, OPERATION_ADD_EXTENSION, id);
        }
        for (String id : previousExtensions) {
            if (!nextExtensions.contains(id)) addOperation(operations, OPERATION_DELETE_EXTENSION, id);
        }

        Map<String, String> previousURLs = getExtensionURLMap(previous);
        Map<String, String> nextURLs = getExtensionURLMap(next);
        for (Map.Entry<String, String> e : nextURLs.entrySet()) {
            if (previousURLs.containsKey(e.getKey()) && !previousURLs.get(e.getKey()).equals(e.getValue())) {
                addOperation(operations, OPERATION_CHANGE_EXTENSION_URL, e.getKey());
            }
        }

        return operations;
    }

    private static String describeOperations(JSONArray operations) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < operations.length(); i++) {
            JSONObject op = operations.optJSONObject(i);
            if (op == null) continue;
            String label = OPERATION_LABELS.get(op.optString("operation", ""));
            if (label != null && !labels.contains(label)) labels.add(label);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) sb.append('、');
            sb.append(labels.get(i));
        }
        return sb.toString();
    }

    private static JSONObject decision(boolean allowed, String reason, String message,
                                       JSONArray operations, JSONArray violations) {
        try {
            return new JSONObject()
                    .put("allowed", allowed)
                    .put("reason", reason)
                    .put("message", message)
                    .put("operations", operations == null ? new JSONArray() : operations)
                    .put("violations", violations == null ? new JSONArray() : violations);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** 权限判定，对齐 policy.js:236-296。 */
    public static JSONObject evaluateProjectUpdate(JSONObject previous, JSONObject next, JSONObject permissions) {
        if (!isProjectSnapshot(next)) {
            return decision(false, "invalid-project", "项目数据无效，已拒绝同步", null, null);
        }
        if (!isProjectSnapshot(previous)) {
            return decision(false, "not-synchronized", "房间项目尚未同步完成，请稍后重试", null, null);
        }

        JSONObject perms = isPlainObject(permissions) ? permissions : new JSONObject();

        if (Boolean.FALSE.equals(perms.opt("allowEdit"))) {
            if (!stableStringify(previous).equals(stableStringify(next))) {
                return decision(false, "edit-disabled", "主机不允许协作者修改项目", null, null);
            }
            return decision(true, "ok", "", null, null);
        }

        JSONArray operations = diffProjectStates(previous, next);
        if (operations.length() == 0) {
            return decision(true, "ok", "", null, null);
        }

        JSONArray violations = new JSONArray();
        for (int i = 0; i < operations.length(); i++) {
            JSONObject op = operations.optJSONObject(i);
            if (op == null) continue;
            String permission = OPERATION_PERMISSIONS.get(op.optString("operation", ""));
            if (permission != null && Boolean.FALSE.equals(perms.opt(permission))) {
                violations.put(op);
            }
        }

        if (violations.length() > 0) {
            return decision(false, "permission-denied",
                    "无权限执行：" + describeOperations(violations), operations, violations);
        }
        return decision(true, "ok", "", operations, null);
    }

    // ====================================================================
    // 通用工具
    // ====================================================================

    private static JSONObject putNullable(JSONObject o, String key, Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static byte[] sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String randomUUID() {
        return UUID.randomUUID().toString();
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null && !s.isClosed()) s.close();
        } catch (IOException ignored) {
        }
    }

    // ====================================================================
    // 5. Host（WebSocket 服务端）
    // ====================================================================

    public interface HostCallback {
        void onClientJoin(String username, String avatar);

        void onClientLeave(String username);

        void onChatMessage(JSONObject message);

        void onProjectRequest(String username);

        void onProjectUpdate(JSONObject data);

        void onProjectUpdateRejected(String username, JSONObject decision);
    }

    public static class Host {
        private static final int MAX_BUFFER_BYTES = 64 * 1024 * 1024;
        private static final long HEARTBEAT_INTERVAL_MS = 30000;
        private static final long HEARTBEAT_TIMEOUT_MS = 65000;
        private static final long AUTH_TIMEOUT_MS = 30000;

        private final int port;
        private final String password;
        private final JSONObject permissions;
        private final String hostName;
        private final String hostAvatar;
        private final HostCallback cb;
        private final String id;
        private final AtomicInteger clientCounter = new AtomicInteger(0);

        private ServerSocket serverSocket;
        private volatile boolean running = false;
        private final ConcurrentHashMap<Socket, Client> clients = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, IpFail> ipFails = new ConcurrentHashMap<>();
        private ScheduledExecutorService scheduler;
        private Thread acceptThread;
        private Thread heartbeatThread;

        private final Object projectLock = new Object();
        private String roomProjectPayload;
        private JSONObject roomProject;
        private String roomProjectDecodeError;

        public Host(int port, String password, JSONObject permissions, String hostName,
                    String hostAvatar, HostCallback cb) {
            this.port = port;
            this.password = password == null ? "" : password;
            this.permissions = permissions;
            this.hostName = (hostName == null || hostName.trim().isEmpty()) ? "主机" : hostName;
            this.hostAvatar = (hostAvatar == null || hostAvatar.isEmpty()) ? "🏠" : hostAvatar;
            this.cb = cb;
            this.id = randomUUID();
        }

        public boolean start() {
            if (password.isEmpty()) return false;
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));
            } catch (IOException e) {
                return false; // 端口占用等
            }
            running = true;
            scheduler = Executors.newScheduledThreadPool(2);
            heartbeatThread = new Thread(this::heartbeatLoop, "nw-host-heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
            acceptThread = new Thread(this::acceptLoop, "nw-host-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            return true;
        }

        private void acceptLoop() {
            while (running) {
                try {
                    final Socket s = serverSocket.accept();
                    Thread t = new Thread(() -> handleConnection(s), "nw-host-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    if (running) {
                        // 临时接受失败，继续；端口关闭时 running 已置 false
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    } else {
                        return;
                    }
                }
            }
        }

        /** 读 HTTP 头（逐字节，避免越过 \r\n\r\n 吞掉帧数据）。 */
        private static String readHttpHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            int last = 0;
            while (true) {
                int c = in.read();
                if (c < 0) throw new IOException("eof in headers");
                hb.write(c);
                last = ((last << 8) | c) & 0xffffffff;
                if (last == 0x0d0a0d0a) break;
                if (hb.size() > 65536) throw new IOException("headers too large");
            }
            return new String(hb.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        private void handleConnection(Socket s) {
            String ip = s.getInetAddress().getHostAddress();
            try {
                s.setSoTimeout(10000);
                String header = readHttpHeaders(s.getInputStream());
                String[] lines = header.split("\r\n");
                if (lines.length == 0) {
                    closeQuietly(s);
                    return;
                }
                String[] request = lines[0].split(" ");
                String method = request.length > 0 ? request[0] : "";
                String target = request.length > 1 ? request[1] : "/";

                Map<String, String> headers = new HashMap<>();
                for (int i = 1; i < lines.length; i++) {
                    int idx = lines[i].indexOf(':');
                    if (idx > 0) {
                        headers.put(lines[i].substring(0, idx).trim().toLowerCase(Locale.US),
                                lines[i].substring(idx + 1).trim());
                    }
                }

                int q = target.indexOf('?');
                String pathname = q < 0 ? target : target.substring(0, q);
                String query = q < 0 ? "" : target.substring(q + 1);
                boolean discover = "/discover".equals(pathname);
                if (!discover) {
                    for (String param : query.split("&")) {
                        if ("discover=1".equals(param)) {
                            discover = true;
                            break;
                        }
                    }
                }

                OutputStream out = s.getOutputStream();
                if (discover) {
                    byte[] body = getDiscoveryInfo().toString().getBytes(StandardCharsets.UTF_8);
                    String resp = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json; charset=utf-8\r\n"
                            + "Access-Control-Allow-Origin: *\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(resp.getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                    closeQuietly(s);
                    return;
                }

                String upgrade = headers.get("upgrade");
                String version = headers.get("sec-websocket-version");
                String key = headers.get("sec-websocket-key");
                if (upgrade == null || !upgrade.toLowerCase(Locale.US).contains("websocket")
                        || !"13".equals(version) || !"/".equals(pathname) || key == null) {
                    closeQuietly(s);
                    return;
                }

                String accept = Base64.encodeToString(sha1(key + MAGIC_STRING), Base64.NO_WRAP);
                String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(resp.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                Client client = new Client(s, ip, out);
                clients.put(s, client);
                client.authTimeout = scheduler.schedule(() -> {
                    if (!client.authenticated) closeClient(client);
                }, AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                s.setSoTimeout(30000);
                readClientLoop(client);
            } catch (Exception e) {
                closeQuietly(s);
            }
        }

        private void readClientLoop(Client client) {
            ByteAccumulator acc = client.acc;
            byte[] tmp = new byte[8192];
            while (running && !client.closed) {
                try {
                    int n = client.socket.getInputStream().read(tmp);
                    if (n < 0) {
                        handleClose(client);
                        break;
                    }
                    client.lastSeen = System.currentTimeMillis();
                    acc.append(tmp, 0, n);
                    if (acc.size() > MAX_BUFFER_BYTES) {
                        handleClose(client);
                        break;
                    }
                    Frame frame;
                    while ((frame = parseFrame(acc.data, acc.readPos, acc.size())) != null) {
                        acc.consume(frame.bytesRead);
                        handleFrame(client, frame);
                        if (client.closed) break;
                    }
                } catch (SocketTimeoutException e) {
                    continue; // 由心跳负责判定失联
                } catch (IOException e) {
                    handleClose(client);
                    break;
                }
            }
        }

        private void handleFrame(Client client, Frame frame) {
            int opcode = frame.opcode;
            if (opcode == OPCODE_CLOSE) {
                handleClose(client);
                return;
            }
            if (opcode == OPCODE_PING) {
                try {
                    synchronized (client.sendLock) {
                        sendFrame(client.out, OPCODE_PONG, frame.payload);
                    }
                } catch (IOException ignored) {
                }
                return;
            }
            if (opcode == OPCODE_PONG) {
                client.lastSeen = System.currentTimeMillis();
                return;
            }
            if (opcode == OPCODE_TEXT) {
                String text = new String(frame.payload, StandardCharsets.UTF_8);
                handleMessage(client, text);
            }
        }

        private void handleMessage(Client client, String text) {
            JSONObject message;
            try {
                message = new JSONObject(text);
            } catch (JSONException e) {
                return;
            }

            String type = message.optString("type", "");
            if ("ping".equals(type)) {
                client.lastSeen = System.currentTimeMillis();
                try {
                    sendJson(client, putNullable(new JSONObject()
                            .put("type", "pong"), "onlineCount", getTotalOnlineCount()));
                } catch (JSONException ignored) {
                }
                return;
            }
            if ("auth-init".equals(type)) {
                handleAuthInit(client, message);
                return;
            }
            if ("auth".equals(type)) {
                handleAuthProof(client, message);
                return;
            }
            if (!client.authenticated) return;

            if ("chat".equals(type)) {
                long timestamp = System.currentTimeMillis();
                Object textObj = message.opt("text");
                String chatText = (textObj == null || textObj == JSONObject.NULL) ? "" : String.valueOf(textObj);
                JSONObject chatData = new JSONObject();
                try {
                    chatData.put("type", "chat")
                            .put("from", client.username)
                            .put("avatar", client.avatar)
                            .put("text", chatText)
                            .put("timestamp", timestamp);
                } catch (JSONException ignored) {
                }
                for (Client other : clients.values()) {
                    if (other == client || !other.authenticated) continue;
                    sendJson(other, withSelf(chatData, false));
                }
                sendJson(client, withSelf(chatData, true));
                if (cb != null) cb.onChatMessage(withSelf(chatData, false));
                return;
            }

            if ("project-update".equals(type)) {
                Object proj = message.opt("project");
                String payload = proj instanceof String ? (String) proj
                        : (proj instanceof JSONObject ? proj.toString() : "");
                JSONObject decision = authorizeParticipantProjectUpdate(payload);
                if (!decision.optBoolean("allowed")) {
                    rejectProjectUpdate(client, decision);
                    return;
                }
                JSONObject updateData = new JSONObject();
                try {
                    updateData.put("type", "project-update")
                            .put("project", payload)
                            .put("from", client.username);
                } catch (JSONException ignored) {
                }
                for (Client other : clients.values()) {
                    if (other == client || !other.authenticated) continue;
                    sendJson(other, updateData);
                }
                if (cb != null) cb.onProjectUpdate(updateData);
            }
        }

        private static JSONObject withSelf(JSONObject base, boolean self) {
            try {
                JSONObject copy = new JSONObject(base.toString());
                copy.put("isSelf", self);
                return copy;
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        private void handleAuthInit(Client client, JSONObject message) {
            if (client.authenticated) return;
            if (getAuthBlockRemaining(client.ip) > 0) {
                sendAuthFailure(client, "尝试次数过多，请稍后再试", true, false);
                return;
            }
            client.authInitCount++;
            if (client.authInitCount > AUTH_MAX_INIT_PER_CONNECTION) {
                sendAuthFailure(client, "认证请求过于频繁", true, false);
                return;
            }

            String nickname = String.valueOf(message.opt("nickname") == null ? "" : message.opt("nickname")).trim();
            String avatar = String.valueOf(message.opt("avatar") == null ? "" : message.opt("avatar")).trim();
            if (avatar.isEmpty()) avatar = "👤";

            byte[] nonce = new byte[AUTH_NONCE_BYTES];
            byte[] salt = new byte[AUTH_SALT_BYTES];
            RNG.nextBytes(nonce);
            RNG.nextBytes(salt);
            client.authChallenge = new Challenge(nonce, salt, AUTH_KDF_ITERATIONS,
                    System.currentTimeMillis() + AUTH_CHALLENGE_TTL_MS);
            client.authNickname = nickname;
            client.authAvatar = avatar;

            try {
                JSONObject challengeMsg = new JSONObject()
                        .put("type", "auth-challenge")
                        .put("version", AUTH_PROTOCOL_VERSION)
                        .put("nonce", toHex(nonce))
                        .put("salt", toHex(salt))
                        .put("iterations", AUTH_KDF_ITERATIONS);
                sendJson(client, challengeMsg);
            } catch (JSONException ignored) {
            }
        }

        private void handleAuthProof(Client client, JSONObject message) {
            if (client.authenticated) return;
            Challenge challenge = client.authChallenge;
            client.authChallenge = null; // challenge 单次使用
            if (challenge == null) {
                sendAuthFailure(client, "认证请求无效，请重试", false, true);
                return;
            }
            if (System.currentTimeMillis() > challenge.expiresAt) {
                sendAuthFailure(client, "认证超时，请重试", false, true);
                return;
            }

            Object proofObj = message.opt("proof");
            String proofHex = proofObj instanceof String ? (String) proofObj : "";
            if (!isHexOfLength(proofHex, 64)) {
                sendAuthFailure(client, "密码错误", false, true);
                return;
            }

            byte[] key = pbkdf2(password, challenge.salt, challenge.iterations);
            if (key == null) {
                sendAuthFailure(client, "认证失败", false, false);
                return;
            }
            if (client.closed) return;

            byte[] expected = hmacSha256(key, challenge.nonce);
            byte[] given = fromHex(proofHex);
            if (!constantTimeEquals(given, expected)) {
                sendAuthFailure(client, "密码错误", false, true);
                return;
            }

            ipFails.remove(client.ip);
            client.authenticated = true;
            if (client.authTimeout != null) {
                client.authTimeout.cancel(false);
                client.authTimeout = null;
            }
            int num = 1000 + RNG.nextInt(9000);
            client.username = getUniqueUsername(client.authNickname.isEmpty() ? "用户-" + num : client.authNickname);
            client.avatar = client.authAvatar.isEmpty() ? "👤" : client.authAvatar;

            String serverProof = computeServerProof(key, challenge.nonce);
            try {
                JSONObject result = new JSONObject()
                        .put("type", "auth-result")
                        .put("success", true)
                        .put("serverProof", serverProof)
                        .put("username", client.username)
                        .put("avatar", client.avatar);
                putNullable(result, "permissions", permissions);
                result.put("onlineCount", getTotalOnlineCount())
                        .put("hostName", hostName)
                        .put("hostAvatar", hostAvatar)
                        .put("members", getRoster());
                sendJson(client, result);
            } catch (JSONException ignored) {
            }

            for (Client other : clients.values()) {
                if (other == client || !other.authenticated) continue;
                try {
                    sendJson(other, new JSONObject()
                            .put("type", "member-joined")
                            .put("username", client.username)
                            .put("avatar", client.avatar)
                            .put("onlineCount", getTotalOnlineCount())
                            .put("members", getRoster()));
                } catch (JSONException ignored) {
                }
            }

            if (cb != null) cb.onClientJoin(client.username, client.avatar);
            if (cb != null) cb.onProjectRequest(client.username);
        }

        private void sendAuthFailure(Client client, String error, boolean destroy, boolean countFailure) {
            if (client.closed) return;
            int failures = 0;
            if (countFailure) {
                client.authFailures++;
                failures = client.authFailures;
                IpFail entry = ipFails.get(client.ip);
                if (entry == null) entry = new IpFail();
                entry.count++;
                entry.lastFailure = System.currentTimeMillis();
                if (entry.count >= AUTH_MAX_FAILURES_PER_IP) {
                    entry.count = 0;
                    entry.blockedUntil = System.currentTimeMillis() + AUTH_IP_BLOCK_MS;
                }
                ipFails.put(client.ip, entry);
            }

            final int f = failures;
            try {
                JSONObject response = new JSONObject()
                        .put("type", "auth-result")
                        .put("success", false)
                        .put("error", error);
                putNullable(response, "permissions", permissions);
                response.put("username", JSONObject.NULL);
                final String data = response.toString();
                if (scheduler != null) {
                    scheduler.schedule(() -> {
                        if (client.closed) return;
                        sendToClient(client, data);
                        if (destroy || f >= AUTH_MAX_TRIES_PER_CONNECTION) closeClient(client);
                    }, AUTH_FAILURE_DELAY_MS, TimeUnit.MILLISECONDS);
                }
            } catch (JSONException ignored) {
            }
        }

        private long getAuthBlockRemaining(String ip) {
            IpFail entry = ipFails.get(ip);
            if (entry == null || entry.blockedUntil == 0) return 0;
            long remaining = entry.blockedUntil - System.currentTimeMillis();
            if (remaining <= 0) {
                ipFails.remove(ip);
                return 0;
            }
            return remaining;
        }

        private String getUniqueUsername(String base) {
            Set<String> taken = new HashSet<>();
            for (Client c : clients.values()) {
                if (c.authenticated && c.username != null) taken.add(c.username);
            }
            if (!taken.contains(base)) return base;
            int i = 2;
            while (taken.contains(base + "-" + i)) i++;
            return base + "-" + i;
        }

        private void sendJson(Client client, JSONObject json) {
            sendToClient(client, json.toString());
        }

        private void sendToClient(Client client, String message) {
            if (client.closed) return;
            try {
                synchronized (client.sendLock) {
                    sendFrame(client.out, OPCODE_TEXT, message.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        }

        private JSONObject getRoomProject() {
            synchronized (projectLock) {
                if (roomProject != null) return roomProject;
                if (roomProjectPayload == null) return null;
                DecodeResult d = decodeProjectPayloadResult(roomProjectPayload);
                roomProjectPayload = null; // 载荷可能几十 MB，解完立即释放
                if (d.error != null) {
                    roomProjectDecodeError = d.error;
                    return null;
                }
                roomProject = d.projectJSON;
                roomProjectDecodeError = null;
                return roomProject;
            }
        }

        private JSONObject authorizeParticipantProjectUpdate(String payload) {
            DecodeResult d = decodeProjectPayloadResult(payload);
            if (d.error != null) {
                return decision(false, "invalid-project", "项目数据无效：" + d.error, null, null);
            }

            JSONObject previous = getRoomProject();
            if (previous == null) {
                String err;
                synchronized (projectLock) {
                    err = roomProjectDecodeError;
                }
                if (err != null) {
                    return decision(false, "host-state-unavailable", "主机项目状态不可用，已拒绝同步", null, null);
                }
                return decision(false, "not-synchronized", "房间项目尚未同步完成，请稍后重试", null, null);
            }

            JSONObject result = evaluateProjectUpdate(previous, d.projectJSON, permissions);
            if (result.optBoolean("allowed")) {
                synchronized (projectLock) {
                    roomProjectPayload = null;
                    roomProject = d.projectJSON;
                    roomProjectDecodeError = null;
                }
            }
            return result;
        }

        private void rejectProjectUpdate(Client client, JSONObject decision) {
            JSONArray ops = new JSONArray();
            JSONArray violations = decision.optJSONArray("violations");
            if (violations != null) {
                for (int i = 0; i < violations.length(); i++) {
                    JSONObject v = violations.optJSONObject(i);
                    if (v != null) {
                        ops.put(v.optString("operation", ""));
                    }
                }
            }
            try {
                sendJson(client, new JSONObject()
                        .put("type", "project-update-rejected")
                        .put("reason", decision.optString("reason", ""))
                        .put("message", decision.optString("message", ""))
                        .put("operations", ops));
            } catch (JSONException ignored) {
            }
            if (cb != null) cb.onProjectUpdateRejected(client.username, decision);

            long now = System.currentTimeMillis();
            if (now - client.lastResyncAt > 5000) {
                client.lastResyncAt = now;
                if (cb != null) cb.onProjectRequest(client.username);
            }
        }

        // ---- Host 公共 API ----

        public JSONObject getDiscoveryInfo() {
            try {
                return new JSONObject()
                        .put("ok", true)
                        .put("id", id)
                        .put("roomName", hostName + " 的协作")
                        .put("hostName", hostName)
                        .put("hostAvatar", hostAvatar)
                        .put("onlineCount", getTotalOnlineCount())
                        .put("port", port);
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        public JSONArray getRoster() {
            JSONArray roster = new JSONArray();
            try {
                roster.put(new JSONObject().put("name", hostName).put("avatar", hostAvatar).put("role", "host"));
                for (Client c : clients.values()) {
                    if (!c.authenticated) continue;
                    roster.put(new JSONObject().put("name", c.username).put("avatar", c.avatar).put("role", "participant"));
                }
            } catch (JSONException ignored) {
            }
            return roster;
        }

        public int getOnlineCount() {
            int count = 0;
            for (Client c : clients.values()) if (c.authenticated) count++;
            return count;
        }

        public int getTotalOnlineCount() {
            return getOnlineCount() + 1;
        }

        public void broadcastProject(String projectJson, String targetUsername) {
            if (projectJson == null) return;
            synchronized (projectLock) {
                roomProjectPayload = projectJson;
                roomProject = null;
                roomProjectDecodeError = null;
            }
            try {
                String data = new JSONObject().put("type", "project-update").put("project", projectJson).toString();
                for (Client c : clients.values()) {
                    if (!c.authenticated) continue;
                    if (targetUsername != null && !targetUsername.equals(c.username)) continue;
                    sendToClient(c, data);
                }
            } catch (JSONException ignored) {
            }
        }

        public void sendChat(String text) {
            long timestamp = System.currentTimeMillis();
            try {
                JSONObject data = new JSONObject()
                        .put("type", "chat")
                        .put("from", hostName)
                        .put("avatar", hostAvatar)
                        .put("text", text == null ? "" : text)
                        .put("timestamp", timestamp);
                for (Client c : clients.values()) {
                    if (!c.authenticated) continue;
                    sendJson(c, withSelf(data, false));
                }
                if (cb != null) cb.onChatMessage(withSelf(data, true));
            } catch (JSONException ignored) {
            }
        }

        public void end() {
            if (!running && serverSocket == null) return;
            running = false;
            if (scheduler != null) scheduler.shutdownNow();

            try {
                String endMessage = new JSONObject()
                        .put("type", "collaboration-ended")
                        .put("reason", "host-ended").toString();
                for (Client c : clients.values()) sendToClient(c, endMessage);
            } catch (JSONException ignored) {
            }
            for (Client c : clients.values()) closeClient(c);
            clients.clear();
            ipFails.clear();
            synchronized (projectLock) {
                roomProjectPayload = null;
                roomProject = null;
                roomProjectDecodeError = null;
            }
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void heartbeatLoop() {
            while (running) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) return;
                long now = System.currentTimeMillis();

                Iterator<Map.Entry<String, IpFail>> it = ipFails.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, IpFail> e = it.next();
                    IpFail entry = e.getValue();
                    boolean expired = entry.blockedUntil <= now;
                    boolean idle = now - entry.lastFailure > 10 * 60 * 1000;
                    if (expired && idle) it.remove();
                }

                for (Client c : clients.values()) {
                    if (c.closed) continue;
                    if (now - c.lastSeen > HEARTBEAT_TIMEOUT_MS) {
                        handleClose(c);
                    } else {
                        try {
                            synchronized (c.sendLock) {
                                sendFrame(c.out, OPCODE_PING, new byte[0]);
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        private void closeClient(Client c) {
            if (c.closed) return;
            c.closed = true;
            clients.remove(c.socket, c);
            if (c.authTimeout != null) {
                c.authTimeout.cancel(false);
                c.authTimeout = null;
            }
            c.authChallenge = null;
            closeQuietly(c.socket);
        }

        private void handleClose(Client c) {
            if (c.closed) return;
            boolean wasAuthenticated = c.authenticated;
            String username = c.username;
            closeClient(c);
            if (!wasAuthenticated) return;

            for (Client other : clients.values()) {
                if (!other.authenticated) continue;
                try {
                    sendJson(other, new JSONObject()
                            .put("type", "member-left")
                            .put("username", username)
                            .put("onlineCount", getTotalOnlineCount())
                            .put("members", getRoster()));
                } catch (JSONException ignored) {
                }
            }
            if (cb != null) cb.onClientLeave(username);
        }

        // ---- Host 内部结构 ----

        private static final class Challenge {
            final byte[] nonce;
            final byte[] salt;
            final int iterations;
            final long expiresAt;

            Challenge(byte[] nonce, byte[] salt, int iterations, long expiresAt) {
                this.nonce = nonce;
                this.salt = salt;
                this.iterations = iterations;
                this.expiresAt = expiresAt;
            }
        }

        private static final class IpFail {
            int count = 0;
            long blockedUntil = 0;
            long lastFailure = 0;
        }

        private final class Client {
            final Socket socket;
            final String ip;
            final OutputStream out;
            final ByteAccumulator acc = new ByteAccumulator();
            final Object sendLock = new Object();
            final int id;

            volatile boolean closed = false;
            volatile boolean authenticated = false;
            volatile long lastSeen = System.currentTimeMillis();
            volatile String username;
            volatile String avatar;
            volatile String authNickname = "";
            volatile String authAvatar = "";
            volatile Challenge authChallenge;
            volatile int authFailures = 0;
            volatile int authInitCount = 0;
            volatile long lastResyncAt = 0;
            ScheduledFuture<?> authTimeout;

            Client(Socket socket, String ip, OutputStream out) {
                this.socket = socket;
                this.ip = ip;
                this.out = out;
                this.id = clientCounter.incrementAndGet();
            }
        }
    }

    // ====================================================================
    // 6. Join（WebSocket 客户端）
    // ====================================================================

    public interface JoinCallback {
        void onConnected(JSONObject authResult);

        void onChatMessage(JSONObject message);

        void onProjectUpdate(JSONObject data);

        void onPermissionDenied(JSONObject message);

        void onMemberJoined(JSONObject message);

        void onMemberLeft(JSONObject message);

        void onEnded(String reason);

        void onError(String error);
    }

    public static class Join {
        private static final int CONNECT_TIMEOUT_MS = 10000;
        private static final long PING_INTERVAL_MS = 25000;
        private static final long PONG_TIMEOUT_MS = 70000;

        private final String ip;
        private final int port;
        private final String password;
        private final String nickname;
        private final String avatar;
        private final JoinCallback cb;

        private final Object sendLock = new Object();
        private final ByteAccumulator acc = new ByteAccumulator();

        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private volatile boolean closed = false;
        private volatile boolean connected = false;

        // 握手状态
        private volatile boolean handshakeDone = false;
        private volatile boolean handshakeSuccess = false;
        private volatile String handshakeError = null;
        private volatile AuthState authState;
        private volatile JSONObject authResult;

        private volatile long lastPongTime = 0;
        private Thread readerThread;
        private Thread heartbeatThread;

        public Join(String ip, int port, String password, String nickname, String avatar, JoinCallback cb) {
            this.ip = ip;
            this.port = port;
            this.password = password;
            this.nickname = nickname == null ? "" : nickname.trim();
            this.avatar = (avatar == null || avatar.isEmpty()) ? "👤" : avatar;
            this.cb = cb;
        }

        /** 阻塞握手，成功返回 true；失败返回 false 并通过 onError 回调。 */
        public boolean connect() {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                in = socket.getInputStream();
                out = socket.getOutputStream();

                byte[] keyBytes = new byte[16];
                RNG.nextBytes(keyBytes);
                String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
                String request = "GET / HTTP/1.1\r\n"
                        + "Host: " + ip + ":" + port + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: " + key + "\r\n"
                        + "Sec-WebSocket-Version: 13\r\n\r\n";
                out.write(request.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String header = readHttpHeaders(in);
                if (!header.startsWith("HTTP/1.1 101") && !header.startsWith("HTTP/1.0 101")) {
                    fail("连接失败");
                    return false;
                }
                String expectedAccept = Base64.encodeToString(sha1(key + MAGIC_STRING), Base64.NO_WRAP);
                boolean acceptOK = false;
                for (String line : header.split("\r\n")) {
                    int idx = line.indexOf(':');
                    if (idx > 0 && "sec-websocket-accept".equals(line.substring(0, idx).trim().toLowerCase(Locale.US))) {
                        acceptOK = expectedAccept.equals(line.substring(idx + 1).trim());
                        break;
                    }
                }
                if (!acceptOK) {
                    fail("房间身份校验失败，请确认网络环境");
                    return false;
                }

                sendJson(new JSONObject().put("type", "auth-init")
                        .put("nickname", nickname).put("avatar", avatar));

                byte[] tmp = new byte[8192];
                while (!closed && !handshakeDone) {
                    int n;
                    try {
                        n = in.read(tmp);
                    } catch (SocketTimeoutException e) {
                        handshakeError = "连接超时";
                        break;
                    } catch (IOException e) {
                        handshakeError = "连接失败";
                        break;
                    }
                    if (n < 0) {
                        handshakeError = "连接已断开";
                        break;
                    }
                    acc.append(tmp, 0, n);
                    Frame frame;
                    while ((frame = parseFrame(acc.data, acc.readPos, acc.size())) != null) {
                        acc.consume(frame.bytesRead);
                        handleFrame(frame);
                        if (handshakeDone) break;
                    }
                }

                if (!handshakeSuccess) {
                    fail(handshakeError == null ? "认证失败" : handshakeError);
                    return false;
                }

                socket.setSoTimeout(30000);
                connected = true;
                lastPongTime = System.currentTimeMillis();
                readerThread = new Thread(this::readerLoop, "nw-join-reader");
                readerThread.setDaemon(true);
                readerThread.start();
                heartbeatThread = new Thread(this::heartbeatLoop, "nw-join-heartbeat");
                heartbeatThread.setDaemon(true);
                heartbeatThread.start();
                if (cb != null) cb.onConnected(authResult);
                return true;
            } catch (Exception e) {
                fail("连接失败");
                return false;
            }
        }

        private static String readHttpHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            int last = 0;
            while (true) {
                int c = in.read();
                if (c < 0) throw new IOException("eof in headers");
                hb.write(c);
                last = ((last << 8) | c) & 0xffffffff;
                if (last == 0x0d0a0d0a) break;
                if (hb.size() > 65536) throw new IOException("headers too large");
            }
            return new String(hb.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        private void fail(String error) {
            closeQuietly(socket);
            closed = true;
            if (cb != null) cb.onError(error);
        }

        private void handleFrame(Frame frame) {
            int opcode = frame.opcode;
            if (opcode == OPCODE_CLOSE) {
                onConnectionLost();
                return;
            }
            if (opcode == OPCODE_PING) {
                try {
                    synchronized (sendLock) {
                        sendFrameMasked(out, OPCODE_PONG, frame.payload);
                    }
                } catch (IOException ignored) {
                }
                return;
            }
            if (opcode == OPCODE_PONG) {
                lastPongTime = System.currentTimeMillis();
                return;
            }
            if (opcode != OPCODE_TEXT) return;

            JSONObject message;
            try {
                message = new JSONObject(new String(frame.payload, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                return;
            }
            String type = message.optString("type", "");
            if ("auth-challenge".equals(type) || "auth-result".equals(type)) {
                handleHandshakeMessage(message);
                return;
            }
            routeMessage(type, message);
        }

        private void handleHandshakeMessage(JSONObject message) {
            if (handshakeDone) return;
            String type = message.optString("type", "");
            if ("auth-challenge".equals(type)) {
                authState = createAuthState(password, message);
                if (authState == null) {
                    handshakeDone = true;
                    handshakeSuccess = false;
                    handshakeError = "认证协议不兼容，请升级 NeoWarp";
                    return;
                }
                try {
                    sendJson(new JSONObject().put("type", "auth").put("proof", authState.proof()));
                } catch (Exception e) {
                    handshakeDone = true;
                    handshakeSuccess = false;
                    handshakeError = "连接失败";
                }
                return;
            }
            if ("auth-result".equals(type)) {
                handshakeDone = true;
                if (message.optBoolean("success")) {
                    if (authState == null || !authState.verifyServerProof(message.optString("serverProof", ""))) {
                        handshakeSuccess = false;
                        handshakeError = "房间身份校验失败，请确认网络环境";
                        return;
                    }
                    handshakeSuccess = true;
                    authResult = message;
                } else {
                    handshakeSuccess = false;
                    handshakeError = message.optString("error", "认证失败");
                }
            }
        }

        private void routeMessage(String type, JSONObject message) {
            if (cb == null) return;
            switch (type) {
                case "chat":
                    cb.onChatMessage(message);
                    break;
                case "project-update":
                    try {
                        Object proj = message.opt("project");
                        String projectJson = (proj == null || proj == JSONObject.NULL) ? "" : String.valueOf(proj);
                        cb.onProjectUpdate(new JSONObject()
                                .put("project", projectJson)
                                .put("from", message.optString("from", "")));
                    } catch (JSONException ignored) {
                    }
                    break;
                case "project-update-rejected":
                    cb.onPermissionDenied(message);
                    break;
                case "pong":
                    lastPongTime = System.currentTimeMillis();
                    break;
                case "member-joined":
                    cb.onMemberJoined(message);
                    break;
                case "member-left":
                    cb.onMemberLeft(message);
                    break;
                case "collaboration-ended":
                    closed = true;
                    if (heartbeatThread != null) heartbeatThread.interrupt();
                    closeQuietly(socket);
                    cb.onEnded(message.optString("reason", ""));
                    break;
                default:
                    break;
            }
        }

        private void readerLoop() {
            byte[] tmp = new byte[8192];
            while (!closed) {
                try {
                    int n = in.read(tmp);
                    if (n < 0) {
                        onConnectionLost();
                        break;
                    }
                    acc.append(tmp, 0, n);
                    if (acc.size() > MAX_BUFFER_BYTES_CLIENT) {
                        onConnectionLost();
                        break;
                    }
                    Frame frame;
                    while ((frame = parseFrame(acc.data, acc.readPos, acc.size())) != null) {
                        acc.consume(frame.bytesRead);
                        handleFrame(frame);
                        if (closed) break;
                    }
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    onConnectionLost();
                    break;
                }
            }
        }

        private static final int MAX_BUFFER_BYTES_CLIENT = 64 * 1024 * 1024;

        private void heartbeatLoop() {
            while (!closed) {
                try {
                    Thread.sleep(PING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (closed) return;
                if (System.currentTimeMillis() - lastPongTime > PONG_TIMEOUT_MS) {
                    onConnectionLost();
                    return;
                }
                sendPing();
            }
        }

        private void onConnectionLost() {
            if (closed) return;
            closed = true;
            connected = false;
            if (heartbeatThread != null) heartbeatThread.interrupt();
            closeQuietly(socket);
            if (cb != null) cb.onEnded("connection-lost");
        }

        private void sendJson(JSONObject json) {
            try {
                synchronized (sendLock) {
                    if (closed || out == null) return;
                    sendFrameMasked(out, OPCODE_TEXT, json.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        }

        // ---- Join 公共 API ----

        public void sendChat(String text) {
            if (!connected || closed) return;
            try {
                sendJson(new JSONObject().put("type", "chat").put("text", text == null ? "" : text));
            } catch (JSONException ignored) {
            }
        }

        public void sendProjectUpdate(String projectJson) {
            if (!connected || closed) return;
            try {
                sendJson(new JSONObject().put("type", "project-update")
                        .put("project", projectJson == null ? "" : projectJson));
            } catch (JSONException ignored) {
            }
        }

        public void sendPing() {
            if (!connected || closed) return;
            try {
                sendJson(new JSONObject().put("type", "ping"));
            } catch (JSONException ignored) {
            }
        }

        public boolean isConnected() {
            return connected && !closed;
        }

        /** 主动离开：不发 connection-lost，不触发 onEnded。 */
        public void close() {
            closed = true;
            connected = false;
            if (heartbeatThread != null) heartbeatThread.interrupt();
            closeQuietly(socket);
        }
    }

    /* ============================================================
     * 局域网房间发现：/24 网段并发扫描 + HTTP GET /discover。
     * 对齐桌面版 src-main/windows/collaboration.js:48-144。
     * 阻塞调用，必须由调用方放到子线程。
     * ============================================================ */
    private static final java.util.regex.Pattern AUTH_HEX_UUID =
            java.util.regex.Pattern.compile("^[0-9a-fA-F-]{8,}$");

    /**
     * 扫描本机所有网卡的 /24 网段（含 127.0.0.1）在指定端口上的协作房间。
     * @return JSONArray of {id, ip, port, roomName, hostName, hostAvatar, onlineCount}
     */
    public static JSONArray discoverSessions(int port) {
        final JSONArray results = new JSONArray();
        final java.util.Set<String> seen = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add("127.0.0.1");
        java.util.List<String> localIPs = new java.util.ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                java.net.NetworkInterface nif = nifs.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        localIPs.add(ip);
                        candidates.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {}

        // 本机各网卡所在 /24 的全部地址
        for (String ip : localIPs) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                String prefix = parts[0] + "." + parts[1] + "." + parts[2];
                for (int i = 1; i < 255; i++) candidates.add(prefix + "." + i);
            }
        }

        final int probePort = port > 0 ? port : 8080;
        final java.util.List<String> list = new java.util.ArrayList<>(candidates);
        if (list.isEmpty()) return results;

        // 并发扫描，线程池上限 96，每项超时 1000ms
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(96, list.size()));
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(list.size());
        for (final String ip : list) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject info = probeDiscover(ip, probePort);
                        if (info != null && info.optBoolean("ok", false)) {
                            String key = info.optString("id", "") + "|" +
                                    info.optString("roomName", "") + "|" + info.optInt("port", probePort);
                            if (seen.add(key)) {
                                JSONObject found = new JSONObject();
                                found.put("id", info.optString("id", ""));
                                found.put("ip", ip);
                                found.put("port", info.optInt("port", probePort));
                                found.put("roomName", info.optString("roomName", ""));
                                found.put("hostName", info.optString("hostName", ""));
                                found.put("hostAvatar", info.optString("hostAvatar", ""));
                                found.put("onlineCount", info.optInt("onlineCount", 1));
                                results.put(found);
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            // 总超时兜底：网段较大时最多等 12 秒
            latch.await(12, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        return results;
    }

    /** 对单个 IP 发 GET /discover，1000ms 超时。失败返回 null。 */
    private static JSONObject probeDiscover(String ip, int port) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL("http://" + ip + ":" + port + "/discover");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 64 * 1024) break;
            }
            in.close();
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                return null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
