using System.Security.Cryptography;
using System.Text.Json;

namespace TabDisplay;

/// <summary>表示のしかた</summary>
public enum ShowMode
{
    /// <summary>PC と両方（画面を広げる）</summary>
    Extend = 0,
    /// <summary>PC と両方（同じ画面を映す）</summary>
    Duplicate = 1,
    /// <summary>タブレットのみ（PC の画面は消す）</summary>
    TabletOnly = 2,
    /// <summary>PC のみ（タブレットには映さない）</summary>
    PcOnly = 3,
    /// <summary>PC の画面の一部（ウィンドウ 1 つ・選んだ範囲）だけ映す</summary>
    Region = 4,
}

/// <summary>画質の方針</summary>
public enum QualityMode { Auto = 0, Text = 1, Motion = 2 }

/// <summary>名前をつけて保存した設定の組み合わせ</summary>
public class Profile
{
    public string Name { get; set; } = "";
    public ShowMode Mode { get; set; }
    public TabletSide Side { get; set; }
    public bool FollowRotation { get; set; }
    public int Fps { get; set; }
    public int BitrateMbps { get; set; }
    public bool AudioToTablet { get; set; }
    public bool AutoDpi { get; set; }
    public bool AdaptiveBitrate { get; set; }
    public bool SkipStatic { get; set; }
    public QualityMode Quality { get; set; }
    public string RegionWindowTitle { get; set; } = "";
    public int[] RegionRect { get; set; } = Array.Empty<int>();
    public List<string> LaunchApps { get; set; } = new();

    public static Profile From(AppSettings s, string name) => new()
    {
        Name = name, Mode = s.Mode, Side = s.Side, FollowRotation = s.FollowRotation, Fps = s.Fps,
        BitrateMbps = s.BitrateMbps, AudioToTablet = s.AudioToTablet, AutoDpi = s.AutoDpi,
        AdaptiveBitrate = s.AdaptiveBitrate, SkipStatic = s.SkipStatic, Quality = s.Quality,
        RegionWindowTitle = s.RegionWindowTitle, RegionRect = s.RegionRect.ToArray(),
        LaunchApps = s.LaunchApps.ToList(),
    };

    public void ApplyTo(AppSettings s)
    {
        s.Mode = Mode; s.Side = Side; s.FollowRotation = FollowRotation; s.Fps = Fps;
        s.BitrateMbps = BitrateMbps; s.AudioToTablet = AudioToTablet; s.AutoDpi = AutoDpi;
        s.AdaptiveBitrate = AdaptiveBitrate; s.SkipStatic = SkipStatic; s.Quality = Quality;
        s.RegionWindowTitle = RegionWindowTitle; s.RegionRect = RegionRect.ToArray();
        s.LaunchApps = LaunchApps.ToList();
    }
}

/// <summary>タブレットを PC の画面のどちら側に置くか（広げるとき）</summary>
public enum TabletSide { Right = 0, Left = 1, Top = 2, Bottom = 3 }

/// <summary>設定。%APPDATA%\TabDisplay\settings.json に保存する</summary>
public class AppSettings
{
    public ShowMode Mode { get; set; } = ShowMode.Extend;
    /// <summary>タブレットを回したら PC 側の画面も縦横を合わせる</summary>
    public bool FollowRotation { get; set; } = true;
    public int Fps { get; set; } = 60;
    /// <summary>Mbps</summary>
    public int BitrateMbps { get; set; } = 12;
    /// <summary>"" = システムの言語、"ja"、"en"</summary>
    public string Language { get; set; } = "";
    public bool StartWithWindows { get; set; } = false;
    /// <summary>起動したら右下のアイコンにしまう（Windows 起動時の自動起動のとき）</summary>
    public bool StartInTray { get; set; } = true;
    /// <summary>タブレットを USB で挿したら自動で接続する</summary>
    public bool AutoConnectUsb { get; set; } = false;
    /// <summary>PC の音もタブレットで鳴らす</summary>
    public bool AudioToTablet { get; set; } = false;
    /// <summary>仮想ディスプレイの拡大率（文字の大きさ）をタブレットに合わせる</summary>
    public bool AutoDpi { get; set; } = true;
    /// <summary>回線の混み具合に合わせて画質を自動で上げ下げする（BitrateMbps が上限）</summary>
    public bool AdaptiveBitrate { get; set; } = true;
    /// <summary>タブレットの置き場所（PC の画面の右・左・上・下）</summary>
    public TabletSide Side { get; set; } = TabletSide.Right;
    /// <summary>省電力：画面に変化がないときは送らない</summary>
    public bool SkipStatic { get; set; } = true;
    /// <summary>画質の方針（自動・文字をくっきり・動きをなめらか）</summary>
    public QualityMode Quality { get; set; } = QualityMode.Auto;
    /// <summary>「一部だけ映す」で映すウィンドウのタイトル（空なら RegionRect の範囲）</summary>
    public string RegionWindowTitle { get; set; } = "";
    /// <summary>「一部だけ映す」で映す範囲（デスクトップの座標 x, y, 幅, 高さ）</summary>
    public int[] RegionRect { get; set; } = Array.Empty<int>();
    /// <summary>タブレットをつないだら、タブレットの画面で開くソフト（1 つずつコマンド）</summary>
    public List<string> LaunchApps { get; set; } = new();
    /// <summary>名前をつけて保存した設定の組み合わせ</summary>
    public List<Profile> Profiles { get; set; } = new();
    /// <summary>ショートカットキー（動作の名前 → 「Ctrl+Alt+T」など。空はなし）</summary>
    public Dictionary<string, string> Hotkeys { get; set; } = new();
    /// <summary>PC の通知をタブレットに出す</summary>
    public bool ForwardNotifications { get; set; } = false;
    /// <summary>「PC のみ」に切り替える前の表示のしかた（キーで戻すため）</summary>
    public ShowMode ModeBeforePcOnly { get; set; } = ShowMode.Extend;
    /// <summary>タブレットのカメラを PC の Web カメラにする</summary>
    public bool TabletCamera { get; set; } = false;
    /// <summary>使うカメラ（"back" / "front"）</summary>
    public string CameraFacing { get; set; } = "front";
    /// <summary>タブレットのマイクの音を PC で鳴らす</summary>
    public bool TabletMic { get; set; } = false;
    /// <summary>マイクの音を鳴らす先（空なら VB-CABLE を探す）</summary>
    public string MicOutput { get; set; } = "";
    /// <summary>タブレットのランチャーに並べるソフト（空ならタスクバーにピン留めしたソフト）</summary>
    public List<string> LauncherApps { get; set; } = new();
    /// <summary>PC を何分使わなかったらタブレットの画面を消すか（0 = 消さない）</summary>
    public int SleepMinutes { get; set; } = 10;
    /// <summary>はじめの設定の案内をもう見た</summary>
    public bool WizardDone { get; set; } = false;
    /// <summary>最後に更新を確かめた日時</summary>
    public DateTime LastUpdateCheck { get; set; } = DateTime.MinValue;
    /// <summary>クリップボード（コピーした文字）をタブレットと共有する</summary>
    public bool ShareClipboard { get; set; } = true;
    /// <summary>H.265 で送る（グラボとタブレットが対応しているとき）</summary>
    public bool UseHevc { get; set; } = true;
    /// <summary>うまく動いた H.265 の圧縮方式</summary>
    public string LastEncoderHevc { get; set; } = "";
    /// <summary>うまく動いた圧縮方式（次回はこれから試す）</summary>
    public string LastEncoder { get; set; } = "";
    /// <summary>Wi-Fi 接続の合言葉（QR に入れる）</summary>
    public string Token { get; set; } = "";
    public int Port { get; set; } = 38500;

    // ---------- 保存 ----------

    public static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "TabDisplay");
    private static string FilePath => Path.Combine(Dir, "settings.json");
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static AppSettings Load()
    {
        AppSettings s;
        try
        {
            s = File.Exists(FilePath)
                ? JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings()
                : new AppSettings();
        }
        catch
        {
            s = new AppSettings();
        }
        if (string.IsNullOrEmpty(s.Token))
        {
            s.Token = NewToken();
            s.Save();
        }
        return s;
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Json));
        }
        catch (Exception e)
        {
            Log.Write("settings save: " + e.Message);
        }
    }

    public static string NewToken()
    {
        const string chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        var b = RandomNumberGenerator.GetBytes(16);
        return new string(b.Select(x => chars[x % chars.Length]).ToArray());
    }
}

public static class Log
{
    private static readonly object Lock = new();
    public static string FilePath => Path.Combine(AppSettings.Dir, "log.txt");

    public static void Write(string s)
    {
        lock (Lock)
        {
            try
            {
                Directory.CreateDirectory(AppSettings.Dir);
                var fi = new FileInfo(FilePath);
                if (fi.Exists && fi.Length > 2 * 1024 * 1024)
                {
                    File.Copy(FilePath, FilePath + ".old", true);
                    File.Delete(FilePath);
                }
                File.AppendAllText(FilePath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {s}{Environment.NewLine}");
            }
            catch { }
        }
    }
}
