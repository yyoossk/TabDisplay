namespace TabDisplay;

/// <summary>
/// はじめの設定の案内：① 仮想ディスプレイを入れる → ② タブレットの準備 → ③ つなぐ
/// 初めて開いたときに出る（窓の「はじめの設定」からいつでも開ける）。
/// </summary>
public sealed class SetupWizard : Form
{
    private readonly Label _step1 = new() { AutoSize = true, MaximumSize = new Size(520, 0) };
    private readonly Label _step2 = new() { AutoSize = true, MaximumSize = new Size(520, 0) };

    public SetupWizard(Action installDriver, Action installApp, Action connectUsb, Action showQr)
    {
        Text = L.T("wiz_title");
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        StartPosition = FormStartPosition.CenterParent;

        var col = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Padding = new Padding(16), WrapContents = false };
        Controls.Add(col);

        Label H(string key) => new() { Text = L.T(key), AutoSize = true, Font = new Font(Font.FontFamily, 12, FontStyle.Bold), Margin = new Padding(3, 12, 3, 3) };
        Label P(string key) => new() { Text = L.T(key), AutoSize = true, MaximumSize = new Size(520, 0) };
        Button B(string key, Action a)
        {
            var b = new Button { Text = L.T(key), AutoSize = true };
            b.Click += (_, _) => { a(); Refresh2(); };
            return b;
        }

        col.Controls.Add(H("wiz_1"));
        col.Controls.Add(P("wiz_1_body"));
        col.Controls.Add(_step1);
        col.Controls.Add(B("btn_install_driver", installDriver));

        col.Controls.Add(H("wiz_2"));
        col.Controls.Add(P("wiz_2_body"));
        col.Controls.Add(_step2);
        col.Controls.Add(B("btn_install_app", installApp));

        col.Controls.Add(H("wiz_3"));
        col.Controls.Add(P("wiz_3_body"));
        var row = new FlowLayoutPanel { AutoSize = true };
        row.Controls.Add(B("btn_connect_usb", connectUsb));
        row.Controls.Add(B("btn_wifi", showQr));
        col.Controls.Add(row);

        var close = new Button { Text = L.T("wiz_close"), AutoSize = true, Margin = new Padding(3, 16, 3, 3) };
        close.Click += (_, _) => Close();
        col.Controls.Add(close);

        var t = new System.Windows.Forms.Timer { Interval = 2000 };
        t.Tick += (_, _) => Refresh2();
        t.Start();
        FormClosed += (_, _) => t.Dispose();
        Refresh2();
    }

    private void Refresh2()
    {
        _step1.Text = VirtualDisplay.IsInstalled ? "✓ " + L.T("driver_yes") : "✗ " + L.T("driver_none");
        Task.Run(() =>
        {
            var devs = Adb.Available ? Adb.Devices() : new List<Adb.Device>();
            var text = devs.Count == 0 ? "・" + L.T("dg_adb_none")
                : string.Join("\n", devs.Select(d => d.State == "device" ? "✓ " + string.Format(L.T("dg_adb_ok"), d.Model)
                    : d.State == "unauthorized" ? "✗ " + L.T("usb_unauthorized") : "✗ " + d.State));
            try { BeginInvoke(() => _step2.Text = text); } catch { }
        });
    }
}
