using Microsoft.Win32;

namespace TabDisplay;

internal static class Program
{
    private const string MutexName = "TabDisplay.SingleInstance";
    public const string ShowEventName = "TabDisplay.ShowWindow";
    public const string SendEventName = "TabDisplay.SendFiles";
    public static string SendQueueFile => Path.Combine(AppSettings.Dir, "send-queue.txt");

    [STAThread]
    private static int Main(string[] args)
    {
        // 管理者で起動し直されたときの作業
        if (args.Contains("--install-driver")) return DriverSetup.Install();
        if (args.Contains("--uninstall-driver")) return DriverSetup.Uninstall();
        if (args.Contains("--vdd-helper"))
        {
            VirtualDisplay.HelperMain();
            return 0;
        }

        // エクスプローラーの「送る」→「TabDisplay」：送るファイルを書き置きして、開いている TabDisplay に知らせる
        var sendIdx = Array.IndexOf(args, "--send");
        if (sendIdx >= 0)
        {
            try
            {
                Directory.CreateDirectory(AppSettings.Dir);
                File.AppendAllLines(SendQueueFile, args.Skip(sendIdx + 1).Where(a => !a.StartsWith("--")));
            }
            catch { }
        }

        using var mutex = new Mutex(true, MutexName, out var first);
        if (!first)
        {
            if (sendIdx >= 0)
            {
                try { EventWaitHandle.OpenExisting(SendEventName).Set(); } catch { }
                return 0;
            }
            // もう開いていれば、そちらの窓を出す
            try { EventWaitHandle.OpenExisting(ShowEventName).Set(); } catch { }
            return 0;
        }

        var settings = AppSettings.Load();
        L.Init(settings.Language);
        Log.Write("start " + string.Join(' ', args));

        // 前回「タブレットのみ」のまま落ちていたら、PC の画面を戻す。残った仮想ディスプレイも消す
        try
        {
            Displays.RestoreIfNeeded();
            if (Displays.FindVirtual() != null) Task.Run(VirtualDisplay.Disable);
        }
        catch (Exception e) { Log.Write("startup cleanup: " + e.Message); }

        ApplicationConfiguration.Initialize();
        var hidden = args.Contains("--autostart") && settings.StartInTray;
        Application.Run(new MainForm(settings, hidden));
        return 0;
    }
}

/// <summary>Windows の起動時に開く（HKCU\...\Run）</summary>
public static class Autostart
{
    private const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "TabDisplay";

    public static void Set(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(Key, true) ?? Registry.CurrentUser.CreateSubKey(Key);
            if (on) k.SetValue(Name, $"\"{Environment.ProcessPath}\" --autostart");
            else k.DeleteValue(Name, false);
        }
        catch (Exception e)
        {
            Log.Write("autostart: " + e.Message);
        }
    }
}
