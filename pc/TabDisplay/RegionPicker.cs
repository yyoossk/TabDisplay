namespace TabDisplay;

/// <summary>「一部だけ映す」で映すウィンドウ・範囲を選ぶ窓</summary>
public sealed class RegionPicker : Form
{
    private readonly AppSettings _s;
    private readonly ListBox _list = new() { Width = 460, Height = 260 };
    private List<Win.WindowItem> _windows = new();

    public RegionPicker(AppSettings s)
    {
        Load += (_, _) => Theme.Apply(this); // 見た目（theme.json で差し替え）
        _s = s;
        Text = L.T("region_title");
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        StartPosition = FormStartPosition.CenterParent;

        var col = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Padding = new Padding(12), WrapContents = false };
        Controls.Add(col);
        col.Controls.Add(new Label { Text = L.T("region_window"), AutoSize = true });
        col.Controls.Add(_list);
        _windows = Win.List();
        foreach (var w in _windows) _list.Items.Add($"{w.Title}  （{w.Process}）");
        int sel = _windows.FindIndex(w => w.Title == s.RegionWindowTitle);
        if (sel >= 0) _list.SelectedIndex = sel;

        var row = new FlowLayoutPanel { AutoSize = true };
        var ok = new Button { Text = L.T("region_use_window"), AutoSize = true };
        ok.Click += (_, _) =>
        {
            if (_list.SelectedIndex < 0) return;
            _s.RegionWindowTitle = _windows[_list.SelectedIndex].Title;
            _s.Save();
            DialogResult = DialogResult.OK;
        };
        var rect = new Button { Text = L.T("region_drag"), AutoSize = true };
        rect.Click += (_, _) =>
        {
            Hide();
            using var f = new RectSelectForm();
            if (f.ShowDialog() == DialogResult.OK && f.Selected.Width >= 64 && f.Selected.Height >= 64)
            {
                _s.RegionWindowTitle = "";
                _s.RegionRect = new[] { f.Selected.X, f.Selected.Y, f.Selected.Width, f.Selected.Height };
                _s.Save();
                DialogResult = DialogResult.OK;
            }
            else
            {
                Show();
            }
        };
        row.Controls.Add(ok);
        row.Controls.Add(rect);
        col.Controls.Add(row);
    }
}

/// <summary>画面全体を薄く覆って、ドラッグで範囲を選ぶ</summary>
internal sealed class RectSelectForm : Form
{
    public Rectangle Selected { get; private set; }
    private Point _start;
    private bool _drag;
    private Rectangle _cur;

    public RectSelectForm()
    {
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        Bounds = SystemInformation.VirtualScreen;
        TopMost = true;
        ShowInTaskbar = false;
        BackColor = Color.Black;
        Opacity = 0.35;
        Cursor = Cursors.Cross;
        DoubleBuffered = true;
        KeyPreview = true;
        KeyDown += (_, e) => { if (e.KeyCode == Keys.Escape) DialogResult = DialogResult.Cancel; };
        MouseDown += (_, e) => { _start = e.Location; _drag = true; };
        MouseMove += (_, e) =>
        {
            if (!_drag) return;
            _cur = Rectangle.FromLTRB(Math.Min(_start.X, e.X), Math.Min(_start.Y, e.Y), Math.Max(_start.X, e.X), Math.Max(_start.Y, e.Y));
            Invalidate();
        };
        MouseUp += (_, _) =>
        {
            _drag = false;
            // フォームの中の座標 → デスクトップの座標
            Selected = new Rectangle(_cur.X + Left, _cur.Y + Top, _cur.Width, _cur.Height);
            DialogResult = DialogResult.OK;
        };
        Paint += (_, e) =>
        {
            using var pen = new Pen(Color.Yellow, 3);
            if (_cur.Width > 0) e.Graphics.DrawRectangle(pen, _cur);
            using var font = new Font(Font.FontFamily, 18);
            e.Graphics.DrawString(L.T("region_drag_help"), font, Brushes.White, 20, 20);
        };
    }
}
