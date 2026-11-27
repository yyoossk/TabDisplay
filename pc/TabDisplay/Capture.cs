using System.Diagnostics;
using System.Text;

namespace TabDisplay;

public static class Clock
{
    private static readonly double UsPerTick = 1_000_000.0 / Stopwatch.Frequency;
    public static long NowUs() => (long)(Stopwatch.GetTimestamp() * UsPerTick);
}

/// <summary>何をどう撮るか</summary>
public record CaptureSpec(
    string DeviceName,     // \\.\DISPLAY3 など
    int SourceWidth,       // 撮る画面の大きさ
    int SourceHeight,
    int OutWidth,          // 送る大きさ（違えば縮める）
    int OutHeight,
    int Fps,
    int BitrateMbps,
    bool SkipStatic = true);  // 省電力：変化がないときは絵を出さない

/// <summary>
/// ffmpeg で画面を撮って H.264 にする。
///  撮る: ddagrab（Desktop Duplication、グラボの上で撮る）
///  圧縮: NVIDIA → AMD → Intel → Windows 標準（Media Foundation）→ CPU（x264）の順に試す
///  受け取り: FLV 形式で標準出力に出させ、1 枚ずつ切り出す（切れ目がはっきりしていて、待ちが出ない）
/// </summary>
public sealed class Capture : IDisposable
{
    public event Action<int, int, byte[], byte[]>? Config;          // w, h, sps, pps
    public event Action<byte[], bool, long>? Frame;                  // Annex-B, キーフレームか, PC 時刻(μs)
    public event Action<string>? Failed;

    public CaptureSpec Spec { get; }
    public string? EncoderName { get; private set; }

    private Process? _proc;
    private Thread? _reader;
    private volatile bool _stopping;
    private readonly AppSettings _settings;
    private byte[]? _sps, _pps;
    private long _lastRestart;
    private readonly ManualResetEventSlim _gotConfig = new(false);
    private readonly object _lock = new();

    private static string FfmpegPath => Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe");

    public Capture(CaptureSpec spec, AppSettings settings)
    {
        Spec = spec;
        _settings = settings;
    }

    private record Enc(string Name, string Codec, bool GpuFrames, Func<CaptureSpec, string> Args);

    private static readonly Enc[] Encoders =
    {
        new("nvenc", "h264_nvenc", true, s =>
            $"-c:v h264_nvenc -preset p1 -tune ull -zerolatency 1 -rc cbr {Rate(s)} -bf 0 -g {Gop(s)} -forced-idr 1 -delay 0 -profile:v high"),
        new("amf", "h264_amf", true, s =>
            $"-c:v h264_amf -usage ultralowlatency -quality speed -rc cbr {Rate(s)} -bf 0 -g {Gop(s)} -header_insertion_mode idr"),
        new("qsv", "h264_qsv", false, s =>
            $"-c:v h264_qsv -preset veryfast -look_ahead 0 -async_depth 1 {Rate(s)} -bf 0 -g {Gop(s)}"),
        new("mf", "h264_mf", false, s =>
            $"-c:v h264_mf -hw_encoding 1 -scenario display_remoting -rate_control cbr -b:v {s.BitrateMbps}M -bf 0 -g {Gop(s)}"),
        new("x264", "libx264", false, s =>
            $"-c:v libx264 -preset ultrafast -tune zerolatency {Rate(s)} -bf 0 -g {Gop(s)} -profile:v high"),
    };

    // ビットレート：VBV（ため込み）は 4 枚分にして、遅れが出ないようにする
    private static string Rate(CaptureSpec s) =>
        $"-b:v {s.BitrateMbps}M -maxrate {s.BitrateMbps}M -bufsize {Math.Max(1, s.BitrateMbps * 1000 * 4 / Math.Max(1, s.Fps))}k";

    // キーフレームは 2 秒ごと（届かなかった絵があっても 2 秒で直る）
    private static int Gop(CaptureSpec s) => s.Fps * 2;

    /// <summary>うまく動く圧縮方式を探して始める。だめなら false</summary>
    public bool Start()
    {
        var order = Encoders.OrderByDescending(e => e.Name == _settings.LastEncoder).ToList();
        foreach (var enc in order)
        {
            if (_stopping) return false;
            if (TryStart(enc))
            {
                EncoderName = enc.Name;
                if (_settings.LastEncoder != enc.Name)
                {
                    _settings.LastEncoder = enc.Name;
                    _settings.Save();
                }
                Log.Write($"capture started with {enc.Name}");
                return true;
            }
        }
        Failed?.Invoke("no encoder");
        return false;
    }

    private Enc? _current;

    private bool TryStart(Enc enc)
    {
        var dx = Native.FindDxgiOutput(Spec.DeviceName);
        if (dx == null)
        {
            Log.Write("DXGI output not found for " + Spec.DeviceName);
            return false;
        }
        var (adapter, output) = dx.Value;
        var scale = Spec.OutWidth != Spec.SourceWidth || Spec.OutHeight != Spec.SourceHeight;

        // dup_frames=0：画面が変わらないときは絵を出さない（送る量・タブレットの電池を節約）
        var filter = new StringBuilder($"ddagrab=output_idx={output}:framerate={Spec.Fps}:draw_mouse=1:dup_frames={(Spec.SkipStatic ? 0 : 1)}");
        if (scale)
            filter.Append($",hwdownload,format=bgra,scale={Spec.OutWidth}:{Spec.OutHeight}:flags=fast_bilinear,format=nv12");
        else if (!enc.GpuFrames)
            filter.Append(enc.Name == "qsv"
                ? ",hwmap=derive_device=qsv,format=qsv"
                : ",hwdownload,format=bgra,format=nv12");

        var args = $"-hide_banner -loglevel warning -nostdin " +
                   $"-init_hw_device d3d11va=dx:{adapter} -filter_hw_device dx " +
                   $"-filter_complex \"{filter}\" " +
                   enc.Args(Spec) +
                   " -an -fflags +flush_packets -flush_packets 1 -f flv -flvflags no_duration_filesize pipe:1";

        lock (_lock)
        {
            KillProcess();
            _gotConfig.Reset();
            _sps = _pps = null;
            try
            {
                var psi = new ProcessStartInfo(FfmpegPath, args)
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                };
                Log.Write("ffmpeg " + args);
                var p = Process.Start(psi)!;
                try { p.PriorityClass = ProcessPriorityClass.High; } catch { }
                _proc = p;
                _current = enc;
                var stderr = p.StandardError;
                new Thread(() =>
                {
                    try
                    {
                        string? line;
                        while ((line = stderr.ReadLine()) != null) Log.Write("[ffmpeg] " + line);
                    }
                    catch { }
                }) { IsBackground = true, Name = "ffmpeg-err" }.Start();
                var stdout = p.StandardOutput.BaseStream;
                _reader = new Thread(() => ReadLoop(p, stdout)) { IsBackground = true, Name = "ffmpeg-out", Priority = ThreadPriority.Highest };
                _reader.Start();
            }
            catch (Exception e)
            {
                Log.Write("ffmpeg start: " + e.Message);
                return false;
            }
        }

        // 5 秒以内に最初の絵（SPS/PPS）が出なければ、この方式はだめ
        if (_gotConfig.Wait(5000)) return true;
        Log.Write($"encoder {enc.Name} gave no output");
        lock (_lock) KillProcess();
        return false;
    }

    /// <summary>タブレット側でデコードし直すときなど、キーフレームをすぐ出させる（ffmpeg を起動し直す）</summary>
    public void RequestKeyFrame()
    {
        var now = Environment.TickCount64;
        if (now - _lastRestart < 2000 || _current == null || _stopping) return;
        _lastRestart = now;
        var enc = _current;
        new Thread(() =>
        {
            if (!TryStart(enc) && !_stopping) Start();
        }) { IsBackground = true }.Start();
    }

    // ---------- FLV の読み取り ----------

    private void ReadLoop(Process p, Stream s)
    {
        try
        {
            var head = new byte[9];
            s.ReadExactly(head);
            if (head[0] != 'F' || head[1] != 'L' || head[2] != 'V') throw new IOException("not flv");
            var skip = ((head[5] << 24) | (head[6] << 16) | (head[7] << 8) | head[8]) - 9;
            if (skip > 0) s.ReadExactly(new byte[skip]);

            var th = new byte[15]; // 直前タグの長さ 4 + タグの頭 11
            while (!_stopping)
            {
                s.ReadExactly(th);
                int type = th[4] & 0x1f;
                int size = (th[5] << 16) | (th[6] << 8) | th[7];
                var data = new byte[size];
                s.ReadExactly(data);
                if (type == 9 && size > 5) OnVideoTag(data);
            }
        }
        catch (Exception e)
        {
            if (!_stopping && ReferenceEquals(p, _proc))
            {
                Log.Write("ffmpeg output ended: " + e.Message);
                Failed?.Invoke("ffmpeg ended");
            }
        }
    }

    private void OnVideoTag(byte[] d)
    {
        int frameType = d[0] >> 4;
        int codec = d[0] & 0x0f;
        if ((d[0] & 0x80) != 0 || codec != 7) return; // H.264（AVC）以外は扱わない
        int packetType = d[1];
        if (packetType == 0)
        {
            ParseAvcConfig(d, 5);
            return;
        }
        if (packetType != 1) return;

        bool key = frameType == 1;
        var ms = new MemoryStream(d.Length + 64);
        if (key && _sps != null && _pps != null)
        {
            WriteNal(ms, _sps);
            WriteNal(ms, _pps);
        }
        int i = 5;
        while (i + 4 <= d.Length)
        {
            int len = (d[i] << 24) | (d[i + 1] << 16) | (d[i + 2] << 8) | d[i + 3];
            i += 4;
            if (len <= 0 || i + len > d.Length) break;
            int nalType = d[i] & 0x1f;
            if (nalType == 7 || nalType == 8)
            {
                // SPS/PPS が中に入っていたら覚える（エンコーダーによっては設定パケットを出さない）
                var nal = d.AsSpan(i, len).ToArray();
                if (nalType == 7) _sps = nal; else _pps = nal;
                if (!_gotConfig.IsSet && _sps != null && _pps != null) EmitConfig();
                if (key) { i += len; continue; } // 先頭に入れたので二重にしない
            }
            if (nalType == 9) { i += len; continue; } // AUD はいらない
            ms.Write(StartCode);
            ms.Write(d, i, len);
            i += len;
        }
        if (!_gotConfig.IsSet) return;
        if (ms.Length > 0) Frame?.Invoke(ms.ToArray(), key, Clock.NowUs());
    }

    private static readonly byte[] StartCode = { 0, 0, 0, 1 };

    private static void WriteNal(MemoryStream ms, byte[] nal)
    {
        ms.Write(StartCode);
        ms.Write(nal);
    }

    private void ParseAvcConfig(byte[] d, int o)
    {
        try
        {
            int numSps = d[o + 5] & 0x1f;
            int p = o + 6;
            byte[]? sps = null, pps = null;
            for (int k = 0; k < numSps; k++)
            {
                int len = (d[p] << 8) | d[p + 1];
                p += 2;
                sps ??= d.AsSpan(p, len).ToArray();
                p += len;
            }
            int numPps = d[p++];
            for (int k = 0; k < numPps; k++)
            {
                int len = (d[p] << 8) | d[p + 1];
                p += 2;
                pps ??= d.AsSpan(p, len).ToArray();
                p += len;
            }
            if (sps != null && pps != null)
            {
                _sps = sps;
                _pps = pps;
                EmitConfig();
            }
        }
        catch (Exception e)
        {
            Log.Write("avcC parse: " + e.Message);
        }
    }

    private void EmitConfig()
    {
        Config?.Invoke(Spec.OutWidth, Spec.OutHeight, _sps!, _pps!);
        _gotConfig.Set();
    }

    // ---------- 終わり ----------

    private void KillProcess()
    {
        var p = _proc;
        _proc = null;
        if (p == null) return;
        try { if (!p.HasExited) p.Kill(true); } catch { }
        try { p.Dispose(); } catch { }
    }

    public void Dispose()
    {
        _stopping = true;
        lock (_lock) KillProcess();
    }
}
