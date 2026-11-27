using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;
using System.Text.Json.Nodes;

namespace TabDisplay;

/// <summary>
/// タブレットからの接続を待つ（Wi-Fi も USB も同じ口。USB は adb reverse で 127.0.0.1 から来る）。
/// タブレットは同時に 4 台まで。画面の並べ替え（仮想ディスプレイの枚数・解像度）は 1 本の作業スレッドで順番に行う。
/// </summary>
public sealed class Server : IDisposable
{
    public const int MaxTablets = 4;

    private readonly AppSettings _settings;
    private TcpListener? _listener;
    private readonly List<Session> _sessions = new();
    private readonly object _lock = new();
    private Beacon? _beacon;

    // 画面の並べ替えの依頼
    private readonly AutoResetEvent _layoutEvent = new(false);
    private volatile bool _fullLayout;
    private readonly ConcurrentQueue<Session> _partial = new();
    private ShowMode _lastMode;
    private volatile bool _disposed;

    public event Action<string>? Status;
    public event Action<string>? Stats;
    public event Action<bool>? ConnectedChanged;

    public bool Connected { get { lock (_lock) return _sessions.Count > 0; } }
    internal AppSettings Settings => _settings;

    public Server(AppSettings settings) => _settings = settings;

    public bool Start()
    {
        try
        {
            _listener = new TcpListener(IPAddress.Any, _settings.Port);
            _listener.Start();
            new Thread(AcceptLoop) { IsBackground = true, Name = "accept" }.Start();
            new Thread(LayoutLoop) { IsBackground = true, Name = "layout" }.Start();
            new Thread(StatsLoop) { IsBackground = true, Name = "stats" }.Start();
            _beacon = new Beacon(_settings);
            return true;
        }
        catch (Exception e)
        {
            Log.Write("listen: " + e.Message);
            return false;
        }
    }

    private void AcceptLoop()
    {
        while (_listener != null)
        {
            TcpClient c;
            try { c = _listener.AcceptTcpClient(); }
            catch { break; }
            new Thread(() => Handle(c)) { IsBackground = true, Name = "session" }.Start();
        }
    }

    private void Handle(TcpClient c)
    {
        Session? s = null;
        try
        {
            c.NoDelay = true;
            c.SendBufferSize = 1024 * 1024;
            c.ReceiveTimeout = 5000;
            // 通信は全部暗号化する（TLS）。タブレットは証明書の指紋で本物の PC か確かめる
            var ssl = new SslStream(c.GetStream(), false);
            ssl.AuthenticateAsServer(CertStore.Cert, false, SslProtocols.Tls12 | SslProtocols.Tls13, false);
            Stream stream = ssl;
            var (type, data) = Protocol.Read(stream);
            if (type != Protocol.T_HELLO) return;
            var hello = Protocol.Json(data);
            if ((string?)hello["token"] != _settings.Token)
            {
                Log.Write("token mismatch from " + c.Client.RemoteEndPoint);
                stream.Write(Protocol.Pack(Protocol.P_ERROR, new JsonObject { ["e"] = "token" }));
                return;
            }
            c.ReceiveTimeout = 0;

            s = new Session(this, c, stream, hello);
            List<Session> replaced;
            lock (_lock)
            {
                // 同じタブレットのつなぎ直しなら古いほうを閉じる。多すぎたらいちばん古いものを閉じる
                replaced = _sessions.Where(x => x.Id == s.Id).ToList();
                foreach (var r in replaced) _sessions.Remove(r);
                if (_sessions.Count >= MaxTablets)
                {
                    replaced.Add(_sessions[0]);
                    _sessions.RemoveAt(0);
                }
                _sessions.Add(s);
            }
            foreach (var r in replaced) r.Close();
            ConnectedChanged?.Invoke(true);
            RequestLayout(null);
            s.Run();
        }
        catch (Exception e)
        {
            Log.Write("session: " + e.Message);
        }
        finally
        {
            try { c.Close(); } catch { }
            if (s != null)
            {
                s.Close();
                bool removed;
                int left;
                lock (_lock)
                {
                    removed = _sessions.Remove(s);
                    left = _sessions.Count;
                }
                s.StopCapture();
                s.StopAudio();
                if (removed)
                {
                    RequestLayout(null);
                    if (left == 0)
                    {
                        ConnectedChanged?.Invoke(false);
                        RaiseStatus("st_disconnected");
                    }
                }
            }
        }
    }

    /// <summary>表示のしかた・回転などの設定を変えたとき</summary>
    public void ApplySettings()
    {
        foreach (var s in Snapshot()) s.UpdateAudio();
        RequestLayout(null);
    }

    public void Disconnect()
    {
        foreach (var s in Snapshot()) s.Close();
    }

    private List<Session> Snapshot()
    {
        lock (_lock) return _sessions.ToList();
    }

    // ---------- 画面の並べ替え ----------

    /// <summary>only = そのタブレットだけ（回したとき・画質を変えたとき）。null = 全部</summary>
    internal void RequestLayout(Session? only)
    {
        if (only == null) _fullLayout = true;
        else _partial.Enqueue(only);
        _layoutEvent.Set();
    }

    private void LayoutLoop()
    {
        while (!_disposed)
        {
            _layoutEvent.WaitOne();
            if (_disposed) break;
            try
            {
                if (_fullLayout)
                {
                    _fullLayout = false;
                    while (_partial.TryDequeue(out _)) { }
                    LayoutAll();
                }
                else
                {
                    while (_partial.TryDequeue(out var s)) LayoutOne(s);
                }
            }
            catch (Exception e)
            {
                Log.Write("layout: " + e);
            }
        }
    }

    private void LayoutAll()
    {
        var list = Snapshot().Where(s => !s.Closed).ToList();
        foreach (var s in list) s.StopCapture();
        var mode = _settings.Mode;

        if (_lastMode == ShowMode.TabletOnly && (mode != ShowMode.TabletOnly || list.Count == 0))
            Displays.RestoreIfNeeded();
        _lastMode = mode;

        if (list.Count == 0)
        {
            VirtualDisplay.Disable();
            return;
        }

        switch (mode)
        {
            case ShowMode.PcOnly:
                VirtualDisplay.Disable();
                foreach (var s in list)
                {
                    s.SetOff("pc_only");
                    RaiseStatus("st_pc_only", s.TabletName);
                }
                return;

            case ShowMode.Duplicate:
            {
                VirtualDisplay.Disable();
                var p = Displays.Primary();
                if (p == null) return;
                foreach (var s in list)
                {
                    var (w, h) = FitInto(p.Width, p.Height, s.TabW, s.TabH);
                    s.StartCapture(p, w, h);
                }
                return;
            }

            default: // 広げる・タブレットのみ
            {
                if (!VirtualDisplay.IsInstalled)
                {
                    foreach (var s in list) s.SetOff("no_driver");
                    RaiseStatus("st_no_driver");
                    return;
                }
                RaiseStatus("st_preparing");
                var sizes = list.Select(s => s.WantedSize()).ToList();
                var displays = VirtualDisplay.EnableMany(sizes, _settings.Fps);
                if (displays == null)
                {
                    foreach (var s in list) s.SetOff("no_driver");
                    RaiseStatus("st_vdd_failed");
                    return;
                }
                var names = displays.Select(d => d.DeviceName).ToList();
                if (mode == ShowMode.TabletOnly)
                {
                    Displays.MakeOnlyDisplay(names);
                    Thread.Sleep(500);
                }
                else
                {
                    Displays.PlaceVirtuals(names, _settings.Side);
                }
                for (int i = 0; i < list.Count; i++)
                {
                    if (_settings.AutoDpi) DpiScale.Set(names[i], DpiScale.PercentForTablet(list[i].Dpi));
                    list[i].VirtualName = names[i];
                }
                displays = VirtualDisplay.Refetch(names) ?? displays;
                for (int i = 0; i < list.Count; i++)
                    list[i].StartCapture(displays[i], Even(displays[i].Width), Even(displays[i].Height));
                return;
            }
        }
    }

    /// <summary>1 台だけ直す（回したとき・画質の自動調整）。ほかのタブレットは止めない</summary>
    private void LayoutOne(Session s)
    {
        if (s.Closed) return;
        switch (_settings.Mode)
        {
            case ShowMode.Duplicate:
            {
                var p = Displays.Primary();
                if (p == null) return;
                var (w, h) = FitInto(p.Width, p.Height, s.TabW, s.TabH);
                s.StartCapture(p, w, h); // 新しいほうが動き出してから古いほうを止める
                return;
            }
            case ShowMode.Extend:
            case ShowMode.TabletOnly:
            {
                var name = s.VirtualName;
                if (name == null) { RequestLayout(null); return; }
                var (w, h) = s.WantedSize();
                // 解像度は縦横とも最初から用意してあるので、切り替えるだけ（ドライバーの再起動なし）
                Displays.SetMode(name, w, h, _settings.Fps);
                if (_settings.Mode == ShowMode.Extend)
                    Displays.PlaceVirtuals(Displays.FindVirtuals().Select(x => x.DeviceName).ToList(), _settings.Side);
                var d = VirtualDisplay.Refetch(new[] { name })?.FirstOrDefault();
                if (d == null) { RequestLayout(null); return; }
                s.StartCapture(d, Even(d.Width), Even(d.Height));
                return;
            }
        }
    }

    internal static int Even(int v) => Math.Max(2, v & ~1);

    /// <summary>(sw, sh) を (bw, bh) の箱（向きは画面に合わせる）に入るように縮める</summary>
    private static (int, int) FitInto(int sw, int sh, int bw, int bh)
    {
        if ((sw >= sh) != (bw >= bh)) (bw, bh) = (bh, bw);
        double s = Math.Min(1.0, Math.Min((double)bw / sw, (double)bh / sh));
        return (Even((int)(sw * s)), Even((int)(sh * s)));
    }

    // ---------- 表示 ----------

    private void StatsLoop()
    {
        while (!_disposed)
        {
            Thread.Sleep(1000);
            var list = Snapshot();
            var sb = new StringBuilder();
            foreach (var s in list)
            {
                s.AdaptTick();
                if (s.Fps == 0 && s.Lat == 0) continue;
                if (sb.Length > 0) sb.AppendLine();
                sb.Append(s.TabletName).Append(": ")
                  .Append(string.Format(L.T("stats_fmt"), s.Fps, s.Lat, s.Rtt))
                  .Append($" / {s.CurrentMbps} Mbps");
            }
            Stats?.Invoke(sb.ToString());
        }
    }

    internal void RaiseStatus(string key, params object[] args) =>
        Status?.Invoke(args.Length == 0 ? L.T(key) : string.Format(L.T(key), args));

    public void Dispose()
    {
        _disposed = true;
        _layoutEvent.Set();
        _beacon?.Dispose();
        try { _listener?.Stop(); } catch { }
        _listener = null;
        foreach (var s in Snapshot())
        {
            s.Close();
            s.StopCapture();
            s.StopAudio();
        }
    }
}

/// <summary>つながっているタブレット 1 台分</summary>
internal sealed class Session
{
    private readonly Server _server;
    private readonly AppSettings _settings;
    private readonly TcpClient _client;
    private readonly Stream _stream;
    private readonly object _writeLock = new();
    private readonly object _capLock = new();
    private volatile bool _closed;

    public string Id { get; }
    public string TabletName { get; }
    public int TabW { get; private set; }
    public int TabH { get; private set; }
    public int Dpi { get; }
    /// <summary>このタブレット用の仮想ディスプレイの名前（広げる・タブレットのみのとき）</summary>
    public volatile string? VirtualName;
    public bool Closed => _closed;

    // 映像
    private volatile Capture? _capture;
    private volatile DisplayInfo? _target;
    private readonly BlockingCollection<byte[]> _queue = new(new ConcurrentQueue<byte[]>());
    private volatile bool _dropUntilKey;

    // 統計と画質の自動調整
    public int Fps, Lat, Rtt;
    public int CurrentMbps;
    private int _drops;
    private long _lastRateChange;
    private long _lastTrouble;

    // 音
    private Action<byte[]>? _audioSink;

    public Session(Server server, TcpClient client, Stream stream, JsonObject hello)
    {
        _server = server;
        _settings = server.Settings;
        _client = client;
        _stream = stream;
        TabletName = (string?)hello["name"] ?? "Tablet";
        Id = (string?)hello["id"] ?? Guid.NewGuid().ToString();
        TabW = Server.Even((int?)hello["w"] ?? 1920);
        TabH = Server.Even((int?)hello["h"] ?? 1080);
        Dpi = (int?)hello["dpi"] ?? 160;
        CurrentMbps = _settings.BitrateMbps;
        _lastRateChange = Environment.TickCount64;
        Log.Write($"tablet {TabletName} {TabW}x{TabH} dpi {Dpi} from {client.Client.RemoteEndPoint}");
    }

    /// <summary>仮想ディスプレイにしてほしい大きさ（回転に合わせないときは横長のまま）</summary>
    public (int w, int h) WantedSize()
    {
        int w = TabW, h = TabH;
        if (!_settings.FollowRotation && h > w) (w, h) = (h, w);
        return (w, h);
    }

    public void Run()
    {
        _server.RaiseStatus("st_connected", TabletName);
        new Thread(SendLoop) { IsBackground = true, Name = "send", Priority = ThreadPriority.AboveNormal }.Start();
        UpdateAudio();

        while (!_closed)
        {
            var (type, data) = Protocol.Read(_stream);
            switch (type)
            {
                case Protocol.T_INPUT:
                    InputInjector.Handle(_target, Protocol.Json(data));
                    break;
                case Protocol.T_SIZE:
                {
                    var j = Protocol.Json(data);
                    var w = Server.Even((int?)j["w"] ?? TabW);
                    var h = Server.Even((int?)j["h"] ?? TabH);
                    if (w != TabW || h != TabH)
                    {
                        TabW = w;
                        TabH = h;
                        Log.Write($"{TabletName} rotated: {w}x{h}");
                        // 回転に合わせない設定なら、PC 側はそのまま（タブレット側で縦横比を保って表示する）
                        if (_settings.FollowRotation || _settings.Mode == ShowMode.Duplicate)
                            _server.RequestLayout(this);
                    }
                    break;
                }
                case Protocol.T_PING:
                {
                    var j = Protocol.Json(data);
                    Send(Protocol.Pack(Protocol.P_PONG, new JsonObject
                    {
                        ["tab"] = (long?)j["tab"] ?? 0,
                        ["pc"] = Clock.NowUs(),
                    }));
                    break;
                }
                case Protocol.T_IDR:
                    _capture?.RequestKeyFrame();
                    break;
                case Protocol.T_STATS:
                {
                    var j = Protocol.Json(data);
                    Fps = (int?)j["fps"] ?? 0;
                    Lat = (int?)j["lat"] ?? 0;
                    Rtt = (int?)j["rtt"] ?? 0;
                    break;
                }
                case Protocol.T_BYE:
                    return;
            }
        }
    }

    // ---------- 映像 ----------

    public void SetOff(string status)
    {
        StopCapture();
        _target = null;
        SendStatus(status);
    }

    /// <summary>
    /// 撮り始める。前のものが動いていれば、新しいほうが絵を出せるようになってから止める（切り替えの止まりを短く）。
    /// </summary>
    public void StartCapture(DisplayInfo target, int outW, int outH)
    {
        if (_closed) return;
        lock (_capLock)
        {
            var old = _capture;
            _target = target;
            SendStatus("ok");
            var spec = new CaptureSpec(target.DeviceName, target.Width, target.Height, outW, outH,
                _settings.Fps, Math.Clamp(CurrentMbps, 1, _settings.BitrateMbps), _settings.SkipStatic);
            var cap = new Capture(spec, _settings);
            cap.Config += (w, h, sps, pps) =>
            {
                // 新しい絵の設定が来たら、古いほうの絵はもう送らない
                if (!ReferenceEquals(_capture, cap))
                {
                    var o = _capture;
                    _capture = cap;
                    o?.Dispose();
                    while (_queue.TryTake(out _)) { }
                }
                Send(Protocol.Pack(Protocol.P_CONFIG, new JsonObject
                {
                    ["w"] = w,
                    ["h"] = h,
                    ["fps"] = _settings.Fps,
                    ["sps"] = Convert.ToBase64String(sps),
                    ["pps"] = Convert.ToBase64String(pps),
                }));
                _dropUntilKey = true;
            };
            cap.Frame += (data, key, t) =>
            {
                if (ReferenceEquals(_capture, cap)) OnFrame(data, key, t);
            };
            cap.Failed += reason =>
            {
                if (_closed || !ReferenceEquals(_capture, cap)) return;
                Log.Write("capture failed: " + reason);
                _server.RaiseStatus("st_capture_failed");
            };
            if (old == null) _capture = cap;
            if (cap.Start())
            {
                _server.RaiseStatus("st_streaming", TabletName, outW, outH, cap.EncoderName ?? "");
                if (!ReferenceEquals(_capture, cap))
                {
                    _capture = cap;
                    old?.Dispose();
                }
            }
            else if (old != null && !ReferenceEquals(_capture, cap))
            {
                // 新しいほうが動かなかった：古いほうも画面が変わって使えないので止める
                _capture = null;
                old.Dispose();
                cap.Dispose();
            }
        }
    }

    public void StopCapture()
    {
        lock (_capLock)
        {
            var c = _capture;
            _capture = null;
            c?.Dispose();
            while (_queue.TryTake(out _)) { }
        }
    }

    private void OnFrame(byte[] annexB, bool key, long t)
    {
        if (_closed) return;
        if (_dropUntilKey)
        {
            if (!key) return;
            _dropUntilKey = false;
        }
        // 送りきれずにたまってきたら（Wi-Fi が混んでいるなど）、古い絵を捨ててキーフレームから出し直す
        if (_queue.Count > 6)
        {
            while (_queue.TryTake(out _)) { }
            Interlocked.Increment(ref _drops);
            if (!key)
            {
                _dropUntilKey = true;
                _capture?.RequestKeyFrame();
                return;
            }
        }
        try { _queue.Add(Protocol.PackVideo(annexB, key, t)); }
        catch (InvalidOperationException) { } // 切断と同時に来たとき
    }

    /// <summary>
    /// 画質の自動調整（1 秒ごと）。
    /// 絵を捨てた・遅れが大きい → 7 割に下げる。10 秒問題がなければ 1.25 倍に上げる（設定の値まで）。
    /// 変えるときは圧縮をやり直すので、6 秒に 1 回まで。
    /// </summary>
    public void AdaptTick()
    {
        if (!_settings.AdaptiveBitrate || _capture == null || _closed)
        {
            if (!_settings.AdaptiveBitrate) CurrentMbps = _settings.BitrateMbps;
            return;
        }
        var now = Environment.TickCount64;
        var drops = Interlocked.Exchange(ref _drops, 0);
        bool trouble = drops > 0 || Lat > 120;
        if (trouble) _lastTrouble = now;
        if (now - _lastRateChange < 6000) return;

        int next = CurrentMbps;
        if (trouble) next = Math.Max(2, CurrentMbps * 7 / 10);
        else if (now - _lastTrouble > 10000 && Lat < 60 && CurrentMbps < _settings.BitrateMbps)
            next = Math.Min(_settings.BitrateMbps, Math.Max(CurrentMbps + 1, CurrentMbps * 5 / 4));
        if (next == CurrentMbps) return;

        Log.Write($"{TabletName}: bitrate {CurrentMbps} -> {next} Mbps (drops {drops}, lat {Lat})");
        CurrentMbps = next;
        _lastRateChange = now;
        _server.RequestLayout(this);
    }

    private void SendLoop()
    {
        try
        {
            foreach (var buf in _queue.GetConsumingEnumerable())
            {
                if (_closed) break;
                Send(buf);
            }
        }
        catch { }
    }

    // ---------- 音 ----------

    public void UpdateAudio()
    {
        if (_settings.AudioToTablet && !_closed)
        {
            if (_audioSink != null) return;
            _audioSink = pcm => Send(Protocol.Pack(Protocol.P_AUDIO, pcm));
            AudioHub.Subscribe(_audioSink);
            Send(Protocol.Pack(Protocol.P_AUDIO_CFG, new JsonObject { ["rate"] = AudioHub.Rate, ["ch"] = AudioHub.Channels }));
        }
        else
        {
            StopAudio();
        }
    }

    public void StopAudio()
    {
        var a = _audioSink;
        _audioSink = null;
        if (a != null) AudioHub.Unsubscribe(a);
    }

    // ---------- 送る ----------

    private void SendStatus(string s) => Send(Protocol.Pack(Protocol.P_STATUS, new JsonObject { ["s"] = s }));

    private void Send(byte[] buf)
    {
        if (_closed) return;
        try
        {
            lock (_writeLock) _stream.Write(buf, 0, buf.Length);
        }
        catch
        {
            Close();
        }
    }

    public void Close()
    {
        if (_closed) return;
        _closed = true;
        _queue.CompleteAdding();
        try { _stream.Dispose(); } catch { }
        try { _client.Close(); } catch { }
    }
}
