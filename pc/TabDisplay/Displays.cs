using System.Text.Json;
using static TabDisplay.Native;

namespace TabDisplay;

/// <summary>画面 1 つ分の情報</summary>
public record DisplayInfo(
    string DeviceName, string Description, string DeviceId, bool Primary,
    int X, int Y, int Width, int Height, int Frequency, int Bits);

/// <summary>
/// 画面の一覧・解像度の変更・「タブレットのみ」のときの PC 画面のオフ／元に戻す。
/// </summary>
public static class Displays
{
    public static List<DisplayInfo> Attached()
    {
        var list = new List<DisplayInfo>();
        for (uint i = 0; i < 64; i++)
        {
            var dd = new DISPLAY_DEVICE { cb = System.Runtime.InteropServices.Marshal.SizeOf<DISPLAY_DEVICE>() };
            if (!EnumDisplayDevices(null, i, ref dd, 0)) break;
            if ((dd.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP) == 0) continue;
            if ((dd.StateFlags & DISPLAY_DEVICE_MIRRORING_DRIVER) != 0) continue;
            var dm = DEVMODE.Create();
            if (!EnumDisplaySettingsEx(dd.DeviceName, ENUM_CURRENT_SETTINGS, ref dm, 0)) continue;
            list.Add(new DisplayInfo(dd.DeviceName, dd.DeviceString, dd.DeviceID,
                (dd.StateFlags & DISPLAY_DEVICE_PRIMARY_DEVICE) != 0,
                dm.dmPositionX, dm.dmPositionY, (int)dm.dmPelsWidth, (int)dm.dmPelsHeight,
                (int)dm.dmDisplayFrequency, (int)dm.dmBitsPerPel));
        }
        return list;
    }

    /// <summary>オフになっているものも含めた全部の画面の名前</summary>
    public static List<(string name, string desc, string id, bool attached)> All()
    {
        var list = new List<(string, string, string, bool)>();
        for (uint i = 0; i < 64; i++)
        {
            var dd = new DISPLAY_DEVICE { cb = System.Runtime.InteropServices.Marshal.SizeOf<DISPLAY_DEVICE>() };
            if (!EnumDisplayDevices(null, i, ref dd, 0)) break;
            if ((dd.StateFlags & DISPLAY_DEVICE_MIRRORING_DRIVER) != 0) continue;
            list.Add((dd.DeviceName, dd.DeviceString, dd.DeviceID, (dd.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP) != 0));
        }
        return list;
    }

    public static bool IsVirtual(string desc, string id) =>
        desc.Contains("Virtual Display Driver", StringComparison.OrdinalIgnoreCase) ||
        id.Contains("MttVDD", StringComparison.OrdinalIgnoreCase);

    public static DisplayInfo? FindVirtual() => FindVirtuals().FirstOrDefault();

    public static List<DisplayInfo> FindVirtuals() =>
        Attached().Where(d => IsVirtual(d.Description, d.DeviceId))
                  .OrderBy(d => d.DeviceName, StringComparer.OrdinalIgnoreCase).ToList();

    public static DisplayInfo? Primary() => Attached().FirstOrDefault(d => d.Primary) ?? Attached().FirstOrDefault();

    /// <summary>解像度を変える（仮想ディスプレイ用）</summary>
    public static bool SetMode(string deviceName, int w, int h, int hz)
    {
        var dm = DEVMODE.Create();
        EnumDisplaySettingsEx(deviceName, ENUM_CURRENT_SETTINGS, ref dm, 0);
        if (dm.dmPelsWidth == w && dm.dmPelsHeight == h) return true;
        dm.dmPelsWidth = (uint)w;
        dm.dmPelsHeight = (uint)h;
        dm.dmDisplayFrequency = (uint)hz;
        dm.dmDisplayOrientation = 0;
        dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY | DM_DISPLAYORIENTATION;
        var r = ChangeDisplaySettingsEx(deviceName, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY, IntPtr.Zero);
        if (r != DISP_CHANGE_SUCCESSFUL)
        {
            // リフレッシュレートが合わないときは、それを外してもう一度
            dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYORIENTATION;
            r = ChangeDisplaySettingsEx(deviceName, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY, IntPtr.Zero);
        }
        Log.Write($"SetMode {deviceName} {w}x{h}@{hz} -> {r}");
        return r == DISP_CHANGE_SUCCESSFUL;
    }

    /// <summary>
    /// 仮想ディスプレイ（タブレット）を、PC の画面の右・左・上・下に並べる。2 台以上は外側へ順に並べる。
    /// </summary>
    public static void PlaceVirtuals(IList<string> names, TabletSide side)
    {
        var all = Attached();
        var phys = all.Where(d => !IsVirtual(d.Description, d.DeviceId)).ToList();
        if (phys.Count == 0 || names.Count == 0) return;
        var primary = phys.FirstOrDefault(d => d.Primary) ?? phys[0];
        int left = phys.Min(d => d.X), top = phys.Min(d => d.Y);
        int right = phys.Max(d => d.X + d.Width), bottom = phys.Max(d => d.Y + d.Height);
        int cx = side == TabletSide.Left ? left : right;
        int cy = side == TabletSide.Top ? top : bottom;
        foreach (var name in names)
        {
            var dm = DEVMODE.Create();
            if (!EnumDisplaySettingsEx(name, ENUM_CURRENT_SETTINGS, ref dm, 0)) continue;
            int w = (int)dm.dmPelsWidth, h = (int)dm.dmPelsHeight;
            switch (side)
            {
                case TabletSide.Right: dm.dmPositionX = cx; dm.dmPositionY = primary.Y; cx += w; break;
                case TabletSide.Left: cx -= w; dm.dmPositionX = cx; dm.dmPositionY = primary.Y; break;
                case TabletSide.Top: cy -= h; dm.dmPositionX = primary.X; dm.dmPositionY = cy; break;
                default: dm.dmPositionX = primary.X; dm.dmPositionY = cy; cy += h; break;
            }
            dm.dmFields = DM_POSITION;
            ChangeDisplaySettingsEx(name, ref dm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
        }
        var r = ChangeDisplaySettingsExApply(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
        Log.Write($"PlaceVirtuals {side}: {r}");
    }

    // ---------- タブレットのみ ----------

    private static string RestoreFile => Path.Combine(AppSettings.Dir, "restore-displays.json");

    /// <summary>
    /// 仮想ディスプレイをメインにして、ほかの画面をオフにする。
    /// 元に戻すための情報はファイルにも残す（ソフトが落ちても次の起動で戻せるように）。
    /// </summary>
    public static bool MakeOnlyDisplay(IList<string> keep)
    {
        var virtualName = keep[0];
        var all = Attached();
        var others = all.Where(d => !keep.Contains(d.DeviceName, StringComparer.OrdinalIgnoreCase)).ToList();
        if (others.Count == 0) return true;
        try
        {
            Directory.CreateDirectory(AppSettings.Dir);
            File.WriteAllText(RestoreFile, JsonSerializer.Serialize(others));
        }
        catch (Exception e) { Log.Write("restore file: " + e.Message); }

        // 仮想ディスプレイを (0,0) のメインにする
        var dm = DEVMODE.Create();
        EnumDisplaySettingsEx(virtualName, ENUM_CURRENT_SETTINGS, ref dm, 0);
        dm.dmPositionX = 0;
        dm.dmPositionY = 0;
        dm.dmFields = DM_POSITION;
        var r1 = ChangeDisplaySettingsEx(virtualName, ref dm, IntPtr.Zero, CDS_SET_PRIMARY | CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
        // 2 台目以降のタブレットは右に並べる
        int x = (int)dm.dmPelsWidth;
        foreach (var name in keep.Skip(1))
        {
            var dm2 = DEVMODE.Create();
            EnumDisplaySettingsEx(name, ENUM_CURRENT_SETTINGS, ref dm2, 0);
            dm2.dmPositionX = x;
            dm2.dmPositionY = 0;
            dm2.dmFields = DM_POSITION;
            ChangeDisplaySettingsEx(name, ref dm2, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
            x += (int)dm2.dmPelsWidth;
        }

        // ほかの画面は大きさ 0 にしてオフ
        foreach (var o in others)
        {
            var off = DEVMODE.Create();
            off.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT;
            ChangeDisplaySettingsEx(o.DeviceName, ref off, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
        }
        var r2 = ChangeDisplaySettingsExApply(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
        Log.Write($"MakeOnlyDisplay {virtualName}: {r1} {r2}");
        return r2 == DISP_CHANGE_SUCCESSFUL;
    }

    public static bool HasPendingRestore => File.Exists(RestoreFile);

    /// <summary>「タブレットのみ」でオフにした画面を元に戻す</summary>
    public static void RestoreIfNeeded()
    {
        if (!File.Exists(RestoreFile)) return;
        try
        {
            var saved = JsonSerializer.Deserialize<List<DisplayInfo>>(File.ReadAllText(RestoreFile)) ?? new();
            // 元のメイン画面から先に戻す
            foreach (var d in saved.OrderByDescending(d => d.Primary))
            {
                var dm = DEVMODE.Create();
                dm.dmPelsWidth = (uint)d.Width;
                dm.dmPelsHeight = (uint)d.Height;
                dm.dmPositionX = d.X;
                dm.dmPositionY = d.Y;
                dm.dmDisplayFrequency = (uint)d.Frequency;
                dm.dmBitsPerPel = (uint)d.Bits;
                dm.dmFields = DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY | DM_BITSPERPEL;
                var flags = CDS_UPDATEREGISTRY | CDS_NORESET | (d.Primary ? CDS_SET_PRIMARY : 0);
                ChangeDisplaySettingsEx(d.DeviceName, ref dm, IntPtr.Zero, flags, IntPtr.Zero);
            }
            // 仮想ディスプレイがまだあれば、重ならないよう右端へよける
            if (saved.Count > 0)
            {
                int vx = saved.Max(s => s.X + s.Width);
                foreach (var v in FindVirtuals())
                {
                    var vm = DEVMODE.Create();
                    EnumDisplaySettingsEx(v.DeviceName, ENUM_CURRENT_SETTINGS, ref vm, 0);
                    vm.dmPositionX = vx;
                    vm.dmPositionY = saved.Min(s => s.Y);
                    vm.dmFields = DM_POSITION;
                    ChangeDisplaySettingsEx(v.DeviceName, ref vm, IntPtr.Zero, CDS_UPDATEREGISTRY | CDS_NORESET, IntPtr.Zero);
                    vx += (int)vm.dmPelsWidth;
                }
            }
            var r =ChangeDisplaySettingsExApply(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
            Log.Write("Restore displays: " + r);
            // うまく戻らなかったときの保険：Windows の「拡張」に切り替えて全部の画面をオンにする
            Thread.Sleep(800);
            var back = Attached();
            if (saved.Any(s => !back.Any(b => string.Equals(b.DeviceName, s.DeviceName, StringComparison.OrdinalIgnoreCase))))
            {
                var r2 = SetDisplayConfig(0, IntPtr.Zero, 0, IntPtr.Zero, SDC_APPLY | SDC_TOPOLOGY_EXTEND);
                Log.Write("Restore fallback SetDisplayConfig extend: " + r2);
            }
        }
        catch (Exception e)
        {
            Log.Write("restore: " + e);
            SetDisplayConfig(0, IntPtr.Zero, 0, IntPtr.Zero, SDC_APPLY | SDC_TOPOLOGY_EXTEND);
        }
        finally
        {
            try { File.Delete(RestoreFile); } catch { }
        }
    }
}
