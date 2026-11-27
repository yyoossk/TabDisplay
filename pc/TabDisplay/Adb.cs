using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

namespace TabDisplay;

/// <summary>
/// USB 接続まわり（adb）。同梱の tools\platform-tools\adb.exe を使う。
///   ・タブレットのアプリを入れる（adb install）
///   ・タブレットの 127.0.0.1:38500 を PC につなぐ（adb reverse）
///   ・タブレットのアプリを起動する（adb shell am start）
/// </summary>
public static class Adb
{
    public const string Package = "com.yyoossk.tabdisplay";
    private static string AdbPath => Path.Combine(AppContext.BaseDirectory, "tools", "platform-tools", "adb.exe");
    public static string ApkPath => Path.Combine(AppContext.BaseDirectory, "apk", "TabDisplay.apk");
    private static string ApkVersionPath => Path.Combine(AppContext.BaseDirectory, "apk", "version.txt");

    public static bool Available => File.Exists(AdbPath);

    public record Device(string Serial, string State, string Model);

    public static (int code, string output) Run(string args, int timeoutMs = 20000)
    {
        try
        {
            var psi = new ProcessStartInfo(AdbPath, args)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
            };
            using var p = Process.Start(psi)!;
            var o = p.StandardOutput.ReadToEndAsync();
            var e = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(timeoutMs))
            {
                try { p.Kill(); } catch { }
                return (-1, "timeout");
            }
            var text = o.Result + e.Result;
            Log.Write($"adb {args} -> {p.ExitCode}: {text.Trim()}");
            return (p.ExitCode, text);
        }
        catch (Exception ex)
        {
            Log.Write("adb: " + ex.Message);
            return (-1, ex.Message);
        }
    }

    public static List<Device> Devices()
    {
        var (_, o) = Run("devices -l", 10000);
        var list = new List<Device>();
        foreach (var raw in o.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("List of") || line.StartsWith("*")) continue;
            var parts = Regex.Split(line, @"\s+");
            if (parts.Length < 2) continue;
            var m = Regex.Match(line, @"model:(\S+)");
            list.Add(new Device(parts[0], parts[1], m.Success ? m.Groups[1].Value.Replace('_', ' ') : parts[0]));
        }
        return list;
    }

    /// <summary>入っているアプリの版（入っていなければ 0）</summary>
    public static int InstalledVersion(string serial)
    {
        var (_, o) = Run($"-s {serial} shell dumpsys package {Package}");
        var m = Regex.Match(o, @"versionCode=(\d+)");
        return m.Success ? int.Parse(m.Groups[1].Value) : 0;
    }

    public static int BundledVersion()
    {
        try { return int.Parse(File.ReadAllText(ApkVersionPath).Trim()); }
        catch { return 0; }
    }

    public static (bool ok, string output) Install(string serial)
    {
        if (!File.Exists(ApkPath)) return (false, "apk not found");
        var (code, o) = Run($"-s {serial} install -r -g \"{ApkPath}\"", 120000);
        if (code != 0 || !o.Contains("Success"))
        {
            // 署名が違う古い版が入っているときは、消してから入れ直す
            if (o.Contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || o.Contains("signatures do not match"))
            {
                Run($"-s {serial} uninstall {Package}");
                (code, o) = Run($"-s {serial} install -g \"{ApkPath}\"", 120000);
            }
        }
        return (code == 0 && o.Contains("Success"), o);
    }

    public static bool Reverse(string serial, int port)
    {
        var (code, _) = Run($"-s {serial} reverse tcp:{port} tcp:{port}");
        return code == 0;
    }

    public static void RemoveReverse(string serial, int port) => Run($"-s {serial} reverse --remove tcp:{port}", 5000);

    public static bool Launch(string serial, int port, string token, string pcName, string pin = "")
    {
        var name = Regex.Replace(pcName, @"[^A-Za-z0-9_\-]", "");
        if (name.Length == 0) name = "PC";
        var cmd = $"am start -n {Package}/.DisplayActivity --es host 127.0.0.1 --ei port {port} --es token {token} --es fp {CertStore.Fingerprint} --es name {name}" +
                  (System.Text.RegularExpressions.Regex.IsMatch(pin, "^[0-9]{1,8}$") ? $" --es pin {pin}" : "");
        var (code, o) = Run($"-s {serial} shell {cmd}");
        return code == 0 && !o.Contains("Error");
    }
}
