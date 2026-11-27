using System.Diagnostics;

namespace TabDisplay;

/// <summary>
/// 設定と操作の窓。見た目はあとで差し替える前提で、部品だけ並べている。
/// </summary>
public sealed class MainForm : Form
{
    private readonly AppSettings _s;
    private readonly Server _server;
    private readonly NotifyIcon _tray;
    private readonly Label _status = new() { AutoSize = true, MaximumSize = new Size(520, 0) };
    private readonly Label _stats = new() { AutoSize = true, ForeColor = Theme.DimColor, MaximumSize = new Size(520, 0) };
    private readonly Label _driver = new() { AutoSize = true };
    private readonly Button _btnUsb = new();
    private readonly Button _btnWifi = new();
    private readonly Button _btnDisconnect = new();
    private readonly System.Windows.Forms.Timer _usbTimer = new() { Interval = 3000 };
    private readonly EventWaitHandle _showEvent;
    private bool _exiting;
    private bool _startHidden;
    private volatile bool _usbBusy;
    private readonly HashSet<string> _autoSerials = new();
    private ComboBox _modeBox = null!;
    private ComboBox _sideBox = null!;
    private CheckBox _rotateBox = null!;
    private CheckBox _audioBox = null!;
    private ComboBox _fpsBox = null!, _brBox = null!, _qualityBox = null!, _profileBox = null!;
    private CheckBox _dpiBox = null!, _adaptiveBox = null!, _skipBox = null!;
    private TextBox _appsBox = null!;
    private const int HotkeyId = 1;
    private const int WM_CLIPBOARDUPDATE = 0x031D;
    private string? _clipLast;
    private EventWaitHandle? _sendEvent;
    private Updater.Release? _pendingUpdate;
    private NotificationWatcher? _notify;

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool AddClipboardFormatListener(IntPtr hwnd);

    public MainForm(AppSettings settings, bool startHidden)
    {
        Load += (_, _) => Theme.Apply(this); // 見た目（theme.json で差し替え）
        _s = settings;
        _startHidden = startHidden;
        Text = L.T("title");
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        StartPosition = FormStartPosition.CenterScreen;
        Icon = SystemIcons.Application;

        BuildUi();

        _tray = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "TabDisplay",
            Visible = true,
            ContextMenuStrip = new ContextMenuStrip(),
        };
        BuildTrayMenu(_tray.ContextMenuStrip!);
        _tray.DoubleClick += (_, _) => ShowFromTray();

        _server = new Server(_s);
        _server.Status += s => Ui(() => { _status.Text = s; });
        _server.Stats += text => Ui(() => _stats.Text = text);
        _server.ConnectedChanged += c => Ui(() =>
        {
            _btnDisconnect.Enabled = c;
            if (!c) _stats.Text = "";
        });
        if (!_server.Start()) _status.Text = string.Format(L.T("port_busy"), _s.Port);
        else _status.Text = L.T("st_idle");

        // クリップボード：タブレットでコピーした文字を PC に
        _server.ClipReceived += text => Ui(() =>
        {
            _clipLast = text;
            try { Clipboard.SetText(text); } catch { }
        });
        // タブレットからファイルが届いた
        _server.FileReceived += path => Ui(() =>
        {
            _tray.ShowBalloonTip(3000, "TabDisplay", string.Format(L.T("file_received"), Path.GetFileName(path)), ToolTipIcon.Info);
            _status.Text = string.Format(L.T("file_received"), path);
        });
        _tray.BalloonTipClicked += (_, _) =>
        {
            try { Process.Start(new ProcessStartInfo(FileTransfer.ReceiveDir) { UseShellExecute = true }); } catch { }
        };
        // この窓にファイルをドロップ → タブレットへ
        AllowDrop = true;
        DragEnter += (_, e) => { if (e.Data?.GetDataPresent(DataFormats.FileDrop) == true) e.Effect = DragDropEffects.Copy; };
        DragDrop += (_, e) =>
        {
            if (e.Data?.GetData(DataFormats.FileDrop) is string[] files) SendFiles(files);
        };
        // 「送る」→「TabDisplay」から
        _sendEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.SendEventName);
        new Thread(() =>
        {
            while (true)
            {
                _sendEvent.WaitOne();
                Ui(SendQueued);
            }
        }) { IsBackground = true }.Start();
        // PC のロック解除・スリープ明け：撮り直し、USB のタブレットにもつなぎ直す
        Microsoft.Win32.SystemEvents.SessionSwitch += (_, e) =>
        {
            if (e.Reason == Microsoft.Win32.SessionSwitchReason.SessionUnlock)
                Task.Delay(1500).ContinueWith(_ => _server.ApplySettings());
        };
        Microsoft.Win32.SystemEvents.PowerModeChanged += (_, e) =>
        {
            if (e.Mode != Microsoft.Win32.PowerModes.Resume) return;
            Task.Delay(4000).ContinueWith(_ =>
            {
                _server.ApplySettings();
                lock (_autoSerials) _autoSerials.Clear();
                Ui(() => ConnectUsb(true));
            });
        };
        // PC の通知をタブレットへ
        _notify = new NotificationWatcher(() => (_s.ForwardNotifications || _server.AnyWantsNotify) && _server.Connected,
            (app, title, body) => _server.BroadcastNotify(app, title, body));
        Shown += (_, _) => AfterShown();
        if (_startHidden) Task.Delay(3000).ContinueWith(_ => Ui(AfterShown));

        _usbTimer.Tick += (_, _) => AutoUsbTick();
        _usbTimer.Start();

        // 2 つ目の起動から「窓を出して」と頼まれたら出す
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.ShowEventName);
        new Thread(() =>
        {
            while (true)
            {
                _showEvent.WaitOne();
                Ui(ShowFromTray);
            }
        }) { IsBackground = true }.Start();

        RefreshDriverLabel();
    }

    protected override void SetVisibleCore(bool value)
    {
        // 起動時にしまうときは、一度も窓を出さない
        if (_startHidden && !IsHandleCreated)
        {
            CreateHandle();
            value = false;
        }
        base.SetVisibleCore(value);
    }

    private void BuildUi()
    {
        // 上：状態とつなぐボタン（いつも見える）。下：タブ（映す・入力と音・つなぐ・そのほか）
        TableLayoutPanel NewPanel() => new()
        {
            ColumnCount = 2,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Padding = new Padding(8),
        };
        var outer = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, WrapContents = false, Padding = new Padding(4) };
        Controls.Add(outer);
        var top = NewPanel();
        outer.Controls.Add(top);
        var tabs = new TabControl { Width = 620, Height = 520 };
        outer.Controls.Add(tabs);
        TableLayoutPanel Tab(string key)
        {
            var page = new TabPage(L.T(key)) { AutoScroll = true };
            var panel = NewPanel();
            page.Controls.Add(panel);
            tabs.TabPages.Add(page);
            return panel;
        }
        var tShow = Tab("tab_show");
        var tInput = Tab("tab_input");
        var tConnect = Tab("tab_connect");
        var tOther = Tab("tab_other");
        var p = top;

        void Row(Control a, Control? b = null)
        {
            if (b == null)
            {
                p.Controls.Add(a);
                p.SetColumnSpan(a, 2);
            }
            else
            {
                p.Controls.Add(a);
                p.Controls.Add(b);
            }
        }

        Row(_status);
        Row(_stats);

        var buttons = new FlowLayoutPanel { AutoSize = true, WrapContents = false };
        _btnUsb.Text = L.T("btn_connect_usb");
        _btnUsb.AutoSize = true;
        _btnUsb.Click += (_, _) => ConnectUsb(false);
        _btnWifi.Text = L.T("btn_wifi");
        _btnWifi.AutoSize = true;
        _btnWifi.Click += (_, _) => new QrForm(_s).ShowDialog(this);
        _btnDisconnect.Text = L.T("btn_disconnect");
        _btnDisconnect.AutoSize = true;
        _btnDisconnect.Enabled = false;
        _btnDisconnect.Click += (_, _) => _server.Disconnect();
        buttons.Controls.AddRange(new Control[] { _btnUsb, _btnWifi, _btnDisconnect });
        Row(buttons);

        p = tShow;
        // 表示のしかた
        var mode = _modeBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 260 };
        mode.Items.AddRange(new object[] { L.T("mode_extend"), L.T("mode_duplicate"), L.T("mode_tablet"), L.T("mode_pc"), L.T("mode_region"), L.T("mode_span") });
        mode.SelectedIndex = (int)_s.Mode;
        mode.SelectedIndexChanged += (_, _) =>
        {
            _s.Mode = (ShowMode)mode.SelectedIndex;
            _s.Save();
            _server.ApplySettings();
        };
        Row(Lbl("lbl_mode"), mode);

        // 一部だけ映す：ウィンドウか範囲を選ぶ
        var pickRegion = new Button { Text = L.T("btn_region"), AutoSize = true };
        pickRegion.Click += (_, _) =>
        {
            using var f = new RegionPicker(_s);
            if (f.ShowDialog(this) == DialogResult.OK)
            {
                if (_modeBox.SelectedIndex != (int)ShowMode.Region) _modeBox.SelectedIndex = (int)ShowMode.Region;
                else _server.ApplySettings();
            }
        };
        Row(new Label(), pickRegion);

        // 画質の方針
        var quality = _qualityBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 260 };
        quality.Items.AddRange(new object[] { L.T("q_auto"), L.T("q_text"), L.T("q_motion") });
        quality.SelectedIndex = (int)_s.Quality;
        quality.SelectedIndexChanged += (_, _) =>
        {
            _s.Quality = (QualityMode)quality.SelectedIndex;
            _s.Save();
            _server.ApplySettings();
        };
        Row(Lbl("lbl_quality"), quality);

        var side = _sideBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 260 };
        side.Items.AddRange(new object[] { L.T("side_right"), L.T("side_left"), L.T("side_top"), L.T("side_bottom") });
        side.SelectedIndex = (int)_s.Side;
        side.SelectedIndexChanged += (_, _) =>
        {
            _s.Side = (TabletSide)side.SelectedIndex;
            _s.Save();
            _server.ApplySettings();
        };
        Row(Lbl("lbl_side"), side);

        var rotate = _rotateBox = new CheckBox { Text = L.T("chk_rotate"), AutoSize = true, Checked = _s.FollowRotation };
        rotate.CheckedChanged += (_, _) =>
        {
            _s.FollowRotation = rotate.Checked;
            _s.Save();
            _server.ApplySettings();
        };
        Row(rotate);

        var fps = _fpsBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 100 };
        fps.Items.AddRange(new object[] { "30", "60" });
        fps.SelectedItem = _s.Fps.ToString();
        if (fps.SelectedIndex < 0) fps.SelectedIndex = 1;
        fps.SelectedIndexChanged += (_, _) =>
        {
            _s.Fps = int.Parse((string)fps.SelectedItem!);
            _s.Save();
            _server.ApplySettings();
        };
        Row(Lbl("lbl_fps"), fps);

        var br = _brBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 100 };
        br.Items.AddRange(new object[] { "4", "8", "12", "20", "30", "50" });
        br.SelectedItem = _s.BitrateMbps.ToString();
        if (br.SelectedIndex < 0) br.SelectedIndex = 2;
        br.SelectedIndexChanged += (_, _) =>
        {
            _s.BitrateMbps = int.Parse((string)br.SelectedItem!);
            _s.Save();
            _server.ApplySettings();
        };
        Row(Lbl("lbl_bitrate"), br);

        p = tOther;
        var lang = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 160 };
        lang.Items.AddRange(new object[] { L.T("lang_system"), "日本語", "English" });
        lang.SelectedIndex = _s.Language switch { "ja" => 1, "en" => 2, _ => 0 };
        lang.SelectedIndexChanged += (_, _) =>
        {
            _s.Language = lang.SelectedIndex switch { 1 => "ja", 2 => "en", _ => "" };
            _s.Save();
            MessageBox.Show(this, L.T("lang_restart"), Text);
        };
        Row(Lbl("lbl_lang"), lang);

        var startup = new CheckBox { Text = L.T("chk_startup"), AutoSize = true, Checked = _s.StartWithWindows };
        var tray = new CheckBox { Text = L.T("chk_tray"), AutoSize = true, Checked = _s.StartInTray, Enabled = _s.StartWithWindows };
        startup.CheckedChanged += (_, _) =>
        {
            _s.StartWithWindows = startup.Checked;
            _s.Save();
            Autostart.Set(startup.Checked);
            tray.Enabled = startup.Checked;
        };
        tray.CheckedChanged += (_, _) => { _s.StartInTray = tray.Checked; _s.Save(); };
        Row(startup);
        Row(tray);

        p = tInput;
        var audio = _audioBox = new CheckBox { Text = L.T("chk_audio"), AutoSize = true, Checked = _s.AudioToTablet };
        audio.CheckedChanged += (_, _) => { _s.AudioToTablet = audio.Checked; _s.Save(); _server.ApplySettings(); };
        Row(audio);
        p = tShow;
        var dpi = _dpiBox = new CheckBox { Text = L.T("chk_dpi"), AutoSize = true, Checked = _s.AutoDpi };
        dpi.CheckedChanged += (_, _) => { _s.AutoDpi = dpi.Checked; _s.Save(); _server.ApplySettings(); };
        Row(dpi);
        var adaptive = _adaptiveBox = new CheckBox { Text = L.T("chk_adaptive"), AutoSize = true, Checked = _s.AdaptiveBitrate };
        adaptive.CheckedChanged += (_, _) => { _s.AdaptiveBitrate = adaptive.Checked; _s.Save(); };
        Row(adaptive);

        var skip = _skipBox = new CheckBox { Text = L.T("chk_skip"), AutoSize = true, Checked = _s.SkipStatic };
        skip.CheckedChanged += (_, _) => { _s.SkipStatic = skip.Checked; _s.Save(); _server.ApplySettings(); };
        Row(skip);

        var hevc = new CheckBox { Text = L.T("chk_hevc"), AutoSize = true, Checked = _s.UseHevc };
        hevc.CheckedChanged += (_, _) => { _s.UseHevc = hevc.Checked; _s.Save(); _server.ApplySettings(); };
        Row(hevc);
        p = tInput;
        var notif = new CheckBox { Text = L.T("chk_notify"), AutoSize = true, Checked = _s.ForwardNotifications };
        notif.CheckedChanged += (_, _) => { _s.ForwardNotifications = notif.Checked; _s.Save(); };
        Row(notif);
        var clip = new CheckBox { Text = L.T("chk_clip"), AutoSize = true, Checked = _s.ShareClipboard };
        clip.CheckedChanged += (_, _) => { _s.ShareClipboard = clip.Checked; _s.Save(); };
        Row(clip);
        var rescue = new CheckBox { Text = L.T("chk_rescue"), AutoSize = true, Checked = _s.RescueWindows };
        rescue.CheckedChanged += (_, _) => { _s.RescueWindows = rescue.Checked; _s.Save(); };
        Row(rescue);
        var remember = new CheckBox { Text = L.T("chk_remember_windows"), AutoSize = true, Checked = _s.RememberWindows };
        remember.CheckedChanged += (_, _) => { _s.RememberWindows = remember.Checked; _s.Save(); };
        Row(remember);

        // タブレットのカメラ・マイク（いちばん先につないだタブレット）
        var cam = new CheckBox { Text = L.T("chk_camera"), AutoSize = true, Checked = _s.TabletCamera };
        var facing = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 160 };
        facing.Items.AddRange(new object[] { L.T("cam_front"), L.T("cam_back") });
        facing.SelectedIndex = _s.CameraFacing == "back" ? 1 : 0;
        cam.CheckedChanged += (_, _) => { _s.TabletCamera = cam.Checked; _s.Save(); _server.ApplyDevices(); };
        facing.SelectedIndexChanged += (_, _) => { _s.CameraFacing = facing.SelectedIndex == 1 ? "back" : "front"; _s.Save(); _server.ApplyDevices(); };
        Row(cam, facing);
        var mic = new CheckBox { Text = L.T("chk_mic"), AutoSize = true, Checked = _s.TabletMic };
        var micOut = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 260 };
        micOut.Items.Add(L.T("mic_auto"));
        foreach (var d in MicBridge.OutputDevices()) micOut.Items.Add(d);
        micOut.SelectedItem = _s.MicOutput.Length > 0 && micOut.Items.Contains(_s.MicOutput) ? _s.MicOutput : micOut.Items[0];
        mic.CheckedChanged += (_, _) => { _s.TabletMic = mic.Checked; _s.Save(); _server.ApplyDevices(); };
        micOut.SelectedIndexChanged += (_, _) =>
        {
            _s.MicOutput = micOut.SelectedIndex <= 0 ? "" : (string)micOut.SelectedItem!;
            _s.Save();
            _server.ApplyDevices();
        };
        Row(mic, micOut);
        Row(new Label { Text = L.T("cam_mic_help"), AutoSize = true, ForeColor = Theme.DimColor, MaximumSize = new Size(540, 0) });

        p = tConnect;
        // つないだまま画面を消す
        var sleep = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 160 };
        int[] mins = { 0, 5, 10, 15, 30, 60 };
        foreach (var m in mins) sleep.Items.Add(m == 0 ? L.T("sleep_off") : string.Format(L.T("sleep_min"), m));
        sleep.SelectedIndex = Math.Max(0, Array.IndexOf(mins, _s.SleepMinutes));
        sleep.SelectedIndexChanged += (_, _) => { _s.SleepMinutes = mins[sleep.SelectedIndex]; _s.Save(); };
        Row(Lbl("lbl_sleep"), sleep);

        var autoUsb = new CheckBox { Text = L.T("chk_autousb"), AutoSize = true, Checked = _s.AutoConnectUsb };
        autoUsb.CheckedChanged += (_, _) => { _s.AutoConnectUsb = autoUsb.Checked; _s.Save(); };
        Row(autoUsb);

        p = tInput;
        // つないだら開くソフト
        Row(new Label { Text = L.T("lbl_apps"), AutoSize = true, Margin = new Padding(3, 10, 3, 3) });
        var apps = _appsBox = new TextBox { Multiline = true, Width = 520, Height = 60, ScrollBars = ScrollBars.Vertical,
            Text = string.Join(Environment.NewLine, _s.LaunchApps) };
        apps.Leave += (_, _) => SaveApps();
        Row(apps);
        var browse = new Button { Text = L.T("btn_browse"), AutoSize = true };
        browse.Click += (_, _) =>
        {
            using var d = new OpenFileDialog { Filter = "*.exe;*.lnk|*.exe;*.lnk|*.*|*.*" };
            if (d.ShowDialog(this) != DialogResult.OK) return;
            var line = d.FileName.Contains(' ') ? $"\"{d.FileName}\"" : d.FileName;
            apps.Text = apps.Text.TrimEnd() + (apps.Text.Trim().Length > 0 ? Environment.NewLine : "") + line;
            SaveApps();
        };
        Row(browse);

        // タブレットのランチャーに並べるソフト
        Row(new Label { Text = L.T("lbl_launcher"), AutoSize = true, Margin = new Padding(3, 10, 3, 3), MaximumSize = new Size(540, 0) });
        var launcher = new TextBox { Multiline = true, Width = 520, Height = 60, ScrollBars = ScrollBars.Vertical,
            Text = string.Join(Environment.NewLine, _s.LauncherApps) };
        launcher.Leave += (_, _) =>
        {
            _s.LauncherApps = launcher.Lines.Select(l => l.Trim()).Where(l => l.Length > 0).ToList();
            _s.Save();
        };
        Row(launcher);

        p = tConnect;
        // PIN（つなぐときに数字を入れてもらう）
        var pin = new TextBox { Width = 120, Text = _s.Pin, MaxLength = 8 };
        pin.KeyPress += (_, e) => { if (!char.IsDigit(e.KeyChar) && !char.IsControl(e.KeyChar)) e.Handled = true; };
        pin.Leave += (_, _) => { _s.Pin = pin.Text.Trim(); _s.Save(); };
        Row(Lbl("lbl_pin"), pin);
        Row(new Label { Text = L.T("pin_help"), AutoSize = true, ForeColor = Theme.DimColor, MaximumSize = new Size(540, 0) });

        // 設定の組み合わせ
        var prof = _profileBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 200 };
        RefreshProfiles();
        prof.SelectedIndexChanged += (_, _) =>
        {
            if (prof.SelectedIndex < 0 || prof.SelectedIndex >= _s.Profiles.Count) return;
            ApplyProfile(_s.Profiles[prof.SelectedIndex]);
        };
        var saveProf = new Button { Text = L.T("btn_profile_save"), AutoSize = true };
        saveProf.Click += (_, _) =>
        {
            var name = TextPrompt.Ask(this, L.T("lbl_profile"), L.T("profile_name"),
                prof.SelectedIndex >= 0 ? _s.Profiles[prof.SelectedIndex].Name : "");
            if (name == null) return;
            SaveApps();
            _s.Profiles.RemoveAll(x => x.Name == name);
            _s.Profiles.Add(Profile.From(_s, name));
            _s.Save();
            RefreshProfiles();
        };
        var delProf = new Button { Text = L.T("btn_profile_delete"), AutoSize = true };
        delProf.Click += (_, _) =>
        {
            if (prof.SelectedIndex < 0) return;
            _s.Profiles.RemoveAt(prof.SelectedIndex);
            _s.Save();
            RefreshProfiles();
        };
        var profRow = new FlowLayoutPanel { AutoSize = true, WrapContents = false };
        profRow.Controls.AddRange(new Control[] { prof, saveProf, delProf });
        Row(Lbl("lbl_profile"), profRow);

        Row(new Label { Text = L.T("drop_hint"), AutoSize = true, ForeColor = Theme.DimColor, MaximumSize = new Size(540, 0) });
        Row(new Label { Text = L.T("hotkey_help"), AutoSize = true, ForeColor = Theme.DimColor, MaximumSize = new Size(540, 0) });

        Row(_driver);
        var tools = new FlowLayoutPanel { AutoSize = true, WrapContents = true, MaximumSize = new Size(540, 0) };
        tools.Controls.Add(Btn("btn_send_files", () =>
        {
            using var d = new OpenFileDialog { Multiselect = true };
            if (d.ShowDialog(this) == DialogResult.OK) SendFiles(d.FileNames);
        }));
        tools.Controls.Add(Btn("btn_wizard", ShowWizard));
        tools.Controls.Add(Btn("btn_diag", () =>
        {
            Cursor = Cursors.WaitCursor;
            var report = Diagnostics.Run(_s, _server);
            Cursor = Cursors.Default;
            using var f = new Form { Text = L.T("btn_diag"), Width = 720, Height = 520, StartPosition = FormStartPosition.CenterParent };
            f.Controls.Add(new TextBox { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Both, Dock = DockStyle.Fill,
                Text = report.Replace("\n", Environment.NewLine), Font = new Font(FontFamily.GenericMonospace, 10) });
            f.ShowDialog(this);
        }));
        tools.Controls.Add(Btn("btn_logs", () =>
        {
            Task.Run(() =>
            {
                try
                {
                    var path = LogBundle.Create(_s, _server);
                    Ui(() => _status.Text = string.Format(L.T("logs_done"), Path.GetFileName(path)));
                    Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"") { UseShellExecute = true });
                }
                catch (Exception e) { Log.Write("log bundle: " + e.Message); }
            });
        }));
        tools.Controls.Add(Btn("btn_changelog", ShowChangelog));
        tools.Controls.Add(Btn("btn_export", () =>
        {
            using var d = new SaveFileDialog { FileName = "TabDisplay-settings.json", Filter = "JSON|*.json" };
            if (d.ShowDialog(this) != DialogResult.OK) return;
            SaveApps();
            _s.Export(d.FileName);
            _status.Text = L.T("export_done");
        }));
        tools.Controls.Add(Btn("btn_import", () =>
        {
            using var d = new OpenFileDialog { Filter = "JSON|*.json" };
            if (d.ShowDialog(this) != DialogResult.OK) return;
            if (!AppSettings.Import(d.FileName)) { _status.Text = L.T("import_failed"); return; }
            // 読み込んだ設定で開き直す
            MessageBox.Show(this, L.T("import_done"), Text);
            Process.Start(new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = true });
            ExitApp();
        }));
        tools.Controls.Add(Btn("btn_update", () => CheckUpdate(quiet: false)));
        tools.Controls.Add(Btn("btn_hotkeys", () =>
        {
            using var f = new HotkeyForm(_s);
            f.ShowDialog(this);
            RegisterHotkeys();
        }));
        tools.Controls.Add(Btn("btn_usage", () =>
        {
            var rows = Usage.Recent(14);
            var text = rows.Count == 0 ? L.T("usage_none")
                : string.Join(Environment.NewLine, rows.Select(r =>
                    $"{r.day}   {(int)r.time.TotalHours}:{r.time.Minutes:00}   {Usage.Bytes(r.bytes)}"));
            MessageBox.Show(this, L.T("usage_head") + Environment.NewLine + Environment.NewLine + text, L.T("btn_usage"));
        }));
        tools.Controls.Add(Btn("btn_install_app", () => InstallApp()));
        tools.Controls.Add(Btn("btn_install_driver", InstallDriver));
        tools.Controls.Add(Btn("btn_uninstall_driver", UninstallDriver));
        tools.Controls.Add(Btn("btn_log", () =>
        {
            try { Process.Start(new ProcessStartInfo(Log.FilePath) { UseShellExecute = true }); } catch { }
        }));
        Row(tools);

        p = tOther;
        // 注意書き
        var notesTitle = new Label { Text = L.T("notes_title"), AutoSize = true, Font = new Font(Font, FontStyle.Bold), Margin = new Padding(3, 12, 3, 3) };
        Row(notesTitle);
        Row(new Label { Text = L.T("notes"), AutoSize = true, MaximumSize = new Size(540, 0), ForeColor = Theme.DimColor });
    }

    private bool _afterShownDone;

    /// <summary>起動のあと 1 回だけ：はじめの設定・書き置きのファイル・テザリングの案内・更新の確認</summary>
    private void AfterShown()
    {
        if (_afterShownDone) return;
        _afterShownDone = true;
        if (!_s.WizardDone && Visible)
        {
            _s.WizardDone = true;
            _s.Save();
            ShowWizard();
        }
        SendQueued();
        if (Diagnostics.TetherAdapter() is string t) _status.Text = string.Format(L.T("st_tether"), t);
        if ((DateTime.Now - _s.LastUpdateCheck).TotalHours > 20) CheckUpdate(quiet: true);
    }

    private void ShowWizard()
    {
        using var w = new SetupWizard(InstallDriver, InstallApp, () => ConnectUsb(false),
            () => new QrForm(_s).ShowDialog(this));
        w.ShowDialog(this);
    }

    private void SendFiles(IEnumerable<string> files)
    {
        var list = files.Where(File.Exists).ToList();
        if (list.Count == 0) return;
        if (_server.SendFiles(list)) _status.Text = string.Format(L.T("files_sent"), list.Count);
        else _status.Text = L.T("files_no_tablet");
    }

    private void SendQueued()
    {
        try
        {
            if (!File.Exists(Program.SendQueueFile)) return;
            var files = File.ReadAllLines(Program.SendQueueFile).Where(l => l.Trim().Length > 0).ToList();
            File.Delete(Program.SendQueueFile);
            if (files.Count > 0) SendFiles(files);
        }
        catch (Exception e) { Log.Write("send queue: " + e.Message); }
    }

    private void CheckUpdate(bool quiet)
    {
        Task.Run(async () =>
        {
            var r = await Updater.CheckAsync();
            _s.LastUpdateCheck = DateTime.Now;
            _s.Save();
            Ui(() =>
            {
                if (r == null) { if (!quiet) _status.Text = L.T("upd_fail"); return; }
                if (r.Build <= Updater.CurrentBuild || Updater.CurrentBuild == 0 && quiet)
                {
                    if (!quiet) _status.Text = string.Format(L.T("upd_none"), Updater.CurrentLabel);
                    return;
                }
                _pendingUpdate = r;
                if (quiet)
                {
                    _tray.ShowBalloonTip(5000, "TabDisplay", string.Format(L.T("upd_tray"), r.Tag), ToolTipIcon.Info);
                    _status.Text = string.Format(L.T("upd_tray"), r.Tag);
                    return;
                }
                if (MessageBox.Show(this, string.Format(L.T("upd_new"), r.Tag), Text, MessageBoxButtons.YesNo) != DialogResult.Yes) return;
                _status.Text = L.T("upd_running");
                Task.Run(async () =>
                {
                    if (await Updater.InstallAsync(r)) Ui(ExitApp);
                    else Ui(() => _status.Text = L.T("upd_fail"));
                });
            });
        });
    }

    /// <summary>更新履歴を見る（ソフトに入れてある CHANGELOG.md）</summary>
    private void ShowChangelog()
    {
        string text;
        try
        {
            using var st = typeof(MainForm).Assembly.GetManifestResourceStream("CHANGELOG.md")!;
            using var r = new StreamReader(st);
            text = r.ReadToEnd();
        }
        catch { text = ""; }
        using var f = new Form
        {
            Text = L.T("btn_changelog") + " — " + Updater.CurrentLabel,
            Width = 640,
            Height = 600,
            StartPosition = FormStartPosition.CenterParent,
        };
        f.Controls.Add(new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill,
            Text = text.Replace("\r\n", "\n").Replace("\n", Environment.NewLine),
            Font = new Font(Font.FontFamily, 10),
        });
        f.ShowDialog(this);
    }

    private void SaveApps()
    {
        _s.LaunchApps = _appsBox.Lines.Select(l => l.Trim()).Where(l => l.Length > 0).ToList();
        _s.Save();
    }

    private void RefreshProfiles()
    {
        _profileBox.Items.Clear();
        foreach (var p in _s.Profiles) _profileBox.Items.Add(p.Name);
    }

    /// <summary>保存した組み合わせを、画面の部品ごと切り替える（部品の変更で設定も保存・反映される）</summary>
    private void ApplyProfile(Profile p)
    {
        p.ApplyTo(_s);
        _s.Save();
        _modeBox.SelectedIndex = (int)_s.Mode;
        _sideBox.SelectedIndex = (int)_s.Side;
        _rotateBox.Checked = _s.FollowRotation;
        _fpsBox.SelectedItem = _s.Fps.ToString();
        _brBox.SelectedItem = _s.BitrateMbps.ToString();
        _audioBox.Checked = _s.AudioToTablet;
        _dpiBox.Checked = _s.AutoDpi;
        _adaptiveBox.Checked = _s.AdaptiveBitrate;
        _skipBox.Checked = _s.SkipStatic;
        _qualityBox.SelectedIndex = (int)_s.Quality;
        _appsBox.Text = string.Join(Environment.NewLine, _s.LaunchApps);
        _server.ApplySettings();
        _status.Text = string.Format(L.T("profile_applied"), p.Name);
    }

    // ---------- ショートカットキー（自分で決められる） ----------

    private void RegisterHotkeys()
    {
        for (int i = 0; i < Hotkeys.Actions.Length; i++)
        {
            Win.UnregisterHotKey(Handle, HotkeyId + i);
            var k = Hotkeys.Parse(Hotkeys.Get(_s, Hotkeys.Actions[i].action));
            if (k == null) continue;
            if (!Win.RegisterHotKey(Handle, HotkeyId + i, k.Value.mods | Win.MOD_NOREPEAT, (uint)k.Value.key))
                Log.Write($"hotkey {Hotkeys.Get(_s, Hotkeys.Actions[i].action)} is in use");
        }
    }

    private void RunHotkey(string action)
    {
        switch (action)
        {
            case "move_window":
                Win.MoveForegroundToNext();
                break;
            case "next_mode":
            {
                // PC のみ は飛ばして順に
                ShowMode[] order = { ShowMode.Extend, ShowMode.Duplicate, ShowMode.TabletOnly, ShowMode.Region, ShowMode.Span };
                int i = Array.IndexOf(order, _s.Mode);
                _modeBox.SelectedIndex = (int)order[(i + 1) % order.Length];
                _tray.ShowBalloonTip(1500, "TabDisplay", (string)_modeBox.SelectedItem!, ToolTipIcon.None);
                break;
            }
            case "pc_only":
                if (_s.Mode != ShowMode.PcOnly)
                {
                    _s.ModeBeforePcOnly = _s.Mode;
                    _modeBox.SelectedIndex = (int)ShowMode.PcOnly;
                }
                else
                {
                    _modeBox.SelectedIndex = (int)_s.ModeBeforePcOnly;
                }
                break;
            case "connect_usb":
                ConnectUsb(false);
                break;
            case "disconnect":
                _server.Disconnect();
                break;
        }
    }

    // ---------- （以下、窓の処理） ----------

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        RegisterHotkeys();
        AddClipboardFormatListener(Handle);
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WM_CLIPBOARDUPDATE)
        {
            // PC でコピーした文字をタブレットへ（タブレットから来たものは送り返さない）
            try
            {
                if (_s.ShareClipboard && _server.Connected && Clipboard.ContainsText())
                {
                    var t = Clipboard.GetText();
                    if (t != _clipLast)
                    {
                        _clipLast = t;
                        _server.BroadcastClip(t);
                    }
                }
            }
            catch { }
        }
        if (m.Msg == Win.WM_HOTKEY)
        {
            int i = m.WParam.ToInt32() - HotkeyId;
            if (i >= 0 && i < Hotkeys.Actions.Length)
            {
                try { RunHotkey(Hotkeys.Actions[i].action); } catch (Exception ex) { Log.Write("hotkey: " + ex.Message); }
                return;
            }
        }
        base.WndProc(ref m);
    }

    private static Label Lbl(string key) => new() { Text = L.T(key), AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(3, 8, 3, 3) };

    private static Button Btn(string key, Action a)
    {
        var b = new Button { Text = L.T(key), AutoSize = true };
        b.Click += (_, _) => a();
        return b;
    }

    private void RefreshDriverLabel() => _driver.Text = L.T(VirtualDisplay.IsInstalled ? "driver_yes" : "driver_none");

    // ---------- USB ----------

    private void ConnectUsb(bool auto)
    {
        if (_usbBusy) return;
        _usbBusy = true;
        _btnUsb.Enabled = false;
        Task.Run(() =>
        {
            try
            {
                if (!Adb.Available) { SetStatus("no_adb"); return; }
                var devs = Adb.Devices();
                var ready = devs.Where(d => d.State == "device").ToList();
                if (ready.Count == 0)
                {
                    if (!auto) SetStatus(devs.Any(d => d.State == "unauthorized") ? "usb_unauthorized" : "usb_no_device");
                    return;
                }
                // 挿してあるタブレット全部につなぐ（2 台以上も）
                foreach (var dev in ready)
                {
                    lock (_autoSerials) _autoSerials.Add(dev.Serial);
                    // アプリが入っていない・古いときは入れる
                    var have = Adb.InstalledVersion(dev.Serial);
                    if (have == 0 || have < Adb.BundledVersion())
                    {
                        SetStatus("usb_installing");
                        if (!Adb.Install(dev.Serial).ok) { SetStatus("usb_install_failed"); continue; }
                    }
                    SetStatus("usb_starting");
                    if (!Adb.Reverse(dev.Serial, _s.Port) ||
                        !Adb.Launch(dev.Serial, _s.Port, _s.Token, Environment.MachineName, _s.Pin))
                    {
                        SetStatus("usb_failed");
                    }
                }
            }
            catch (Exception e)
            {
                Log.Write("ConnectUsb: " + e);
                SetStatus("usb_failed");
            }
            finally
            {
                _usbBusy = false;
                Ui(() => _btnUsb.Enabled = true);
            }
        });
    }

    private void AutoUsbTick()
    {
        if (!_s.AutoConnectUsb || _usbBusy || !Adb.Available) return;
        Task.Run(() =>
        {
            var serials = Adb.Devices().Where(d => d.State == "device").Select(d => d.Serial).ToHashSet();
            // 抜いたタブレットは忘れる（次に挿したときにまた自動でつなぐ）
            bool fresh;
            lock (_autoSerials)
            {
                _autoSerials.RemoveWhere(x => !serials.Contains(x));
                // まだつないでいないタブレットがあれば、つなぐ（「切断」したものは挿し直すまでつながない）
                fresh = serials.Any(x => !_autoSerials.Contains(x));
            }
            if (fresh) Ui(() => ConnectUsb(true));
        });
    }

    private void InstallApp()
    {
        Task.Run(() =>
        {
            var dev = Adb.Devices().FirstOrDefault(d => d.State == "device");
            if (dev == null) { SetStatus("usb_no_device"); return; }
            SetStatus("usb_installing");
            SetStatus(Adb.Install(dev.Serial).ok ? "usb_installed" : "usb_install_failed");
        });
    }

    // ---------- ドライバー ----------

    private void InstallDriver()
    {
        Task.Run(() =>
        {
            var ok = DriverSetup.RunElevated("--install-driver");
            Ui(() =>
            {
                RefreshDriverLabel();
                _status.Text = ok ? L.T("driver_ok") : string.Format(L.T("driver_ng"), Log.FilePath);
            });
        });
    }

    private void UninstallDriver()
    {
        _server.Disconnect();
        Task.Run(() =>
        {
            DriverSetup.RunElevated("--uninstall-driver");
            Ui(() =>
            {
                RefreshDriverLabel();
                _status.Text = L.T("driver_removed");
            });
        });
    }

    // ---------- トレイ ----------

    /// <summary>右下のアイコンのメニュー：窓を開かなくても、ふだんの操作は全部ここでできる</summary>
    private void BuildTrayMenu(ContextMenuStrip m)
    {
        m.Items.Add(L.T("tray_open"), null, (_, _) => ShowFromTray());
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add(L.T("btn_connect_usb"), null, (_, _) => ConnectUsb(false));
        m.Items.Add(L.T("btn_wifi"), null, (_, _) => { ShowFromTray(); new QrForm(_s).ShowDialog(this); });
        m.Items.Add(L.T("btn_send_files"), null, (_, _) =>
        {
            using var d = new OpenFileDialog { Multiselect = true };
            if (d.ShowDialog() == DialogResult.OK) SendFiles(d.FileNames);
        });
        var disconnect = new ToolStripMenuItem(L.T("btn_disconnect"), null, (_, _) => _server.Disconnect());
        m.Items.Add(disconnect);
        m.Items.Add(new ToolStripSeparator());

        var modeMenu = new ToolStripMenuItem(L.T("tray_mode"));
        string[] modeKeys = { "mode_extend", "mode_duplicate", "mode_tablet", "mode_pc", "mode_region", "mode_span" };
        for (int i = 0; i < modeKeys.Length; i++)
        {
            int idx = i;
            modeMenu.DropDownItems.Add(new ToolStripMenuItem(L.T(modeKeys[i]), null, (_, _) => _modeBox.SelectedIndex = idx));
        }
        m.Items.Add(modeMenu);

        var sideMenu = new ToolStripMenuItem(L.T("lbl_side"));
        string[] sideKeys = { "side_right", "side_left", "side_top", "side_bottom" };
        for (int i = 0; i < sideKeys.Length; i++)
        {
            int idx = i;
            sideMenu.DropDownItems.Add(new ToolStripMenuItem(L.T(sideKeys[i]), null, (_, _) => _sideBox.SelectedIndex = idx));
        }
        m.Items.Add(sideMenu);

        var profMenu = new ToolStripMenuItem(L.T("lbl_profile"));
        m.Items.Add(profMenu);

        var rotate = new ToolStripMenuItem(L.T("chk_rotate"), null, (_, _) => _rotateBox.Checked = !_rotateBox.Checked);
        var audio = new ToolStripMenuItem(L.T("chk_audio"), null, (_, _) => _audioBox.Checked = !_audioBox.Checked);
        m.Items.Add(rotate);
        m.Items.Add(audio);
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add(L.T("btn_changelog"), null, (_, _) => { ShowFromTray(); ShowChangelog(); });
        m.Items.Add(L.T("tray_exit"), null, (_, _) => ExitApp());

        // 開くたびに今の状態をチェックに出す
        m.Opening += (_, _) =>
        {
            disconnect.Enabled = _server.Connected;
            for (int i = 0; i < modeMenu.DropDownItems.Count; i++)
                ((ToolStripMenuItem)modeMenu.DropDownItems[i]).Checked = (int)_s.Mode == i;
            for (int i = 0; i < sideMenu.DropDownItems.Count; i++)
                ((ToolStripMenuItem)sideMenu.DropDownItems[i]).Checked = (int)_s.Side == i;
            rotate.Checked = _s.FollowRotation;
            audio.Checked = _s.AudioToTablet;
            profMenu.DropDownItems.Clear();
            foreach (var p in _s.Profiles.ToList())
                profMenu.DropDownItems.Add(new ToolStripMenuItem(p.Name, null, (_, _) => ApplyProfile(p)));
            profMenu.Enabled = _s.Profiles.Count > 0;
        };
    }

    private void ShowFromTray()
    {
        _startHidden = false;
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!_exiting && e.CloseReason == CloseReason.UserClosing)
        {
            // × は終了ではなく、右下のアイコンにしまう
            e.Cancel = true;
            Hide();
            _tray.ShowBalloonTip(2000, "TabDisplay", L.T("tray_hint"), ToolTipIcon.Info);
            return;
        }
        base.OnFormClosing(e);
    }

    private void ExitApp()
    {
        _exiting = true;
        _usbTimer.Stop();
        _notify?.Dispose();
        CameraBridge.Shutdown();
        Usage.Save();
        _server.Dispose();
        // 切断の片付け（画面を戻す）が終わるのを少し待つ
        Thread.Sleep(300);
        Displays.RestoreIfNeeded();
        VirtualDisplay.Disable();
        _tray.Visible = false;
        _tray.Dispose();
        Application.Exit();
    }

    private void SetStatus(string key) => Ui(() => _status.Text = L.T(key));

    private void Ui(Action a)
    {
        if (IsDisposed) return;
        try
        {
            if (InvokeRequired) BeginInvoke(a);
            else a();
        }
        catch (InvalidOperationException) { }
    }
}
