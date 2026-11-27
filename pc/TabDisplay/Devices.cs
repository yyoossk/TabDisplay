using System.Diagnostics;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace TabDisplay;

/// <summary>
/// タブレットのカメラを PC の Web カメラにする。
///  タブレット：カメラ → H.264 → PC
///  PC：ffmpeg で絵に戻して、仮想カメラ（Softcam、MIT）に渡す。ビデオ会議ソフトでは「DirectShow Softcam」を選ぶ
/// </summary>
public static class CameraBridge
{
    public const int Width = 1280, Height = 720;

    [DllImport("softcam.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr scCreateCamera(int width, int height, float framerate);
    [DllImport("softcam.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern void scDeleteCamera(IntPtr camera);
    [DllImport("softcam.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern void scSendFrame(IntPtr camera, byte[] imageBits);

    private static readonly object Lock = new();
    private static IntPtr _cam;
    private static Process? _ff;
    private static string? _owner;

    public static bool Available => File.Exists(Path.Combine(AppContext.BaseDirectory, "softcam.dll"));

    /// <summary>タブレットからカメラの設定（SPS・PPS）が来たとき</summary>
    public static void Start(string owner, byte[] sps, byte[] pps)
    {
        lock (Lock)
        {
            StopDecoder();
            _owner = owner;
            try
            {
                if (_cam == IntPtr.Zero) _cam = scCreateCamera(Width, Height, 0);
                if (_cam == IntPtr.Zero) { Log.Write("softcam: create failed (another camera instance?)"); return; }
            }
            catch (Exception e)
            {
                Log.Write("softcam: " + e.Message);
                return;
            }
            var ff = Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe");
            var psi = new ProcessStartInfo(ff,
                "-hide_banner -loglevel error -fflags nobuffer -flags low_delay -f h264 -i pipe:0 " +
                $"-vf scale={Width}:{Height}:force_original_aspect_ratio=decrease,pad={Width}:{Height}:(ow-iw)/2:(oh-ih)/2 " +
                "-f rawvideo -pix_fmt bgr24 pipe:1")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            var p = Process.Start(psi)!;
            _ff = p;
            new Thread(() => { try { string? l; while ((l = p.StandardError.ReadLine()) != null) Log.Write("[cam] " + l); } catch { } })
                { IsBackground = true }.Start();
            var cam = _cam;
            new Thread(() =>
            {
                var frame = new byte[Width * Height * 3];
                var s = p.StandardOutput.BaseStream;
                try
                {
                    while (true)
                    {
                        s.ReadExactly(frame);
                        scSendFrame(cam, frame);
                    }
                }
                catch { }
            }) { IsBackground = true, Name = "camera-out" }.Start();
            Feed(owner, Annex(sps));
            Feed(owner, Annex(pps));
        }
    }

    private static byte[] Annex(byte[] nal) => new byte[] { 0, 0, 0, 1 }.Concat(nal).ToArray();

    public static void Feed(string owner, byte[] annexB)
    {
        var p = _ff;
        if (p == null || owner != _owner) return;
        try
        {
            p.StandardInput.BaseStream.Write(annexB);
            p.StandardInput.BaseStream.Flush();
        }
        catch { }
    }

    private static void StopDecoder()
    {
        var p = _ff;
        _ff = null;
        if (p == null) return;
        try { p.Kill(); } catch { }
        try { p.Dispose(); } catch { }
    }

    public static void Stop(string owner)
    {
        lock (Lock)
        {
            if (owner != _owner) return;
            StopDecoder();
        }
    }

    /// <summary>ソフトを終えるとき：仮想カメラも消す</summary>
    public static void Shutdown()
    {
        lock (Lock)
        {
            StopDecoder();
            if (_cam != IntPtr.Zero) try { scDeleteCamera(_cam); } catch { }
            _cam = IntPtr.Zero;
        }
    }
}

/// <summary>
/// タブレットのマイクの音を、PC の選んだ出力先で鳴らす。
/// 出力先を「CABLE Input（VB-CABLE）」にすると、ビデオ会議ソフトでは「CABLE Output」をマイクとして選べる。
/// </summary>
public static class MicBridge
{
    private static readonly object Lock = new();
    private static WasapiOut? _out;
    private static BufferedWaveProvider? _buf;
    private static string? _owner;

    /// <summary>出力先の一覧（名前）</summary>
    public static List<string> OutputDevices()
    {
        try
        {
            using var en = new MMDeviceEnumerator();
            return en.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active).Select(d => d.FriendlyName).ToList();
        }
        catch { return new List<string>(); }
    }

    public static void Start(string owner, int rate, int channels, string deviceName)
    {
        lock (Lock)
        {
            StopInner();
            _owner = owner;
            try
            {
                using var en = new MMDeviceEnumerator();
                var devs = en.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active).ToList();
                var dev = devs.FirstOrDefault(d => d.FriendlyName == deviceName)
                          ?? devs.FirstOrDefault(d => d.FriendlyName.Contains("CABLE Input", StringComparison.OrdinalIgnoreCase))
                          ?? en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Communications);
                _buf = new BufferedWaveProvider(new WaveFormat(rate, 16, channels))
                {
                    DiscardOnBufferOverflow = true,
                    BufferDuration = TimeSpan.FromMilliseconds(300),
                };
                _out = new WasapiOut(dev, AudioClientShareMode.Shared, true, 30);
                _out.Init(_buf);
                _out.Play();
                Log.Write($"mic -> {dev.FriendlyName}");
            }
            catch (Exception e)
            {
                Log.Write("mic: " + e.Message);
                StopInner();
            }
        }
    }

    public static void Feed(string owner, byte[] pcm)
    {
        var b = _buf;
        if (b == null || owner != _owner) return;
        // 遅れがたまったら捨てて追いつく
        if (b.BufferedDuration > TimeSpan.FromMilliseconds(200)) b.ClearBuffer();
        b.AddSamples(pcm, 0, pcm.Length);
    }

    private static void StopInner()
    {
        try { _out?.Stop(); } catch { }
        try { _out?.Dispose(); } catch { }
        _out = null;
        _buf = null;
    }

    public static void Stop(string owner)
    {
        lock (Lock)
        {
            if (owner == _owner) StopInner();
        }
    }
}

/// <summary>タブレットの画面に並べる PC のソフト（ランチャー）</summary>
public static class Launcher
{
    /// <summary>並べるソフト。設定が空なら、タスクバーにピン留めしたソフト</summary>
    public static List<string> Items(AppSettings s)
    {
        if (s.LauncherApps.Count > 0) return s.LauncherApps.Where(l => l.Trim().Length > 0).ToList();
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "Microsoft", "Internet Explorer", "Quick Launch", "User Pinned", "TaskBar");
        try { return Directory.Exists(dir) ? Directory.GetFiles(dir, "*.lnk").OrderBy(f => f).ToList() : new(); }
        catch { return new(); }
    }

    /// <summary>（名前, アイコンの PNG）</summary>
    public static (string name, byte[]? icon) Describe(string command)
    {
        var (exe, _) = Win.SplitCommand(command);
        var name = Path.GetFileNameWithoutExtension(exe);
        byte[]? png = null;
        try
        {
            using var ico = Icon.ExtractAssociatedIcon(exe);
            if (ico != null)
            {
                using var bmp = new Bitmap(ico.ToBitmap(), 64, 64);
                using var ms = new MemoryStream();
                bmp.Save(ms, System.Drawing.Imaging.ImageFormat.Png);
                png = ms.ToArray();
            }
        }
        catch { }
        return (name, png);
    }
}

/// <summary>Windows のタッチとして入れる（2 本指の拡大・スワイプなどがそのまま効く）</summary>
internal static class TouchInput
{
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool InitializeTouchInjection(uint maxCount, uint dwMode);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool InjectTouchInput(uint count, [In] Native.POINTER_TOUCH_INFO[] contacts);

    private const int PT_TOUCH = 2;
    private const uint TOUCH_MASK_CONTACTAREA = 0x1, TOUCH_MASK_ORIENTATION = 0x2, TOUCH_MASK_PRESSURE = 0x4;
    private static bool _init;
    private static readonly Dictionary<uint, (int x, int y)> Down = new();
    private static System.Threading.Timer? _keep;

    /// <summary>pts：[{id, x, y, s:"d"|"m"|"u"}]（x, y は画面の中の割合）</summary>
    public static void Handle(DisplayInfo t, System.Text.Json.Nodes.JsonArray pts)
    {
        lock (Down)
        {
            if (!_init)
            {
                _init = InitializeTouchInjection(10, 1);
                if (!_init) { Log.Write("touch injection not available: " + Marshal.GetLastWin32Error()); return; }
                // 動かさずに押したままでも、Windows が「離した」とみなさないよう、少しずつ送り直す
                _keep = new System.Threading.Timer(_ => Keep(), null, 100, 100);
            }
            var list = new List<Native.POINTER_TOUCH_INFO>();
            foreach (var n in pts)
            {
                if (n == null) continue;
                uint id = (uint)((int?)n["id"] ?? 0);
                var (px, py) = InputInjector.ToPixel(t, (double?)n["x"] ?? 0, (double?)n["y"] ?? 0);
                var s = (string?)n["s"] ?? "m";
                uint flags = s switch
                {
                    "d" => Native.POINTER_FLAG_DOWN | Native.POINTER_FLAG_INRANGE | Native.POINTER_FLAG_INCONTACT,
                    "u" => Native.POINTER_FLAG_UP,
                    _ => Native.POINTER_FLAG_UPDATE | Native.POINTER_FLAG_INRANGE | Native.POINTER_FLAG_INCONTACT,
                };
                if (s == "u") Down.Remove(id); else Down[id] = (px, py);
                list.Add(Contact(id, px, py, flags));
            }
            if (list.Count > 0 && !InjectTouchInput((uint)list.Count, list.ToArray()))
                Log.Write("InjectTouchInput: " + Marshal.GetLastWin32Error());
        }
    }

    private static void Keep()
    {
        lock (Down)
        {
            if (Down.Count == 0) return;
            var list = Down.Select(kv => Contact(kv.Key, kv.Value.x, kv.Value.y,
                Native.POINTER_FLAG_UPDATE | Native.POINTER_FLAG_INRANGE | Native.POINTER_FLAG_INCONTACT)).ToArray();
            InjectTouchInput((uint)list.Length, list);
        }
    }

    private static Native.POINTER_TOUCH_INFO Contact(uint id, int x, int y, uint flags) => new()
    {
        pointerInfo = new Native.POINTER_INFO
        {
            pointerType = PT_TOUCH,
            pointerId = id,
            pointerFlags = flags,
            ptPixelLocation = new Native.POINT { x = x, y = y },
        },
        touchMask = TOUCH_MASK_CONTACTAREA | TOUCH_MASK_ORIENTATION | TOUCH_MASK_PRESSURE,
        rcContact = new Native.RECT { left = x - 2, top = y - 2, right = x + 2, bottom = y + 2 },
        orientation = 90,
        pressure = 32000,
    };
}

/// <summary>PC が使われていない時間（キーボード・マウス）</summary>
internal static class Idle
{
    [StructLayout(LayoutKind.Sequential)]
    private struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
    [DllImport("user32.dll")] private static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

    public static TimeSpan Time
    {
        get
        {
            var i = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
            if (!GetLastInputInfo(ref i)) return TimeSpan.Zero;
            return TimeSpan.FromMilliseconds(unchecked((uint)Environment.TickCount - i.dwTime));
        }
    }
}
