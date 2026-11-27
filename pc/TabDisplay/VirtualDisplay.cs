using System.Diagnostics;
using System.Xml.Linq;
using Microsoft.Win32;

namespace TabDisplay;

/// <summary>
/// 仮想ディスプレイ（Virtual Display Driver, MIT ライセンス）を使って、タブレット用の画面を増やす。
///
/// ・解像度の一覧は設定ファイル（C:\VirtualDisplayDriver\vdd_settings.xml）に書く
/// ・ドライバーは読み込むときにしか設定ファイルを読まないので、変えたらデバイスを再起動する
/// ・デバイスのオン／オフ／再起動は管理者が要るので、インストール時に作った
///   「最上位の特権で実行するタスク」（TabDisplay Helper）経由で行う（UAC の確認なし）
/// </summary>
public static class VirtualDisplay
{
    public const string TaskName = "TabDisplay Helper";

    public static string SettingsDir
    {
        get
        {
            try
            {
                using var k = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\MikeTheTech\VirtualDisplayDriver");
                if (k?.GetValue("VDDPATH") is string p && p.Length > 0) return p;
            }
            catch { }
            return @"C:\VirtualDisplayDriver";
        }
    }

    public static string SettingsFile => Path.Combine(SettingsDir, "vdd_settings.xml");

    public static bool IsInstalled => DeviceControl.GetState() != DeviceControl.State.NotInstalled;

    /// <summary>よく使う解像度（タブレットの大きさは接続時に足す）</summary>
    private static readonly (int w, int h)[] Defaults =
    {
        (1920, 1080), (1080, 1920), (1920, 1200), (1200, 1920), (1280, 800), (800, 1280),
        (2560, 1600), (1600, 2560), (2000, 1200), (1200, 2000), (1280, 720), (720, 1280),
    };

    /// <summary>
    /// 設定ファイルに「画面の枚数」「この解像度（縦横両方）」を書く。中身が変わったら true。
    /// </summary>
    public static bool WriteSettings(int count, IEnumerable<(int w, int h)> sizes, int hz)
    {
        XDocument doc;
        try
        {
            doc = File.Exists(SettingsFile) ? XDocument.Load(SettingsFile) : LoadTemplate();
        }
        catch
        {
            doc = LoadTemplate();
        }
        var root = doc.Root!;
        var before = doc.ToString();

        var monitors = root.Element("monitors") ?? new XElement("monitors");
        if (monitors.Parent == null) root.AddFirst(monitors);
        monitors.SetElementValue("count", Math.Max(1, count));

        var res = root.Element("resolutions");
        if (res == null)
        {
            res = new XElement("resolutions");
            root.Add(res);
        }
        var have = res.Elements("resolution")
            .Select(e => ((int?)e.Element("width") ?? 0, (int?)e.Element("height") ?? 0, (int?)e.Element("refresh_rate") ?? 60))
            .ToList();
        void Ensure(int ww, int hh)
        {
            if (have.Any(x => x.Item1 == ww && x.Item2 == hh && x.Item3 == hz)) return;
            res.Add(new XElement("resolution",
                new XElement("width", ww), new XElement("height", hh), new XElement("refresh_rate", hz)));
            have.Add((ww, hh, hz));
        }
        foreach (var (dw, dh) in Defaults) Ensure(dw, dh);
        foreach (var (w, h) in sizes)
        {
            Ensure(w, h);
            Ensure(h, w);
        }

        var after = doc.ToString();
        if (after == before && File.Exists(SettingsFile)) return false;
        try
        {
            Directory.CreateDirectory(SettingsDir);
            doc.Save(SettingsFile);
        }
        catch (Exception e)
        {
            Log.Write("WriteSettings: " + e.Message);
            // 書けない（権限がない）ときは Helper に書いてもらう
            var tmp = Path.Combine(AppSettings.Dir, "vdd_settings.pending.xml");
            Directory.CreateDirectory(AppSettings.Dir);
            doc.Save(tmp);
        }
        return true;
    }

    private static XDocument LoadTemplate()
    {
        using var s = typeof(VirtualDisplay).Assembly.GetManifestResourceStream("vdd_settings.template.xml")!;
        return XDocument.Load(s);
    }

    /// <summary>
    /// タブレットの台数ぶん仮想ディスプレイを出して、それぞれの解像度にする。
    /// 返す一覧は sizes と同じ順（だめなら null）。
    /// </summary>
    public static List<DisplayInfo>? EnableMany(IList<(int w, int h)> sizes, int hz)
    {
        int n = sizes.Count;
        var changed = WriteSettings(n, sizes, hz);
        var now = Displays.FindVirtuals();
        if (now.Count != n || changed)
        {
            RunHelper(now.Count == 0 ? "on" : "restart");
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < 20000)
            {
                Thread.Sleep(300);
                now = Displays.FindVirtuals();
                if (now.Count >= n) break;
            }
            if (now.Count < n) return null;
            Thread.Sleep(500);
        }
        var names = now.Take(n).Select(d => d.DeviceName).ToList();
        for (int i = 0; i < n; i++) Displays.SetMode(names[i], sizes[i].w, sizes[i].h, hz);
        return Refetch(names);
    }

    /// <summary>名前の順のまま、今の位置・大きさを取り直す</summary>
    public static List<DisplayInfo>? Refetch(IList<string> names)
    {
        var all = Displays.FindVirtuals();
        var list = new List<DisplayInfo>();
        foreach (var name in names)
        {
            var d = all.FirstOrDefault(x => string.Equals(x.DeviceName, name, StringComparison.OrdinalIgnoreCase));
            if (d == null) return null;
            list.Add(d);
        }
        return list;
    }

    /// <summary>仮想ディスプレイを消す（タブレットを外したとき）</summary>
    public static void Disable()
    {
        if (!IsInstalled) return;
        if (Displays.FindVirtual() == null && DeviceControl.GetState() == DeviceControl.State.Disabled) return;
        RunHelper("off");
    }

    /// <summary>
    /// 管理者で動く Helper に頼む。タスクがあればそれを使い（確認なし）、なければ UAC の確認を出して起動する。
    /// </summary>
    public static void RunHelper(string action)
    {
        try
        {
            Directory.CreateDirectory(AppSettings.Dir);
            File.WriteAllText(HelperRequestFile, action);
            var done = HelperDoneFile;
            try { File.Delete(done); } catch { }

            var ok = RunProcess("schtasks.exe", $"/Run /TN \"{TaskName}\"", 5000) == 0;
            if (!ok)
            {
                var psi = new ProcessStartInfo(Environment.ProcessPath!, "--vdd-helper")
                {
                    UseShellExecute = true,
                    Verb = "runas",
                };
                Process.Start(psi);
            }
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < 15000 && !File.Exists(done)) Thread.Sleep(100);
        }
        catch (Exception e)
        {
            Log.Write("RunHelper: " + e.Message);
        }
    }

    public static string HelperRequestFile => Path.Combine(AppSettings.Dir, "vdd-request.txt");
    public static string HelperDoneFile => Path.Combine(AppSettings.Dir, "vdd-done.txt");

    /// <summary>--vdd-helper で起動されたとき（管理者）</summary>
    public static void HelperMain()
    {
        try
        {
            var action = File.Exists(HelperRequestFile) ? File.ReadAllText(HelperRequestFile).Trim() : "on";
            var pending = Path.Combine(AppSettings.Dir, "vdd_settings.pending.xml");
            if (File.Exists(pending))
            {
                Directory.CreateDirectory(SettingsDir);
                File.Copy(pending, SettingsFile, true);
                File.Delete(pending);
            }
            DeviceControl.Change(action);
        }
        catch (Exception e)
        {
            Log.Write("HelperMain: " + e);
        }
        finally
        {
            try { File.WriteAllText(HelperDoneFile, "done"); } catch { }
        }
    }

    public static int RunProcess(string exe, string args, int timeoutMs)
    {
        try
        {
            var psi = new ProcessStartInfo(exe, args)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            using var p = Process.Start(psi)!;
            p.StandardOutput.ReadToEndAsync();
            p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(timeoutMs)) { try { p.Kill(); } catch { } return -1; }
            return p.ExitCode;
        }
        catch (Exception e)
        {
            Log.Write($"RunProcess {exe}: {e.Message}");
            return -1;
        }
    }
}
