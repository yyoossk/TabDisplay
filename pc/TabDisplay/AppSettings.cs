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
