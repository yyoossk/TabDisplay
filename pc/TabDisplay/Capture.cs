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
    bool SkipStatic = true,   // 省電力：変化がないときは絵を出さない
    int OffsetX = 0,          // 画面の一部だけ撮るとき（画面の左上からの位置）
    int OffsetY = 0,
    bool Region = false,
    bool TextQuality = false, // 文字をくっきり（動きは少しにじんでもよい）
    bool Hevc = false,        // H.265 で送る（相手が再生できるときだけ）
    double Contrast = 1.0);   // コントラスト（1.0 = そのまま。変えるときは CPU で処理する）

/// <summary>
/// ffmpeg で画面を撮って H.264 にする。
///  撮る: ddagrab（Desktop Duplication、グラボの上で撮る）
///  圧縮: NVIDIA → AMD → Intel → Windows 標準（Media Foundation）→ CPU（x264）の順に試す
///  受け取り: FLV 形式で標準出力に出させ、1 枚ずつ切り出す（切れ目がはっきりしていて、待ちが出ない）
/// </summary>
public sealed class Capture : IDisposable
{
    /// <summary>w, h, 方式（"h264" / "hevc"）, VPS（H.265 のみ）, SPS, PPS</summary>
    public event Action<int, int, string, byte[]?, byte[], byte[]>? Config;
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

    private record Enc(string Name, string Codec, bool GpuFrames, Func<CaptureSpec, string> Args, bool Hevc = false);

    // H.265：同じ画質で送る量が少ない（グラボが対応しているときだけ）
    private static readonly Enc[] HevcEncoders =
    {
        new("nvenc265", "hevc_nvenc", true, s => s.TextQuality
            ? $"-c:v hevc_nvenc -preset p4 -tune ll -zerolatency 1 -rc vbr -cq 21 {Rate(s, 1.5)} -bf 0 -g {Gop(s)} -forced-idr 1 -delay 0"
            : $"-c:v hevc_nvenc -preset p1 -tune ull -zerolatency 1 -rc cbr {Rate(s)} -bf 0 -g {Gop(s)} -forced-idr 1 -delay 0", true),
        new("amf265", "hevc_amf", true, s => s.TextQuality
            ? $"-c:v hevc_amf -usage lowlatency -quality quality -rc cqp -qp_i 22 -qp_p 24 -g {Gop(s)} -header_insertion_mode idr"
            : $"-c:v hevc_amf -usage ultralowlatency -quality speed -rc cbr {Rate(s)} -g {Gop(s)} -header_insertion_mode idr", true),
        new("qsv265", "hevc_qsv", false, s =>
            $"-c:v hevc_qsv -preset {(s.TextQuality ? "medium" : "veryfast")} -look_ahead 0 -async_depth 1 {Rate(s)} -bf 0 -g {Gop(s)}", true),
        new("mf265", "hevc_mf", false, s =>
            $"-c:v hevc_mf -hw_encoding 1 -scenario display_remoting -rate_control cbr -b:v {s.BitrateMbps}M -bf 0 -g {Gop(s)}", true),
    };

    // 動きをなめらか：遅れ最小・ビットレート一定
    // 文字をくっきり：画質を一定に保つ（変化の少ない画面で文字がにじまない）。上限は設定のビットレートの 1.5 倍
    private static readonly Enc[] Encoders =
    {
        new("nvenc", "h264_nvenc", true, s => s.TextQuality
            ? $"-c:v h264_nvenc -preset p4 -tune ll -zerolatency 1 -rc vbr -cq 19 {Rate(s, 1.5)} -bf 0 -g {Gop(s)} -forced-idr 1 -delay 0 -profile:v high"
            : $"-c:v h264_nvenc -preset p1 -tune ull -zerolatency 1 -rc cbr {Rate(s)} -bf 0 -g {Gop(s)} -forced-idr 1 -delay 0 -profile:v high"),
        new("amf", "h264_amf", true, s => s.TextQuality
            ? $"-c:v h264_amf -usage lowlatency -quality quality -rc qvbr -qvbr_quality_level 22 {Rate(s, 1.5)} -bf 0 -g {Gop(s)} -header_insertion_mode idr"
            : $"-c:v h264_amf -usage ultralowlatency -quality speed -rc cbr {Rate(s)} -bf 0 -g {Gop(s)} -header_insertion_mode idr"),
        new("qsv", "h264_qsv", false, s => s.TextQuality
            ? $"-c:v h264_qsv -preset medium -look_ahead 0 -async_depth 1 -global_quality 22 {Rate(s, 1.5)} -bf 0 -g {Gop(s)}"
            : $"-c:v h264_qsv -preset veryfast -look_ahead 0 -async_depth 1 {Rate(s)} -bf 0 -g {Gop(s)}"),
        new("mf", "h264_mf", false, s => s.TextQuality
            ? $"-c:v h264_mf -hw_encoding 1 -scenario display_remoting -rate_control quality -quality 80 -b:v {s.BitrateMbps}M -bf 0 -g {Gop(s)}"
            : $"-c:v h264_mf -hw_encoding 1 -scenario display_remoting -rate_control cbr -b:v {s.BitrateMbps}M -bf 0 -g {Gop(s)}"),
        new("x264", "libx264", false, s => s.TextQuality
            ? $"-c:v libx264 -preset veryfast -tune zerolatency -crf 20 {Rate(s, 1.5)} -bf 0 -g {Gop(s)} -profile:v high"
            : $"-c:v libx264 -preset ultrafast -tune zerolatency {Rate(s)} -bf 0 -g {Gop(s)} -profile:v high"),
    };

    // ビットレート：VBV（ため込み）は 4 枚分にして、遅れが出ないようにする
    private static string Rate(CaptureSpec s, double maxFactor = 1.0)
    {
        int max = (int)(s.BitrateMbps * 1000 * maxFactor);
        return $"-b:v {s.BitrateMbps}M -maxrate {max}k -bufsize {Math.Max(1000, max * 4 / Math.Max(1, s.Fps))}k";
    }

    // キーフレームは 2 秒ごと（届かなかった絵があっても 2 秒で直る）
    private static int Gop(CaptureSpec s) => s.Fps * 2;

    /// <summary>うまく動く圧縮方式を探して始める。だめなら false</summary>
    public bool Start()
    {
        // H.265 を使えるなら先に試し、だめなら H.264
        var order = new List<Enc>();
        if (Spec.Hevc) order.AddRange(HevcEncoders.OrderByDescending(e => e.Name == _settings.LastEncoderHevc));
        order.AddRange(Encoders.OrderByDescending(e => e.Name == _settings.LastEncoder));
        foreach (var enc in order)
        {
            if (_stopping) return false;
            if (TryStart(enc))
            {
                EncoderName = enc.Name;
                if (enc.Hevc && _settings.LastEncoderHevc != enc.Name)
                {
                    _settings.LastEncoderHevc = enc.Name;
                    _settings.Save();
                }
                else if (!enc.Hevc && _settings.LastEncoder != enc.Name)
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
        var scale = Spec.OutWidth != Spec.SourceWidth || Spec.OutHeight != Spec.SourceHeight
                    || Math.Abs(Spec.Contrast - 1.0) > 0.01;

        // dup_frames=0：画面が変わらないときは絵を出さない（送る量・タブレットの電池を節約）
        var filter = new StringBuilder($"ddagrab=output_idx={output}:framerate={Spec.Fps}:draw_mouse=1:dup_frames={(Spec.SkipStatic ? 0 : 1)}");
        // 画面の一部だけ撮る（ウィンドウ・選んだ範囲）
        if (Spec.Region)
            filter.Append($":offset_x={Spec.OffsetX}:offset_y={Spec.OffsetY}:video_size={Spec.SourceWidth}x{Spec.SourceHeight}");
        if (scale)
            filter.Append($",hwdownload,format=bgra,scale={Spec.OutWidth}:{Spec.OutHeight}:flags=fast_bilinear" +
                          (Math.Abs(Spec.Contrast - 1.0) > 0.01
                              ? $",eq=contrast={Spec.Contrast.ToString("0.00", System.Globalization.CultureInfo.InvariantCulture)}"
                              : "") +
                          ",format=nv12");
        else if (!enc.GpuFrames)
            filter.Append(enc.Name.StartsWith("qsv")
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
            _sps = _pps = _vps = null;
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

    private byte[]? _vps;
    private bool _hevc;

    private void OnVideoTag(byte[] d)
    {
        int payload;
        bool key;
        if ((d[0] & 0x80) != 0)
        {
            // 拡張 FLV（H.265）：[ExHeader|frameType|packetType][FourCC 4][...]
            int frameType = (d[0] >> 4) & 7;
            int packetType = d[0] & 0x0f;
            if (d.Length < 5 || d[1] != 'h' || d[2] != 'v' || d[3] != 'c' || d[4] != '1') return;
            _hevc = true;
            if (packetType == 0) { ParseHevcConfig(d, 5); return; }
            if (packetType == 1) payload = 8;       // CodedFrames（CTS 3 バイトのあと）
            else if (packetType == 3) payload = 5;  // CodedFramesX（CTS なし）
            else return;
            key = frameType == 1;
        }
        else
        {
            int frameType = d[0] >> 4;
            int codec = d[0] & 0x0f;
            if (codec != 7) return; // H.264（AVC）
            _hevc = false;
            if (d[1] == 0) { ParseAvcConfig(d, 5); return; }
            if (d[1] != 1) return;
            payload = 5;
            key = frameType == 1;
        }

        var ms = new MemoryStream(d.Length + 128);
        if (key && _sps != null && _pps != null)
        {
            if (_hevc && _vps != null) WriteNal(ms, _vps);
            WriteNal(ms, _sps);
            WriteNal(ms, _pps);
        }
        int i = payload;
        while (i + 4 <= d.Length)
        {
            int len = (d[i] << 24) | (d[i + 1] << 16) | (d[i + 2] << 8) | d[i + 3];
            i += 4;
            if (len <= 0 || i + len > d.Length) break;
            int t = _hevc ? (d[i] >> 1) & 0x3f : d[i] & 0x1f;
            bool isVps = _hevc && t == 32, isSps = _hevc ? t == 33 : t == 7, isPps = _hevc ? t == 34 : t == 8;
            bool isAud = _hevc ? t == 35 : t == 9;
            if (isVps || isSps || isPps)
            {
                // 設定が中に入っていたら覚える（エンコーダーによっては設定パケットを出さない）
                var nal = d.AsSpan(i, len).ToArray();
                if (isVps) _vps = nal; else if (isSps) _sps = nal; else _pps = nal;
                if (!_gotConfig.IsSet && _sps != null && _pps != null && (!_hevc || _vps != null)) EmitConfig();
                if (key) { i += len; continue; } // 先頭に入れたので二重にしない
            }
            if (isAud) { i += len; continue; }
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

    /// <summary>H.265 の設定（HEVCDecoderConfigurationRecord）から VPS・SPS・PPS を取り出す</summary>
    private void ParseHevcConfig(byte[] d, int o)
    {
        try
        {
            int n = d[o + 22];
            int p = o + 23;
            for (int a = 0; a < n; a++)
            {
                int type = d[p] & 0x3f;
                int count = (d[p + 1] << 8) | d[p + 2];
                p += 3;
                for (int k = 0; k < count; k++)
                {
                    int len = (d[p] << 8) | d[p + 1];
                    p += 2;
                    var nal = d.AsSpan(p, len).ToArray();
                    if (type == 32) _vps ??= nal;
                    else if (type == 33) _sps ??= nal;
                    else if (type == 34) _pps ??= nal;
                    p += len;
                }
            }
            if (_vps != null && _sps != null && _pps != null) EmitConfig();
        }
        catch (Exception e)
        {
            Log.Write("hvcC parse: " + e.Message);
        }
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
        Config?.Invoke(Spec.OutWidth, Spec.OutHeight, _hevc ? "hevc" : "h264", _hevc ? _vps : null, _sps!, _pps!);
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
