using System.Text.Json;

namespace TabDisplay;

/// <summary>
/// タブレットを外したとき、タブレット側にあった窓を PC のメイン画面へ戻す。
/// そのとき並びを覚えておき、同じタブレットを次につないだら元の場所へ戻す。
/// </summary>
public static class WindowKeeper
{
    public class Saved
    {
        public string Process { get; set; } = "";
        public string Title { get; set; } = "";
        // タブレット画面の中の位置（0〜1 の割合。解像度が変わっても使える）
        public double X { get; set; }
        public double Y { get; set; }
        public double W { get; set; }
        public double H { get; set; }
        public bool Max { get; set; }
    }

    private static string FilePath => Path.Combine(AppSettings.Dir, "window-layouts.json");
    private static readonly object Lock = new();

    private static Dictionary<string, List<Saved>> Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Dictionary<string, List<Saved>>>(File.ReadAllText(FilePath)) ?? new();
        }
        catch { }
        return new();
    }

    private static void Store(Dictionary<string, List<Saved>> all)
    {
        try
        {
            Directory.CreateDirectory(AppSettings.Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(all));
        }
        catch (Exception e) { Log.Write("window layouts: " + e.Message); }
    }

    /// <summary>タブレットの画面にある窓を覚えて（remember のとき）、メイン画面へ移す（rescue のとき）</summary>
    public static void OnDisconnect(string tabletId, DisplayInfo d, bool remember, bool rescue)
    {
        if (d.Primary || (!remember && !rescue)) return;
        var area = new Rectangle(d.X, d.Y, d.Width, d.Height);
        if (area.Width <= 0 || area.Height <= 0) return;
        var saved = new List<Saved>();
        var main = Screen.PrimaryScreen;
        foreach (var w in Win.List())
        {
            var b = Win.Bounds(w.Handle);
            if (b == null) continue;
            var r = b.Value;
            var c = new Point(r.Left + r.Width / 2, r.Top + r.Height / 2);
            if (!area.Contains(c)) continue;
            saved.Add(new Saved
            {
                Process = w.Process,
                Title = w.Title,
                X = (double)(r.Left - area.Left) / area.Width,
                Y = (double)(r.Top - area.Top) / area.Height,
                W = (double)r.Width / area.Width,
                H = (double)r.Height / area.Height,
                Max = Win.IsZoomed(w.Handle),
            });
            if (rescue && main != null) Win.MoveTo(w.Handle, main);
        }
        if (remember && saved.Count > 0)
        {
            lock (Lock)
            {
                var all = Load();
                all[tabletId] = saved;
                Store(all);
            }
        }
        if (saved.Count > 0) Log.Write($"windows: {saved.Count} moved from {d.DeviceName}");
    }

    /// <summary>覚えていた窓を、つないだタブレットの画面に戻す</summary>
    public static void Restore(string tabletId, DisplayInfo d)
    {
        if (d.Primary) return;
        List<Saved>? list;
        lock (Lock)
        {
            var all = Load();
            if (!all.TryGetValue(tabletId, out list) || list.Count == 0) return;
        }
        var area = new Rectangle(d.X, d.Y, d.Width, d.Height);
        var target = Screen.AllScreens.FirstOrDefault(s => s.DeviceName == d.DeviceName);
        var windows = Win.List();
        var used = new HashSet<IntPtr>();
        int moved = 0;
        foreach (var s in list)
        {
            // 同じソフトで同じ題名の窓を優先し、なければ同じソフトの窓
            var w = windows.FirstOrDefault(x => !used.Contains(x.Handle) && x.Process == s.Process && x.Title == s.Title)
                    ?? windows.FirstOrDefault(x => !used.Contains(x.Handle) && x.Process == s.Process && list.Count(y => y.Process == s.Process) == 1);
            if (w == null) continue;
            used.Add(w.Handle);
            if (s.Max && target != null)
            {
                Win.MoveTo(w.Handle, target, true);
            }
            else
            {
                Win.Place(w.Handle, new Rectangle(
                    area.Left + (int)(s.X * area.Width), area.Top + (int)(s.Y * area.Height),
                    Math.Max(200, (int)(s.W * area.Width)), Math.Max(120, (int)(s.H * area.Height))));
            }
            moved++;
        }
        if (moved > 0) Log.Write($"windows: {moved} restored to {d.DeviceName}");
    }
}
