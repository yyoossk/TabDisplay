using System.Text.Json;

namespace TabDisplay;

/// <summary>
/// PC ソフトの見た目（色・文字）をまとめたもの。
/// 差し替えるときは theme.json を、ソフトと同じ場所か %APPDATA%\TabDisplay に置く。
/// 例：{ "FontName": "Yu Gothic UI", "FontSize": 10, "Back": "#1E1E1E", "Fore": "#FFFFFF", "Dim": "#A0A0A0", "Accent": "#1E88E5" }
/// </summary>
public class Theme
{
    public string FontName { get; set; } = "";
    public float FontSize { get; set; } = 0;
    public string Back { get; set; } = "";
    public string Fore { get; set; } = "";
    public string Dim { get; set; } = "#696969";
    public string Accent { get; set; } = "#1E88E5";

    public static Theme Current { get; } = Load();

    public static Color DimColor => Parse(Current.Dim) ?? Color.DimGray;
    public static Color AccentColor => Parse(Current.Accent) ?? Color.SteelBlue;

    private static Theme Load()
    {
        foreach (var dir in new[] { AppSettings.Dir, AppContext.BaseDirectory })
        {
            var f = Path.Combine(dir, "theme.json");
            try
            {
                if (File.Exists(f)) return JsonSerializer.Deserialize<Theme>(File.ReadAllText(f)) ?? new Theme();
            }
            catch (Exception e) { Log.Write("theme: " + e.Message); }
        }
        return new Theme();
    }

    private static Color? Parse(string s)
    {
        if (string.IsNullOrWhiteSpace(s)) return null;
        try { return ColorTranslator.FromHtml(s); } catch { return null; }
    }

    /// <summary>窓と、その中の部品全部に、色と文字を当てる</summary>
    public static void Apply(Control root)
    {
        var t = Current;
        var back = Parse(t.Back);
        var fore = Parse(t.Fore);
        if (t.FontName.Length > 0 || t.FontSize > 0)
        {
            try
            {
                root.Font = new Font(t.FontName.Length > 0 ? t.FontName : root.Font.FontFamily.Name,
                    t.FontSize > 0 ? t.FontSize : root.Font.Size);
            }
            catch { }
        }
        void Walk(Control c)
        {
            if (back != null && c is not TextBox && c is not ComboBox) c.BackColor = back.Value;
            if (fore != null && c.ForeColor != Color.DimGray && c.ForeColor != DimColor) c.ForeColor = fore.Value;
            foreach (Control k in c.Controls) Walk(k);
        }
        Walk(root);
    }
}
