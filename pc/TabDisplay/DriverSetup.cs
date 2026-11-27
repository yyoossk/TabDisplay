using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Xml.Linq;

namespace TabDisplay;

/// <summary>
/// 管理者で動かす作業：仮想ディスプレイドライバーの導入・削除、Helper タスク、ファイアウォール。
/// 同梱物（ビルド時に入れる）:
///   driver\MttVDD.inf ほか（Virtual Display Driver の署名済みドライバー）
///   tools\nefcon\nefconw.exe（ドライバーを入れる道具）
/// </summary>
public static class DriverSetup
{
    private static string AppDir => AppContext.BaseDirectory;
    private static string DriverDir => Path.Combine(AppDir, "driver");
    private static string Nefcon => Path.Combine(AppDir, "tools", "nefcon", "nefconw.exe");
    private const string FirewallRule = "TabDisplay";

    public static bool IsAdmin()
    {
        using var id = WindowsIdentity.GetCurrent();
        return new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);
    }

    /// <summary>管理者で自分を起動し直して、引数の作業をさせる。終わるまで待つ</summary>
    public static bool RunElevated(string arg)
    {
        try
        {
            var p = Process.Start(new ProcessStartInfo(Environment.ProcessPath!, arg)
            {
                UseShellExecute = true,
                Verb = "runas",
            });
            p?.WaitForExit();
            return p?.ExitCode == 0;
        }
        catch (Exception e)
        {
            Log.Write("RunElevated: " + e.Message);
            return false;
        }
    }

    /// <summary>--install-driver</summary>
    public static int Install()
    {
        try
        {
            var inf = Directory.GetFiles(DriverDir, "*.inf", SearchOption.AllDirectories)
                .FirstOrDefault(f => Path.GetFileName(f).Equals("MttVDD.inf", StringComparison.OrdinalIgnoreCase));
            if (inf == null) { Log.Write("driver inf not found"); return 2; }

            // 1. ドライバーの署名の証明書を「信頼された発行元」に入れる（確認の窓を出さないため）
            var cat = Directory.GetFiles(Path.GetDirectoryName(inf)!, "*.cat").FirstOrDefault();
            if (cat != null)
            {
                try
                {
                    var certs = new X509Certificate2Collection();
                    certs.Import(File.ReadAllBytes(cat));
                    using var store = new X509Store(StoreName.TrustedPublisher, StoreLocation.LocalMachine);
                    store.Open(OpenFlags.ReadWrite);
                    foreach (var c in certs) store.Add(c);
                }
                catch (Exception e) { Log.Write("cert import: " + e.Message); }
            }

            // 2. 設定ファイルの置き場所を作り、ふつうのユーザーでも書けるようにする
            var dir = VirtualDisplay.SettingsDir;
            Directory.CreateDirectory(dir);
            try
            {
                var di = new DirectoryInfo(dir);
                var sec = di.GetAccessControl();
                foreach (var sid in new[] { WellKnownSidType.BuiltinUsersSid, WellKnownSidType.AuthenticatedUserSid, WellKnownSidType.LocalServiceSid })
                {
                    sec.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null),
                        FileSystemRights.Modify,
                        InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                        PropagationFlags.None, AccessControlType.Allow));
                }
                di.SetAccessControl(sec);
            }
            catch (Exception e) { Log.Write("acl: " + e.Message); }
            if (!File.Exists(VirtualDisplay.SettingsFile))
                VirtualDisplay.WriteSettings(1, new[] { (1920, 1080) }, 60);

            // 3. ドライバーを入れる
            if (DeviceControl.GetState() == DeviceControl.State.NotInstalled)
            {
                var r = Run(Nefcon, $"install \"{inf}\" \"{DeviceControl.HardwareId}\"", Path.GetDirectoryName(inf)!);
                Log.Write("nefcon install -> " + r);
                var sw = Stopwatch.StartNew();
                while (sw.ElapsedMilliseconds < 20000 && DeviceControl.GetState() == DeviceControl.State.NotInstalled)
                    Thread.Sleep(500);
            }

            // 4. 管理者の作業を確認なしで頼めるタスクを作る
            CreateHelperTask();

            // 5. Wi-Fi 接続を受けられるようにファイアウォールを開ける
            Run("netsh.exe", $"advfirewall firewall delete rule name=\"{FirewallRule}\"", null);
            Run("netsh.exe", $"advfirewall firewall add rule name=\"{FirewallRule}\" dir=in action=allow protocol=TCP " +
                             $"localport={AppSettings.Load().Port},{WebServer.Port} program=\"{Environment.ProcessPath}\" enable=yes profile=any", null);

            // 6. タブレットをつなぐまでは画面を増やさない
            Thread.Sleep(2000);
            DeviceControl.Change("off");

            return DeviceControl.GetState() == DeviceControl.State.NotInstalled ? 3 : 0;
        }
        catch (Exception e)
        {
            Log.Write("Install: " + e);
            return 1;
        }
    }

    /// <summary>--uninstall-driver</summary>
    public static int Uninstall()
    {
        try
        {
            Displays.RestoreIfNeeded();
            Run(Nefcon, $"--remove-device-node --hardware-id \"{DeviceControl.HardwareId}\" --class-guid \"{{{DeviceControl.DisplayClass}}}\"", null);
            Run("schtasks.exe", $"/Delete /F /TN \"{VirtualDisplay.TaskName}\"", null);
            Run("netsh.exe", $"advfirewall firewall delete rule name=\"{FirewallRule}\"", null);
            return 0;
        }
        catch (Exception e)
        {
            Log.Write("Uninstall: " + e);
            return 1;
        }
    }

    /// <summary>
    /// 「最上位の特権で実行する」タスクを作る。ふつうの権限のソフトから schtasks /Run すると、
    /// UAC の確認なしで管理者として動く（自分のアカウントのタスクだけ）。
    /// </summary>
    private static void CreateHelperTask()
    {
        using var id = WindowsIdentity.GetCurrent();
        XNamespace ns = "http://schemas.microsoft.com/windows/2004/02/mit/task";
        var xml = new XDocument(
            new XElement(ns + "Task", new XAttribute("version", "1.2"),
                new XElement(ns + "RegistrationInfo",
                    new XElement(ns + "Description", "TabDisplay: turn the virtual display on/off")),
                new XElement(ns + "Principals",
                    new XElement(ns + "Principal", new XAttribute("id", "Author"),
                        new XElement(ns + "UserId", id.User!.Value),
                        new XElement(ns + "LogonType", "InteractiveToken"),
                        new XElement(ns + "RunLevel", "HighestAvailable"))),
                new XElement(ns + "Settings",
                    new XElement(ns + "MultipleInstancesPolicy", "Queue"),
                    new XElement(ns + "DisallowStartIfOnBatteries", "false"),
                    new XElement(ns + "StopIfGoingOnBatteries", "false"),
                    new XElement(ns + "ExecutionTimeLimit", "PT1M"),
                    new XElement(ns + "Priority", "4")),
                new XElement(ns + "Actions", new XAttribute("Context", "Author"),
                    new XElement(ns + "Exec",
                        new XElement(ns + "Command", Environment.ProcessPath),
                        new XElement(ns + "Arguments", "--vdd-helper")))));
        var tmp = Path.Combine(Path.GetTempPath(), "tabdisplay-task.xml");
        using (var w = new StreamWriter(tmp, false, System.Text.Encoding.Unicode)) xml.Save(w);
        var r = Run("schtasks.exe", $"/Create /F /TN \"{VirtualDisplay.TaskName}\" /XML \"{tmp}\"", null);
        Log.Write("schtasks create -> " + r);
        try { File.Delete(tmp); } catch { }
    }

    private static int Run(string exe, string args, string? workDir)
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
            if (workDir != null) psi.WorkingDirectory = workDir;
            using var p = Process.Start(psi)!;
            var o = p.StandardOutput.ReadToEndAsync();
            var e = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(60000)) { try { p.Kill(); } catch { } return -1; }
            Log.Write($"{Path.GetFileName(exe)} {args}\n{o.Result}{e.Result}");
            return p.ExitCode;
        }
        catch (Exception ex)
        {
            Log.Write($"Run {exe}: {ex.Message}");
            return -1;
        }
    }
}
