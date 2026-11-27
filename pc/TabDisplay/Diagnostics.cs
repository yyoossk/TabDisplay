using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Text;

namespace TabDisplay;

/// <summary>つながらないときの診断：よくある原因を順に調べて、直し方を書き出す</summary>
public static class Diagnostics
{
    public static string Run(AppSettings s, Server server)
    {
        var sb = new StringBuilder();
        void Ok(string text) => sb.AppendLine("✓ " + text);
        void Ng(string text, string fix) { sb.AppendLine("✗ " + text); sb.AppendLine("   → " + fix); }
        void Info(string text) => sb.AppendLine("・" + text);

        Info($"TabDisplay {Updater.CurrentLabel} / Windows {Environment.OSVersion.Version}");

        // 仮想ディスプレイ
        var st = DeviceControl.GetState();
        if (st == DeviceControl.State.NotInstalled)
            Ng(L.T("dg_vdd_none"), L.T("dg_vdd_none_fix"));
        else
            Ok(L.T("dg_vdd_ok") + (st == DeviceControl.State.Disabled ? L.T("dg_vdd_off") : ""));
        if (st != DeviceControl.State.NotInstalled)
        {
            if (VirtualDisplay.RunProcess("schtasks.exe", $"/Query /TN \"{VirtualDisplay.TaskName}\"", 5000) == 0) Ok(L.T("dg_task_ok"));
            else Ng(L.T("dg_task_none"), L.T("dg_vdd_none_fix"));
        }

        // 待ち受け
        if (server.Listening) Ok(string.Format(L.T("dg_port_ok"), s.Port));
        else Ng(string.Format(L.T("dg_port_ng"), s.Port), L.T("dg_port_fix"));

        // ファイアウォール
        var fw = Capture("netsh.exe", "advfirewall firewall show rule name=\"TabDisplay\"");
        if (fw.Contains("TabDisplay")) Ok(L.T("dg_fw_ok"));
        else Ng(L.T("dg_fw_ng"), L.T("dg_vdd_none_fix"));

        // ネットワーク
        var ips = QrForm.LocalAddresses();
        if (ips.Count == 0) Ng(L.T("dg_net_ng"), L.T("dg_net_fix"));
        else Ok(L.T("dg_net_ok") + string.Join(", ", ips));
        if (TetherAdapter() is string tether) Ok(string.Format(L.T("dg_tether"), tether));
        var profiles = Capture("powershell.exe", "-NoProfile -Command \"Get-NetConnectionProfile | ForEach-Object { $_.Name + ' : ' + $_.NetworkCategory }\"");
        foreach (var line in profiles.Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0))
        {
            if (line.EndsWith("Public")) Info(string.Format(L.T("dg_public"), line));
        }

        // USB（adb）
        if (!Adb.Available) Ng(L.T("no_adb"), L.T("dg_reinstall"));
        else
        {
            var devs = Adb.Devices();
            if (devs.Count == 0) Info(L.T("dg_adb_none"));
            foreach (var d in devs)
            {
                if (d.State == "device") Ok(string.Format(L.T("dg_adb_ok"), d.Model));
                else if (d.State == "unauthorized") Ng(string.Format(L.T("dg_adb_unauth"), d.Serial), L.T("usb_unauthorized"));
                else Ng($"{d.Serial}: {d.State}", L.T("dg_adb_replug"));
            }
        }
        if (File.Exists(Adb.ApkPath)) Ok(L.T("dg_apk_ok")); else Ng(L.T("dg_apk_ng"), L.T("dg_reinstall"));

        // ffmpeg とグラボの圧縮
        var ff = Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe");
        if (!File.Exists(ff)) Ng(L.T("dg_ffmpeg_ng"), L.T("dg_reinstall"));
        else
        {
            var enc = Capture(ff, "-hide_banner -encoders");
            var have = new[] { "h264_nvenc", "h264_amf", "h264_qsv", "h264_mf", "hevc_nvenc", "hevc_amf", "hevc_qsv", "hevc_mf" }
                .Where(e => enc.Contains(e)).ToList();
            Ok(L.T("dg_ffmpeg_ok") + string.Join(", ", have));
            if (!string.IsNullOrEmpty(s.LastEncoder)) Info(L.T("dg_last_enc") + s.LastEncoder + (s.LastEncoderHevc.Length > 0 ? " / " + s.LastEncoderHevc : ""));
        }

        // 画面
        foreach (var d in Displays.Attached())
            Info($"{d.DeviceName} {d.Width}x{d.Height} @{d.Frequency}Hz ({d.Description}){(d.Primary ? " *" : "")}");

        return sb.ToString();
    }

    /// <summary>USB テザリング（RNDIS）のネットワークがあれば、その名前</summary>
    public static string? TetherAdapter()
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            var d = ni.Description.ToLowerInvariant();
            if (d.Contains("remote ndis") || d.Contains("rndis") || d.Contains("android usb ethernet"))
                return ni.Name;
        }
        return null;
    }

    public static string Capture(string exe, string args)
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
            var o = p.StandardOutput.ReadToEndAsync();
            var e = p.StandardError.ReadToEndAsync();
            p.WaitForExit(15000);
            return o.Result + e.Result;
        }
        catch (Exception ex)
        {
            return ex.Message;
        }
    }
}

/// <summary>不具合の報告用：PC とタブレットのログを 1 つの zip にまとめる</summary>
public static class LogBundle
{
    public static string Create(AppSettings s, Server server)
    {
        var name = $"TabDisplay-logs-{DateTime.Now:yyyyMMdd-HHmmss}.zip";
        var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), name);
        using var zip = System.IO.Compression.ZipFile.Open(path, System.IO.Compression.ZipArchiveMode.Create);
        void AddText(string entry, string text)
        {
            var e = zip.CreateEntry(entry);
            using var w = new StreamWriter(e.Open(), new UTF8Encoding(true));
            w.Write(text);
        }
        foreach (var f in new[] { Log.FilePath, Log.FilePath + ".old" })
        {
            try
            {
                if (File.Exists(f))
                {
                    using var fs = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                    using var r = new StreamReader(fs);
                    AddText("pc/" + Path.GetFileName(f), r.ReadToEnd());
                }
            }
            catch { }
        }
        // 設定（合言葉は伏せる）
        try
        {
            var json = File.ReadAllText(Path.Combine(AppSettings.Dir, "settings.json"));
            json = System.Text.RegularExpressions.Regex.Replace(json, "\"Token\"\\s*:\\s*\"[^\"]*\"", "\"Token\": \"***\"");
            AddText("pc/settings.json", json);
        }
        catch { }
        AddText("pc/diagnostics.txt", Diagnostics.Run(s, server));
        // タブレットのアプリが落ちたときの記録（つないだときに届いたもの）
        var crashes = Path.Combine(AppSettings.Dir, "tablet-crashes");
        if (Directory.Exists(crashes))
            foreach (var f in Directory.GetFiles(crashes, "*.txt").OrderByDescending(f => f).Take(20))
                AddText("tablet-crashes/" + Path.GetFileName(f), File.ReadAllText(f));
        // USB でつないでいるタブレットのログ
        if (Adb.Available)
        {
            foreach (var d in Adb.Devices().Where(d => d.State == "device"))
            {
                var (_, log) = Adb.Run($"-s {d.Serial} logcat -d -v time -t 5000", 30000);
                AddText($"tablet-{d.Serial}/logcat.txt", log);
                var (_, info) = Adb.Run($"-s {d.Serial} shell getprop ro.build.version.release; getprop ro.product.model", 10000);
                AddText($"tablet-{d.Serial}/device.txt", info);
            }
        }
        return path;
    }
}

/// <summary>
/// 自動更新：GitHub の最新のリリース（build-番号）と比べて、新しければインストーラーを落として動かす。
/// タブレットのアプリは、PC が新しくなったあと USB でつないだときに自動で入れ替わる。
/// </summary>
public static class Updater
{
    private const string Api = "https://api.github.com/repos/yyoossk/tabdisplay/releases/latest";

    /// <summary>この版の番号（ビルドのときに build-番号 を入れる。手元で組んだものは 0）</summary>
    public static int CurrentBuild
    {
        get
        {
            var v = Assembly.GetEntryAssembly()?.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "";
            v = v.Split('+')[0];
            return v.StartsWith("build-") && int.TryParse(v[6..], out var n) ? n : 0;
        }
    }

    public static string CurrentLabel => CurrentBuild > 0 ? $"build {CurrentBuild}" : "dev";

    public record Release(int Build, string Tag, string? SetupUrl);

    public static async Task<Release?> CheckAsync()
    {
        try
        {
            using var http = new HttpClient();
            http.DefaultRequestHeaders.UserAgent.ParseAdd("TabDisplay");
            http.Timeout = TimeSpan.FromSeconds(15);
            var json = System.Text.Json.Nodes.JsonNode.Parse(await http.GetStringAsync(Api));
            var tag = (string?)json?["tag_name"] ?? "";
            if (!tag.StartsWith("build-") || !int.TryParse(tag[6..], out var n)) return null;
            string? url = null;
            if (json?["assets"] is System.Text.Json.Nodes.JsonArray assets)
                foreach (var a in assets)
                    if ((string?)a?["name"] == "TabDisplay-Setup.exe") url = (string?)a?["browser_download_url"];
            return new Release(n, tag, url);
        }
        catch (Exception e)
        {
            Log.Write("update check: " + e.Message);
            return null;
        }
    }

    /// <summary>インストーラーを落として、静かに上書きインストールする（終わると TabDisplay がまた開く）</summary>
    public static async Task<bool> InstallAsync(Release r)
    {
        if (r.SetupUrl == null) return false;
        try
        {
            using var http = new HttpClient();
            http.DefaultRequestHeaders.UserAgent.ParseAdd("TabDisplay");
            var file = Path.Combine(Path.GetTempPath(), $"TabDisplay-Setup-{r.Build}.exe");
            await File.WriteAllBytesAsync(file, await http.GetByteArrayAsync(r.SetupUrl));
            Process.Start(new ProcessStartInfo(file, "/SILENT /SUPPRESSMSGBOXES /NORESTART /TASKS=\"\"") { UseShellExecute = true });
            return true;
        }
        catch (Exception e)
        {
            Log.Write("update install: " + e.Message);
            return false;
        }
    }
}
