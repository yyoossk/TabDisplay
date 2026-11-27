using System.Buffers.Binary;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace TabDisplay;

/// <summary>
/// タブレットとのやりとりの形式（タブレット側の Protocol.kt と同じ）。
///   [種類 1 バイト][長さ 4 バイト（ビッグエンディアン）][中身]
/// 映像の中身: [PC の送信時刻 μs 8 バイト][フラグ 1 バイト][H.264（Annex-B）]
/// </summary>
public static class Protocol
{
    public const int Version = 1;

    // タブレット → PC
    public const byte T_HELLO = 1, T_INPUT = 2, T_SIZE = 3, T_PING = 4, T_IDR = 5, T_STATS = 6, T_BYE = 7, T_OPTS = 8, T_CLIP = 9,
        T_FILE_BEGIN = 30, T_FILE_DATA = 31, T_FILE_END = 32,
        T_CAM_CONFIG = 33, T_CAM_VIDEO = 34, T_MIC_CFG = 35, T_MIC = 36, T_APPS_REQ = 37, T_LAUNCH = 38, T_CRASH = 39, T_CHECK = 50;
    // PC → タブレット
    public const byte P_CONFIG = 10, P_VIDEO = 11, P_PONG = 12, P_STATUS = 13, P_ERROR = 14, P_AUDIO_CFG = 15, P_AUDIO = 16, P_AREA = 17, P_CLIP = 18, P_NOTIFY = 19,
        P_FILE_BEGIN = 40, P_FILE_DATA = 41, P_FILE_END = 42,
        P_CAM = 43, P_MIC = 44, P_APPS = 45, P_SLEEP = 46, P_CHECK = 51;

    public const byte F_KEY = 1, F_CONFIG = 2;
    public const int MaxLen = 1 * 1024 * 1024; // タブレットから来るのは小さい JSON だけ

    public static (byte type, byte[] data) Read(Stream s)
    {
        var head = new byte[5];
        s.ReadExactly(head);
        int len = BinaryPrimitives.ReadInt32BigEndian(head.AsSpan(1));
        if (len < 0 || len > MaxLen) throw new IOException("bad length " + len);
        var data = new byte[len];
        s.ReadExactly(data);
        return (head[0], data);
    }

    public static byte[] Pack(byte type, ReadOnlySpan<byte> data)
    {
        var buf = new byte[5 + data.Length];
        buf[0] = type;
        BinaryPrimitives.WriteInt32BigEndian(buf.AsSpan(1), data.Length);
        data.CopyTo(buf.AsSpan(5));
        return buf;
    }

    public static byte[] Pack(byte type, JsonObject j) => Pack(type, Encoding.UTF8.GetBytes(j.ToJsonString()));

    public static byte[] PackVideo(byte[] annexB, bool key, long pcTimeUs)
    {
        var buf = new byte[5 + 9 + annexB.Length];
        buf[0] = P_VIDEO;
        BinaryPrimitives.WriteInt32BigEndian(buf.AsSpan(1), 9 + annexB.Length);
        BinaryPrimitives.WriteInt64BigEndian(buf.AsSpan(5), pcTimeUs);
        buf[13] = key ? F_KEY : (byte)0;
        annexB.CopyTo(buf.AsSpan(14));
        return buf;
    }

    public static JsonObject Json(byte[] data)
    {
        try { return JsonNode.Parse(data) as JsonObject ?? new JsonObject(); }
        catch (JsonException) { return new JsonObject(); }
    }
}
