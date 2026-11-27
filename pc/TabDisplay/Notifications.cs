using System.Text.RegularExpressions;
using System.Xml.Linq;
using Microsoft.Data.Sqlite;

namespace TabDisplay;

/// <summary>
/// PC の通知をタブレットに出す。
/// Windows が通知をしまっている場所（%LOCALAPPDATA%\Microsoft\Windows\Notifications\wpndatabase.db）を
/// 3 秒ごとに写して読み、新しい通知の題名と本文を取り出す（ふつうのソフトから通知を受け取る公式の方法がないため）。
/// </summary>
public sealed class NotificationWatcher : IDisposable
{
    private readonly System.Threading.Timer _timer;
    private readonly Action<string, string, string> _onNew; // アプリ, 題名, 本文
    private readonly Func<bool> _enabled;
    private long _lastId = -1;
    private int _busy;

    private static string Db => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Microsoft", "Windows", "Notifications", "wpndatabase.db");

    public NotificationWatcher(Func<bool> enabled, Action<string, string, string> onNew)
    {
        _enabled = enabled;
        _onNew = onNew;
        _timer = new System.Threading.Timer(_ => Tick(), null, 5000, 3000);
    }

    private void Tick()
    {
        if (!_enabled() || !File.Exists(Db)) return;
        if (Interlocked.Exchange(ref _busy, 1) == 1) return;
        var tmp = Path.Combine(Path.GetTempPath(), "tabdisplay-wpn");
        try
        {
            Directory.CreateDirectory(tmp);
            // 使用中のファイルなので、写してから読む（-wal も一緒に）
            foreach (var ext in new[] { "", "-wal", "-shm" })
            {
                var src = Db + ext;
                var dst = Path.Combine(tmp, "wpn.db" + ext);
                if (File.Exists(src))
                {
                    using var i = new FileStream(src, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                    using var o = new FileStream(dst, FileMode.Create);
                    i.CopyTo(o);
                }
                else if (File.Exists(dst)) File.Delete(dst);
            }
            using var con = new SqliteConnection($"Data Source={Path.Combine(tmp, "wpn.db")}");
            con.Open();
            using var cmd = con.CreateCommand();
            if (_lastId < 0)
            {
                // 初回は今ある通知を出さない
                cmd.CommandText = "SELECT IFNULL(MAX(Id), 0) FROM Notification";
                _lastId = Convert.ToInt64(cmd.ExecuteScalar());
                return;
            }
            cmd.CommandText = "SELECT n.Id, IFNULL(h.PrimaryId, ''), n.Payload FROM Notification n " +
                              "LEFT JOIN NotificationHandler h ON n.HandlerId = h.RecordId " +
                              "WHERE n.Type = 'toast' AND n.Id > $last ORDER BY n.Id";
            cmd.Parameters.AddWithValue("$last", _lastId);
            using var r = cmd.ExecuteReader();
            while (r.Read())
            {
                _lastId = r.GetInt64(0);
                var app = AppName(r.GetString(1));
                var payload = r.IsDBNull(2) ? "" : System.Text.Encoding.UTF8.GetString((byte[])r.GetValue(2));
                var texts = Texts(payload);
                if (texts.Count == 0) continue;
                _onNew(app, texts[0], string.Join("\n", texts.Skip(1)));
            }
        }
        catch (Exception e)
        {
            Log.Write("notifications: " + e.Message);
        }
        finally
        {
            Interlocked.Exchange(ref _busy, 0);
        }
    }

    /// <summary>「Microsoft.Office.OUTLOOK.EXE.15」「...!App」などから、それらしい名前に</summary>
    private static string AppName(string id)
    {
        var s = id.Split('!')[0];
        s = Regex.Replace(s, @"_[a-z0-9]{13}$", "");   // パッケージのしるしを外す
        var parts = s.Split('.', '\\');
        var name = parts.LastOrDefault(p => p.Length > 2 && !Regex.IsMatch(p, @"^(exe|\d+)$", RegexOptions.IgnoreCase)) ?? s;
        return name;
    }

    private static List<string> Texts(string xml)
    {
        try
        {
            var doc = XDocument.Parse(xml);
            return doc.Descendants("text").Select(t => t.Value.Trim()).Where(t => t.Length > 0).Take(4).ToList();
        }
        catch
        {
            return new List<string>();
        }
    }

    public void Dispose() => _timer.Dispose();
}

/// <summary>使用時間と通信量の記録（日ごと）。%APPDATA%\TabDisplay\usage.json</summary>
public static class Usage
{
    private static readonly object Lock = new();
    private static Dictionary<string, long[]>? _data; // 日付 → [秒, バイト]
    private static string FilePath => Path.Combine(AppSettings.Dir, "usage.json");
    private static long _lastSave;

    private static Dictionary<string, long[]> Data()
    {
        if (_data != null) return _data;
        try
        {
            _data = File.Exists(FilePath)
                ? System.Text.Json.JsonSerializer.Deserialize<Dictionary<string, long[]>>(File.ReadAllText(FilePath)) ?? new()
                : new();
        }
        catch { _data = new(); }
        return _data;
    }

    public static void Add(int seconds, long bytes)
    {
        lock (Lock)
        {
            var key = DateTime.Now.ToString("yyyy-MM-dd");
            var d = Data();
            if (!d.TryGetValue(key, out var v)) d[key] = v = new long[2];
            v[0] += seconds;
            v[1] += bytes;
            if (Environment.TickCount64 - _lastSave > 60000) Save();
        }
    }

    public static void Save()
    {
        lock (Lock)
        {
            try
            {
                Directory.CreateDirectory(AppSettings.Dir);
                // 1 年より前は消す
                var cut = DateTime.Now.AddDays(-366).ToString("yyyy-MM-dd");
                foreach (var k in Data().Keys.Where(k => string.CompareOrdinal(k, cut) < 0).ToList()) Data().Remove(k);
                File.WriteAllText(FilePath, System.Text.Json.JsonSerializer.Serialize(Data()));
                _lastSave = Environment.TickCount64;
            }
            catch { }
        }
    }

    /// <summary>最近の日から順に（日付, 時間, 通信量）</summary>
    public static List<(string day, TimeSpan time, long bytes)> Recent(int days)
    {
        lock (Lock)
            return Data().OrderByDescending(kv => kv.Key).Take(days)
                .Select(kv => (kv.Key, TimeSpan.FromSeconds(kv.Value[0]), kv.Value[1])).ToList();
    }

    public static string Bytes(long b) =>
        b >= 1L << 30 ? $"{b / (double)(1L << 30):0.0} GB" : b >= 1L << 20 ? $"{b / (double)(1L << 20):0} MB" : $"{b / 1024} KB";
}
