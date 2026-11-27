using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace TabDisplay;

/// <summary>ウィンドウまわり：一覧・位置・ほかの画面へ移す</summary>
public static class Win
{
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hWnd, StringBuilder s, int max);
    [DllImport("user32.dll")] private static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool IsZoomed(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] private static extern int GetWindowLong(IntPtr hWnd, int index);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out RECT r, int size);
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out int v, int size);

    [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint mods, uint vk);
    [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr hWnd, int id);
    public const uint MOD_ALT = 1, MOD_CONTROL = 2, MOD_SHIFT = 4, MOD_NOREPEAT = 0x4000;
    public const int WM_HOTKEY = 0x0312;

    private const int SW_RESTORE = 9, SW_MAXIMIZE = 3;
    private const uint SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10;
    private const int GWL_EXSTYLE = -20, WS_EX_TOOLWINDOW = 0x80;
    private const int DWMWA_EXTENDED_FRAME_BOUNDS = 9, DWMWA_CLOAKED = 14;

    public record WindowItem(IntPtr Handle, string Title, string Process);

    /// <summary>タスクバーに出るようなふつうのウィンドウの一覧</summary>
    public static List<WindowItem> List()
    {
        var list = new List<WindowItem>();
        var me = Environment.ProcessId;
        EnumWindows((h, _) =>
        {
            if (!IsWindowVisible(h)) return true;
            if ((GetWindowLong(h, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) != 0) return true;
            if (DwmGetWindowAttribute(h, DWMWA_CLOAKED, out int cloaked, 4) == 0 && cloaked != 0) return true;
            int len = GetWindowTextLength(h);
            if (len == 0) return true;
            var sb = new StringBuilder(len + 1);
            GetWindowText(h, sb, sb.Capacity);
            GetWindowThreadProcessId(h, out var pid);
            if (pid == me) return true;
            string pname = "";
            try { pname = System.Diagnostics.Process.GetProcessById((int)pid).ProcessName; } catch { }
            list.Add(new WindowItem(h, sb.ToString(), pname));
            return true;
        }, IntPtr.Zero);
        return list;
    }

    public static string Title(IntPtr h)
    {
        int len = GetWindowTextLength(h);
        var sb = new StringBuilder(len + 1);
        GetWindowText(h, sb, sb.Capacity);
        return sb.ToString();
    }

    /// <summary>影を除いた、見えている部分の位置</summary>
    public static Rectangle? Bounds(IntPtr h)
    {
        if (!IsWindow(h) || IsIconic(h)) return null;
        if (DwmGetWindowAttribute(h, DWMWA_EXTENDED_FRAME_BOUNDS, out RECT r, Marshal.SizeOf<RECT>()) != 0)
            if (!GetWindowRect(h, out r)) return null;
        return Rectangle.FromLTRB(r.Left, r.Top, r.Right, r.Bottom);
    }

    /// <summary>ウィンドウを指定の画面へ移す（最大化していたら移した先でも最大化）</summary>
    public static void MoveTo(IntPtr h, Screen target, bool maximize = false)
    {
        if (!IsWindow(h)) return;
        bool wasMax = IsZoomed(h);
        if (wasMax || IsIconic(h)) ShowWindow(h, SW_RESTORE);
        if (!GetWindowRect(h, out var r)) return;
        var from = Screen.FromRectangle(Rectangle.FromLTRB(r.Left, r.Top, r.Right, r.Bottom)).WorkingArea;
        var to = target.WorkingArea;
        int w = Math.Min(r.Right - r.Left, to.Width);
        int hh = Math.Min(r.Bottom - r.Top, to.Height);
        // 元の画面の中の位置の割合を保つ
        double fx = from.Width > w ? (double)(r.Left - from.Left) / (from.Width - w) : 0;
        double fy = from.Height > hh ? (double)(r.Top - from.Top) / (from.Height - hh) : 0;
        int x = to.Left + (int)(Math.Clamp(fx, 0, 1) * Math.Max(0, to.Width - w));
        int y = to.Top + (int)(Math.Clamp(fy, 0, 1) * Math.Max(0, to.Height - hh));
        SetWindowPos(h, IntPtr.Zero, x, y, w, hh, SWP_NOZORDER | SWP_NOACTIVATE);
        if (wasMax || maximize) ShowWindow(h, SW_MAXIMIZE);
    }

    /// <summary>
    /// 今のウィンドウを「次の画面」へ移す（PC の画面 → タブレット 1 → タブレット 2 → PC の画面…）。
    /// </summary>
    public static void MoveForegroundToNext()
    {
        var h = GetForegroundWindow();
        if (h == IntPtr.Zero) return;
        var screens = Screen.AllScreens
            .OrderBy(s => Displays.IsVirtualName(s.DeviceName) ? 1 : 0)
            .ThenByDescending(s => s.Primary)
            .ThenBy(s => s.DeviceName)
            .ToList();
        if (screens.Count < 2) return;
        var cur = Screen.FromHandle(h);
        int i = screens.FindIndex(s => s.DeviceName == cur.DeviceName);
        var next = screens[(i + 1) % screens.Count];
        MoveTo(h, next);
    }

    /// <summary>ソフトを開いて、その窓を指定の画面に最大化して置く</summary>
    public static void LaunchOn(string commandLine, string deviceName)
    {
        try
        {
            var (exe, args) = SplitCommand(commandLine);
            if (exe.Length == 0) return;
            var p = Process.Start(new ProcessStartInfo(exe, args) { UseShellExecute = true });
            if (p == null) return;
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < 15000)
            {
                Thread.Sleep(300);
                try { p.Refresh(); } catch { return; }
                if (p.HasExited) return;
                if (p.MainWindowHandle != IntPtr.Zero)
                {
                    var target = Screen.AllScreens.FirstOrDefault(s => s.DeviceName == deviceName);
                    if (target != null) MoveTo(p.MainWindowHandle, target, maximize: true);
                    return;
                }
            }
        }
        catch (Exception e)
        {
            Log.Write($"launch {commandLine}: {e.Message}");
        }
    }

    /// <summary>「"C:\a b\x.exe" -y」→ (C:\a b\x.exe, -y)</summary>
    public static (string exe, string args) SplitCommand(string line)
    {
        line = line.Trim();
        if (line.StartsWith('"'))
        {
            int end = line.IndexOf('"', 1);
            if (end > 0) return (line[1..end], line[(end + 1)..].Trim());
        }
        // 空白を含むパスでも、ファイルがあるところまでを実行ファイルとみなす
        var parts = line.Split(' ');
        for (int n = parts.Length; n >= 1; n--)
        {
            var cand = string.Join(' ', parts.Take(n));
            if (File.Exists(cand)) return (cand, string.Join(' ', parts.Skip(n)));
        }
        return (parts[0], string.Join(' ', parts.Skip(1)));
    }
}
