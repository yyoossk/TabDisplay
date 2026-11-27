using System.Runtime.InteropServices;
using static TabDisplay.Native;

namespace TabDisplay;

/// <summary>
/// 画面ごとの拡大率（設定 → ディスプレイ → 拡大/縮小）を変える。
/// Windows の公開されていない DisplayConfig の種類（-3 / -4）を使う（「設定」アプリと同じ方法）。
/// </summary>
public static class DpiScale
{
    private static readonly int[] Steps = { 100, 125, 150, 175, 200, 225, 250, 300, 350, 400, 450, 500 };
    private const int PathSize = 72;
    private const int ModeSize = 64;

    /// <summary>
    /// タブレットの dpi から、ちょうどよい拡大率を決める。
    /// Android の 160dpi が Windows の 100% と同じくらいの見た目になる。
    /// </summary>
    public static int PercentForTablet(int tabletDpi)
    {
        var want = tabletDpi * 100 / 160;
        return Steps.OrderBy(s => Math.Abs(s - want)).First();
    }

    public static bool Set(string gdiDeviceName, int percent)
    {
        try
        {
            if (!Find(gdiDeviceName, out var low, out var high, out var sourceId)) return false;

            // 今の値と「おすすめ」の位置を取る（値はおすすめからの段数）
            var get = Marshal.AllocHGlobal(32);
            int min, cur, max;
            try
            {
                WriteHeader(get, -3, 32, low, high, sourceId);
                if (DisplayConfigGetDeviceInfo(get) != 0) return false;
                min = Marshal.ReadInt32(get, 20);
                cur = Marshal.ReadInt32(get, 24);
                max = Marshal.ReadInt32(get, 28);
            }
            finally { Marshal.FreeHGlobal(get); }

            int recommendedIdx = Math.Abs(min);
            int targetIdx = Array.IndexOf(Steps, percent);
            if (targetIdx < 0) return false;
            int rel = Math.Clamp(targetIdx - recommendedIdx, min, max);
            if (rel == cur) return true;

            var set = Marshal.AllocHGlobal(24);
            try
            {
                WriteHeader(set, -4, 24, low, high, sourceId);
                Marshal.WriteInt32(set, 20, rel);
                var r = DisplayConfigSetDeviceInfo(set);
                Log.Write($"DpiScale {gdiDeviceName} {percent}% (rel {rel}) -> {r}");
                return r == 0;
            }
            finally { Marshal.FreeHGlobal(set); }
        }
        catch (Exception e)
        {
            Log.Write("DpiScale: " + e.Message);
            return false;
        }
    }

    private static void WriteHeader(IntPtr p, int type, int size, uint low, int high, uint id)
    {
        Marshal.WriteInt32(p, 0, type);
        Marshal.WriteInt32(p, 4, size);
        Marshal.WriteInt32(p, 8, (int)low);
        Marshal.WriteInt32(p, 12, high);
        Marshal.WriteInt32(p, 16, (int)id);
    }

    /// <summary>\\.\DISPLAY3 などの名前から、DisplayConfig のアダプター ID とソース ID を探す</summary>
    private static bool Find(string gdiName, out uint low, out int high, out uint sourceId)
    {
        low = 0; high = 0; sourceId = 0;
        if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, out var np, out var nm) != 0) return false;
        var paths = new byte[np * PathSize];
        var modes = new byte[nm * ModeSize];
        if (QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, ref np, paths, ref nm, modes, IntPtr.Zero) != 0) return false;
        var buf = Marshal.AllocHGlobal(84);
        try
        {
            for (int i = 0; i < np; i++)
            {
                int o = i * PathSize;
                uint l = BitConverter.ToUInt32(paths, o);
                int h = BitConverter.ToInt32(paths, o + 4);
                uint id = BitConverter.ToUInt32(paths, o + 8);
                for (int k = 0; k < 84; k++) Marshal.WriteByte(buf, k, 0);
                WriteHeader(buf, 1, 84, l, h, id); // DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME
                if (DisplayConfigGetDeviceInfo(buf) != 0) continue;
                var name = Marshal.PtrToStringUni(buf + 20) ?? "";
                if (string.Equals(name, gdiName, StringComparison.OrdinalIgnoreCase))
                {
                    low = l; high = h; sourceId = id;
                    return true;
                }
            }
        }
        finally { Marshal.FreeHGlobal(buf); }
        return false;
    }
}
