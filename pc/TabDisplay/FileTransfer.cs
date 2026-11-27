using System.Buffers.Binary;
using System.Text.Json.Nodes;

namespace TabDisplay;

/// <summary>
/// ファイルの受け渡し。
///  送る：[BEGIN {id, name, size}] → [DATA（id 4 バイト＋中身、256KB ずつ）]… → [END {id}]
///  受け取る：PC は「ダウンロード\TabDisplay」に保存する
/// </summary>
public static class FileTransfer
{
    public const int Chunk = 256 * 1024;

    public static string ReceiveDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "TabDisplay");

    /// <summary>ファイルを 1 つ送る（送る用のスレッドで呼ぶ）。send は 1 メッセージを送る関数</summary>
    public static void Send(string path, int id, Action<byte[]> send, Func<bool> cancelled)
    {
        var fi = new FileInfo(path);
        send(Protocol.Pack(Protocol.P_FILE_BEGIN, new JsonObject { ["id"] = id, ["name"] = fi.Name, ["size"] = fi.Length }));
        using var fs = fi.OpenRead();
        var buf = new byte[Chunk];
        int n;
        while ((n = fs.Read(buf, 0, buf.Length)) > 0)
        {
            if (cancelled()) return;
            var data = new byte[4 + n];
            BinaryPrimitives.WriteInt32BigEndian(data, id);
            Array.Copy(buf, 0, data, 4, n);
            send(Protocol.Pack(Protocol.P_FILE_DATA, data));
            Thread.Sleep(2); // 映像を先に通すため、少しずつ
        }
        send(Protocol.Pack(Protocol.P_FILE_END, new JsonObject { ["id"] = id }));
    }

    /// <summary>受け取り中のファイル</summary>
    public sealed class Incoming : IDisposable
    {
        public string Path { get; }
        private readonly FileStream _fs;

        public Incoming(string name)
        {
            Directory.CreateDirectory(ReceiveDir);
            var safe = string.Concat(name.Select(c => System.IO.Path.GetInvalidFileNameChars().Contains(c) ? '_' : c));
            if (safe.Length == 0) safe = "file";
            var p = System.IO.Path.Combine(ReceiveDir, safe);
            var stem = System.IO.Path.GetFileNameWithoutExtension(safe);
            var ext = System.IO.Path.GetExtension(safe);
            for (int i = 2; File.Exists(p); i++) p = System.IO.Path.Combine(ReceiveDir, $"{stem} ({i}){ext}");
            Path = p;
            _fs = new FileStream(p, FileMode.CreateNew);
        }

        public void Write(byte[] data, int offset) => _fs.Write(data, offset, data.Length - offset);

        public void Dispose() => _fs.Dispose();
    }
}
