namespace TabDisplay;

/// <summary>
/// 自分で決められるショートカットキー。設定には「Ctrl+Alt+T」のような文字で保存する。
/// </summary>
public static class Hotkeys
{
    /// <summary>（動作の名前, 既定のキー）</summary>
    public static readonly (string action, string def)[] Actions =
    {
        ("move_window", "Ctrl+Alt+T"),   // 今のウィンドウを次の画面へ
        ("next_mode", "Ctrl+Alt+M"),     // 表示のしかたを順に切り替え
        ("pc_only", "Ctrl+Alt+B"),       // タブレットの画面を消す／戻す（PC のみ）
        ("connect_usb", "Ctrl+Alt+U"),   // USB でつなぐ
        ("disconnect", "Ctrl+Alt+D"),    // 切断
    };

    public static string Get(AppSettings s, string action) =>
        s.Hotkeys.TryGetValue(action, out var k) ? k : Actions.First(a => a.action == action).def;

    /// <summary>「Ctrl+Alt+T」→（修飾キー, キー）。空や読めないときは null</summary>
    public static (uint mods, Keys key)? Parse(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        uint mods = 0;
        Keys key = Keys.None;
        foreach (var part in text.Split('+').Select(p => p.Trim()))
        {
            switch (part.ToLowerInvariant())
            {
                case "ctrl": mods |= Win.MOD_CONTROL; break;
                case "alt": mods |= Win.MOD_ALT; break;
                case "shift": mods |= Win.MOD_SHIFT; break;
                case "win": mods |= 0x8; break;
                default:
                    if (Enum.TryParse<Keys>(part, true, out var k)) key = k;
                    break;
            }
        }
        if (key == Keys.None || mods == 0) return null;
        return (mods, key);
    }

    public static string Format(Keys keyData)
    {
        var key = keyData & Keys.KeyCode;
        if (key is Keys.ControlKey or Keys.ShiftKey or Keys.Menu or Keys.LWin or Keys.RWin or Keys.None) return "";
        var parts = new List<string>();
        if ((keyData & Keys.Control) != 0) parts.Add("Ctrl");
        if ((keyData & Keys.Alt) != 0) parts.Add("Alt");
        if ((keyData & Keys.Shift) != 0) parts.Add("Shift");
        if (parts.Count == 0) return "";
        parts.Add(key.ToString());
        return string.Join("+", parts);
    }
}

/// <summary>ショートカットキーを決める窓：欄を選んでキーを押す（Delete で消す）</summary>
public sealed class HotkeyForm : Form
{
    public HotkeyForm(AppSettings s)
    {
        Load += (_, _) => Theme.Apply(this); // 見た目（theme.json で差し替え）
        Text = L.T("hk_title");
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        StartPosition = FormStartPosition.CenterParent;
        var t = new TableLayoutPanel { ColumnCount = 2, AutoSize = true, Padding = new Padding(12) };
        Controls.Add(t);
        t.Controls.Add(new Label { Text = L.T("hk_help"), AutoSize = true, MaximumSize = new Size(460, 0) });
        t.SetColumnSpan(t.Controls[0], 2);
        foreach (var (action, _) in Hotkeys.Actions)
        {
            t.Controls.Add(new Label { Text = L.T("hk_" + action), AutoSize = true, Anchor = AnchorStyles.Left });
            var box = new TextBox { Width = 180, ReadOnly = true, Text = Hotkeys.Get(s, action) };
            box.KeyDown += (_, e) =>
            {
                e.SuppressKeyPress = true;
                if (e.KeyCode is Keys.Delete or Keys.Back) box.Text = "";
                else
                {
                    var f = Hotkeys.Format(e.KeyData);
                    if (f.Length > 0) box.Text = f;
                }
                s.Hotkeys[action] = box.Text;
                s.Save();
            };
            t.Controls.Add(box);
        }
        var ok = new Button { Text = "OK", DialogResult = DialogResult.OK };
        t.Controls.Add(ok);
        AcceptButton = ok;
    }
}
