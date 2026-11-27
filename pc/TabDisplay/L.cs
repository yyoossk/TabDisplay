using System.Globalization;

namespace TabDisplay;

/// <summary>画面の文字。既定はシステムの言語（日本語以外は英語）</summary>
public static class L
{
    public static string Lang { get; private set; } = "en";

    public static void Init(string setting)
    {
        Lang = setting switch
        {
            "ja" => "ja",
            "en" => "en",
            _ => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ja" ? "ja" : "en",
        };
    }

    public static string T(string key)
    {
        var table = Lang == "ja" ? Ja : En;
        if (table.TryGetValue(key, out var s)) return s;
        return En.TryGetValue(key, out var e) ? e : key;
    }

    private static readonly Dictionary<string, string> Ja = new()
    {
        ["title"] = "TabDisplay",
        ["st_idle"] = "タブレットを待っています",
        ["st_connected"] = "{0} とつながりました",
        ["st_disconnected"] = "切断しました",
        ["st_pc_only"] = "PC のみ：{0} には映していません",
        ["st_no_driver"] = "仮想ディスプレイが入っていません。「仮想ディスプレイを入れる」を押してください",
        ["st_preparing"] = "画面を準備しています…",
        ["st_vdd_failed"] = "仮想ディスプレイをオンにできませんでした",
        ["st_capture_failed"] = "画面を撮れませんでした（「ログを開く」で内容を見られます）",
        ["st_streaming"] = "{0} に映しています（{1}×{2}・{3}）",
        ["usb_no_device"] = "タブレットが見つかりません。タブレットの「USB デバッグ」をオンにして、USB で挿してください",
        ["usb_unauthorized"] = "タブレットに出ている「USB デバッグを許可しますか？」で「許可」を押してください",
        ["usb_installing"] = "タブレットにアプリを入れています…",
        ["usb_install_failed"] = "タブレットにアプリを入れられませんでした",
        ["usb_installed"] = "タブレットにアプリを入れました",
        ["usb_starting"] = "タブレットのアプリを起動しています…",
        ["usb_failed"] = "USB で接続できませんでした",
        ["no_adb"] = "adb が見つかりません（tools フォルダーを消していませんか？）",
        ["btn_connect_usb"] = "接続する（USB）",
        ["btn_wifi"] = "Wi-Fi（QR コード）",
        ["btn_disconnect"] = "切断",
        ["btn_install_app"] = "タブレットにアプリを入れる",
        ["btn_install_driver"] = "仮想ディスプレイを入れる",
        ["btn_uninstall_driver"] = "仮想ディスプレイを消す",
        ["btn_log"] = "ログを開く",
        ["lbl_mode"] = "表示のしかた",
        ["mode_extend"] = "PC と両方（画面を広げる）",
        ["mode_duplicate"] = "PC と両方（同じ画面を映す）",
        ["mode_tablet"] = "タブレットのみ",
        ["mode_pc"] = "PC のみ",
        ["chk_rotate"] = "タブレットを回したら、PC の画面も一緒に回す",
        ["lbl_fps"] = "1 秒あたりの枚数",
        ["lbl_bitrate"] = "画質（Mbps）",
        ["lbl_lang"] = "言語",
        ["lang_system"] = "システムの言語",
        ["chk_startup"] = "Windows の起動時に開く",
        ["chk_tray"] = "起動時は右下のアイコンにしまう",
        ["chk_autousb"] = "USB で挿したら自動で接続する",
        ["chk_audio"] = "PC の音もタブレットで鳴らす",
        ["lbl_side"] = "タブレットの置き場所",
        ["side_right"] = "PC の画面の右",
        ["side_left"] = "PC の画面の左",
        ["side_top"] = "PC の画面の上",
        ["side_bottom"] = "PC の画面の下",
        ["chk_skip"] = "省電力：画面が変わらないときは送らない",
        ["tray_mode"] = "表示のしかた",
        ["chk_dpi"] = "文字の大きさ（拡大率）をタブレットに合わせる",
        ["chk_adaptive"] = "回線の混み具合に合わせて画質を自動で調整する（上の画質が上限）",
        ["tray_open"] = "開く",
        ["tray_exit"] = "終了",
        ["tray_hint"] = "右下のアイコンにしまいました。ダブルクリックで開きます",
        ["stats_fmt"] = "{0} fps ／ 遅れ {1} ms ／ 往復 {2} ms",
        ["qr_title"] = "Wi-Fi で接続",
        ["qr_help"] = "タブレットの TabDisplay で「QR コードを読む」を押して、このコードを読み取ってください。\nPC とタブレットは同じ Wi-Fi（ネットワーク）につないでください。",
        ["qr_no_ip"] = "この PC のネットワークのアドレスが見つかりません",
        ["driver_ok"] = "仮想ディスプレイを入れました",
        ["driver_ng"] = "仮想ディスプレイを入れられませんでした。ログ: {0}",
        ["driver_removed"] = "仮想ディスプレイを消しました",
        ["driver_none"] = "仮想ディスプレイ：未導入",
        ["driver_yes"] = "仮想ディスプレイ：導入済み",
        ["lang_restart"] = "言語は次に起動したときから変わります",
        ["port_busy"] = "ポート {0} が使えません。ほかの TabDisplay が開いていないか確かめてください",
        ["notes_title"] = "注意",
        ["notes"] =
            "・タブレットを 2 台以上つないでいるときに、1 台つないだり外したりすると、全部のタブレットの映像が数秒止まります（画面を組み直すため）。\n" +
            "・画質の自動調整で画質が変わるとき、そのタブレットの映像が一瞬止まります（6 秒に 1 回まで）。\n" +
            "・タブレットを回したときも、そのタブレットの映像がごく短く止まります。\n" +
            "・通信はすべて暗号化しています。Wi-Fi で初めてつなぐときは QR コードを読んでください（PC の確認に使います）。\n" +
            "・管理者として動いているソフトの窓は、タブレットから操作できません（Windows の決まり）。\n" +
            "・ペンの筆圧・傾きは Windows 10（1809）以降で使えます。それより前はマウスの代わりになります。\n" +
            "・PC の音は、タブレットで少し遅れて鳴ります。PC 側のスピーカーからも鳴ったままです。\n" +
            "・USB でつなぐには、タブレットの「USB デバッグ」をオンにしておく必要があります。\n" +
            "・「タブレットのみ」で切断したときは、PC の画面を自動で元に戻します。",
    };

    private static readonly Dictionary<string, string> En = new()
    {
        ["title"] = "TabDisplay",
        ["st_idle"] = "Waiting for a tablet",
        ["st_connected"] = "Connected to {0}",
        ["st_disconnected"] = "Disconnected",
        ["st_pc_only"] = "PC only: nothing is shown on {0}",
        ["st_no_driver"] = "The virtual display is not installed. Press \"Install virtual display\".",
        ["st_preparing"] = "Preparing the screen…",
        ["st_vdd_failed"] = "Could not turn on the virtual display",
        ["st_capture_failed"] = "Could not capture the screen (see \"Open log\")",
        ["st_streaming"] = "Showing on {0} ({1}×{2}, {3})",
        ["usb_no_device"] = "No tablet found. Turn on \"USB debugging\" on the tablet and plug it in with USB.",
        ["usb_unauthorized"] = "On the tablet, press \"Allow\" for \"Allow USB debugging?\"",
        ["usb_installing"] = "Installing the app on the tablet…",
        ["usb_install_failed"] = "Could not install the app on the tablet",
        ["usb_installed"] = "Installed the app on the tablet",
        ["usb_starting"] = "Starting the app on the tablet…",
        ["usb_failed"] = "Could not connect with USB",
        ["no_adb"] = "adb not found (was the tools folder deleted?)",
        ["btn_connect_usb"] = "Connect (USB)",
        ["btn_wifi"] = "Wi-Fi (QR code)",
        ["btn_disconnect"] = "Disconnect",
        ["btn_install_app"] = "Install app on tablet",
        ["btn_install_driver"] = "Install virtual display",
        ["btn_uninstall_driver"] = "Remove virtual display",
        ["btn_log"] = "Open log",
        ["lbl_mode"] = "Show on",
        ["mode_extend"] = "PC and tablet (extend)",
        ["mode_duplicate"] = "PC and tablet (same picture)",
        ["mode_tablet"] = "Tablet only",
        ["mode_pc"] = "PC only",
        ["chk_rotate"] = "Rotate the PC screen when the tablet is rotated",
        ["lbl_fps"] = "Frames per second",
        ["lbl_bitrate"] = "Quality (Mbps)",
        ["lbl_lang"] = "Language",
        ["lang_system"] = "System language",
        ["chk_startup"] = "Open when Windows starts",
        ["chk_tray"] = "Start minimized to the tray icon",
        ["chk_autousb"] = "Connect automatically when plugged in with USB",
        ["chk_audio"] = "Play the PC's sound on the tablet",
        ["lbl_side"] = "Tablet position",
        ["side_right"] = "Right of the PC screen",
        ["side_left"] = "Left of the PC screen",
        ["side_top"] = "Above the PC screen",
        ["side_bottom"] = "Below the PC screen",
        ["chk_skip"] = "Power saving: send nothing while the screen does not change",
        ["tray_mode"] = "Show on",
        ["chk_dpi"] = "Match the text size (scale) to the tablet",
        ["chk_adaptive"] = "Adjust quality automatically to the connection (up to the quality above)",
        ["tray_open"] = "Open",
        ["tray_exit"] = "Exit",
        ["tray_hint"] = "Minimized to the tray. Double-click the icon to open.",
        ["stats_fmt"] = "{0} fps / delay {1} ms / ping {2} ms",
        ["qr_title"] = "Connect with Wi-Fi",
        ["qr_help"] = "In TabDisplay on the tablet, press \"Scan QR code\" and scan this code.\nThe PC and the tablet must be on the same Wi-Fi network.",
        ["qr_no_ip"] = "No network address found on this PC",
        ["driver_ok"] = "Installed the virtual display",
        ["driver_ng"] = "Could not install the virtual display. Log: {0}",
        ["driver_removed"] = "Removed the virtual display",
        ["driver_none"] = "Virtual display: not installed",
        ["driver_yes"] = "Virtual display: installed",
        ["lang_restart"] = "The language changes the next time you start the app",
        ["port_busy"] = "Port {0} is in use. Check that TabDisplay is not already open.",
        ["notes_title"] = "Notes",
        ["notes"] =
            "- With two or more tablets, connecting or disconnecting one stops the picture on all tablets for a few seconds (the screens are rebuilt).\n" +
            "- When automatic quality changes the quality, that tablet's picture stops for a moment (at most once every 6 seconds).\n" +
            "- Rotating a tablet also stops that tablet's picture very briefly.\n" +
            "- All communication is encrypted. The first Wi-Fi connection needs the QR code (it is used to verify the PC).\n" +
            "- Windows of programs running as administrator cannot be operated from the tablet (a Windows rule).\n" +
            "- Pen pressure and tilt need Windows 10 (1809) or later. On older Windows the pen works as a mouse.\n" +
            "- The PC's sound plays on the tablet with a short delay, and still plays from the PC speakers.\n" +
            "- To connect with USB, \"USB debugging\" must be on on the tablet.\n" +
            "- In \"Tablet only\", the PC screens are restored automatically when the tablet disconnects.",
    };
}
