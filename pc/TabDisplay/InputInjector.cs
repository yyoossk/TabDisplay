using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using static TabDisplay.Native;

namespace TabDisplay;

/// <summary>
/// タブレットからの操作を PC の操作にする。
/// 座標は「映している画面の中の割合（0〜1）」で届くので、その画面の位置に直してから動かす。
///  マウス：click / rclick / move / scroll / zoom（Ctrl + ホイール）
///  キー：text（文字）/ vk（1 回押す）/ key（押す・離すを別々に）
///  ペン：pen（筆圧・傾き・サイドボタン・消しゴム）
/// </summary>
public static class InputInjector
{
    private static readonly object Lock = new();

    public static void Handle(DisplayInfo? t, JsonObject j)
    {
        var kind = (string?)j["k"] ?? "";
        double x = (double?)j["x"] ?? 0, y = (double?)j["y"] ?? 0;
        lock (Lock)
        {
            switch (kind)
            {
                case "move":
                    if (t != null) Move(t, x, y);
                    break;
                case "click":
                    if (t == null) break;
                    Move(t, x, y);
                    Mouse(MOUSEEVENTF_LEFTDOWN, 0);
                    Mouse(MOUSEEVENTF_LEFTUP, 0);
                    break;
                case "rclick":
                    if (t == null) break;
                    Move(t, x, y);
                    Mouse(MOUSEEVENTF_RIGHTDOWN, 0);
                    Mouse(MOUSEEVENTF_RIGHTUP, 0);
                    break;
                case "scroll":
                {
                    int wx = (int?)j["wx"] ?? 0, wy = (int?)j["wy"] ?? 0;
                    if (wy != 0) Mouse(MOUSEEVENTF_WHEEL, wy);
                    if (wx != 0) Mouse(MOUSEEVENTF_HWHEEL, wx);
                    break;
                }
                case "zoom":
                {
                    int z = (int?)j["z"] ?? 0;
                    if (z == 0) break;
                    Key(0xA2, true);   // 左 Ctrl
                    Mouse(MOUSEEVENTF_WHEEL, z);
                    Key(0xA2, false);
                    break;
                }
                case "text":
                    Text((string?)j["s"] ?? "");
                    break;
                case "vk":
                {
                    int vk = (int?)j["vk"] ?? 0;
                    if (vk == 0) break;
                    Key(vk, true);
                    Key(vk, false);
                    break;
                }
                case "key":
                {
                    int vk = (int?)j["vk"] ?? 0;
                    if (vk != 0) Key(vk, (bool?)j["d"] ?? true);
                    break;
                }
                case "combo":
                {
                    // ショートカット：順に押して、逆の順に離す（例：Ctrl, C）
                    var keys = (j["vks"] as JsonArray)?.Select(n => (int?)n ?? 0).Where(v => v != 0).ToList() ?? new();
                    foreach (var vk in keys) Key(vk, true);
                    for (int k = keys.Count - 1; k >= 0; k--) Key(keys[k], false);
                    break;
                }
                case "rel":
                {
                    // タッチパッド：カーソルを少し動かす
                    var i = new INPUT { type = 0, mi = new MOUSEINPUT { dx = (int?)j["dx"] ?? 0, dy = (int?)j["dy"] ?? 0, dwFlags = MOUSEEVENTF_MOVE } };
                    SendInput(1, new[] { i }, Marshal.SizeOf<INPUT>());
                    break;
                }
                case "ldown":
                    Mouse(MOUSEEVENTF_LEFTDOWN, 0);
                    break;
                case "lup":
                    Mouse(MOUSEEVENTF_LEFTUP, 0);
                    break;
                case "lclick":
                    Mouse(MOUSEEVENTF_LEFTDOWN, 0);
                    Mouse(MOUSEEVENTF_LEFTUP, 0);
                    break;
                case "mclick":
                    if (t != null && j["x"] != null) Move(t, x, y);
                    Mouse(0x0020, 0); // 中ボタン
                    Mouse(0x0040, 0);
                    break;
                case "rclick_here":
                    Mouse(MOUSEEVENTF_RIGHTDOWN, 0);
                    Mouse(MOUSEEVENTF_RIGHTUP, 0);
                    break;
                case "pen":
                    if (t != null) PenInput.Handle(t, j);
                    break;
            }
        }
    }

    // ---------- マウス ----------

    /// <summary>画面の中の割合 → 画面上のピクセル</summary>
    public static (int px, int py) ToPixel(DisplayInfo t, double x, double y) =>
        ((int)Math.Round(t.X + Math.Clamp(x, 0, 1) * (t.Width - 1)),
         (int)Math.Round(t.Y + Math.Clamp(y, 0, 1) * (t.Height - 1)));

    private static void Move(DisplayInfo t, double x, double y)
    {
        var (px, py) = ToPixel(t, x, y);
        // 全部の画面をまとめた「仮想デスクトップ」の中の割合（0〜65535）に直す
        int vx = GetSystemMetrics(SM_XVIRTUALSCREEN);
        int vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
        int vw = Math.Max(1, GetSystemMetrics(SM_CXVIRTUALSCREEN) - 1);
        int vh = Math.Max(1, GetSystemMetrics(SM_CYVIRTUALSCREEN) - 1);
        var i = new INPUT
        {
            type = 0,
            mi = new MOUSEINPUT
            {
                dx = (int)Math.Round((px - vx) * 65535.0 / vw),
                dy = (int)Math.Round((py - vy) * 65535.0 / vh),
                dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
            },
        };
        SendInput(1, new[] { i }, Marshal.SizeOf<INPUT>());
    }

    private static void Mouse(uint flags, int data)
    {
        var i = new INPUT { type = 0, mi = new MOUSEINPUT { dwFlags = flags, mouseData = data } };
        SendInput(1, new[] { i }, Marshal.SizeOf<INPUT>());
    }

    // ---------- キー ----------

    // 矢印・Home・End など「拡張キー」の印が要るもの
    private static readonly HashSet<int> Extended = new()
    {
        0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0xA3, 0xA5, 0x5B, 0x5C,
    };

    private static void Key(int vk, bool down)
    {
        var k = new KEYINPUT
        {
            type = 1,
            wVk = (ushort)vk,
            dwFlags = (down ? 0 : KEYEVENTF_KEYUP) | (Extended.Contains(vk) ? KEYEVENTF_EXTENDEDKEY : 0),
        };
        SendKeyInput(1, new[] { k }, Marshal.SizeOf<KEYINPUT>());
    }

    /// <summary>文字をそのまま入れる（日本語も。PC の IME を通さない）</summary>
    private static void Text(string s)
    {
        if (s.Length == 0) return;
        var list = new List<KEYINPUT>();
        foreach (var ch in s)
        {
            if (ch == '\n' || ch == '\r')
            {
                if (ch == '\r') continue;
                list.Add(new KEYINPUT { type = 1, wVk = 0x0D });
                list.Add(new KEYINPUT { type = 1, wVk = 0x0D, dwFlags = KEYEVENTF_KEYUP });
                continue;
            }
            list.Add(new KEYINPUT { type = 1, wScan = ch, dwFlags = KEYEVENTF_UNICODE });
            list.Add(new KEYINPUT { type = 1, wScan = ch, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP });
        }
        SendKeyInput((uint)list.Count, list.ToArray(), Marshal.SizeOf<KEYINPUT>());
    }
}

/// <summary>
/// ペン入力。Windows の「合成ポインター」でペンとして入れるので、ペイントソフトで筆圧が効く。
/// 使えない Windows（1809 より前）では、マウスの左ボタンで代わりにする。
/// </summary>
internal static class PenInput
{
    private static IntPtr _device;
    private static bool _unsupported;
    private static bool _inContact;
    private static bool _mouseDown;
    private static readonly int TypeInfoSize = 8 + Marshal.SizeOf<POINTER_TOUCH_INFO>();

    public static void Handle(DisplayInfo t, JsonObject j)
    {
        var a = (string?)j["a"] ?? "m";
        var (px, py) = InputInjector.ToPixel(t, (double?)j["x"] ?? 0, (double?)j["y"] ?? 0);
        double p = (double?)j["p"] ?? 0.5;
        int tx = (int?)j["tx"] ?? 0, ty = (int?)j["ty"] ?? 0;
        bool barrel = (bool?)j["b"] ?? false;
        bool eraser = (bool?)j["e"] ?? false;

        if (!_unsupported && _device == IntPtr.Zero)
        {
            try
            {
                _device = CreateSyntheticPointerDevice(PT_PEN, 1, POINTER_FEEDBACK_DEFAULT);
            }
            catch (EntryPointNotFoundException) { }
            if (_device == IntPtr.Zero)
            {
                _unsupported = true;
                Log.Write("synthetic pen not available, using mouse");
            }
        }
        if (_unsupported)
        {
            MouseFallback(t, a, (double?)j["x"] ?? 0, (double?)j["y"] ?? 0);
            return;
        }

        uint flags;
        switch (a)
        {
            case "d":
                flags = POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN | POINTER_FLAG_FIRSTBUTTON;
                _inContact = true;
                break;
            case "m":
                flags = POINTER_FLAG_INRANGE | POINTER_FLAG_UPDATE |
                        (_inContact ? POINTER_FLAG_INCONTACT | POINTER_FLAG_FIRSTBUTTON : 0);
                break;
            case "u":
                flags = POINTER_FLAG_INRANGE | POINTER_FLAG_UP;
                _inContact = false;
                break;
            case "h":
                flags = POINTER_FLAG_INRANGE | POINTER_FLAG_UPDATE;
                break;
            default: // "x"：ペンが離れた
                flags = POINTER_FLAG_UPDATE;
                _inContact = false;
                break;
        }
        if (barrel && _inContact) flags |= POINTER_FLAG_SECONDBUTTON;

        var info = new POINTER_PEN_INFO
        {
            pointerInfo = new POINTER_INFO
            {
                pointerType = PT_PEN,
                pointerId = 0,
                pointerFlags = flags,
                ptPixelLocation = new POINT { x = px, y = py },
            },
            penFlags = (barrel ? PEN_FLAG_BARREL : 0) | (eraser ? PEN_FLAG_ERASER : 0),
            penMask = PEN_MASK_PRESSURE | PEN_MASK_TILT_X | PEN_MASK_TILT_Y,
            pressure = (uint)Math.Clamp(p * 1024, 0, 1024),
            tiltX = Math.Clamp(tx, -90, 90),
            tiltY = Math.Clamp(ty, -90, 90),
        };
        var buf = Marshal.AllocHGlobal(TypeInfoSize);
        try
        {
            for (int k = 0; k < TypeInfoSize; k++) Marshal.WriteByte(buf, k, 0);
            Marshal.WriteInt32(buf, 0, PT_PEN);
            Marshal.StructureToPtr(info, buf + 8, false);
            if (!InjectSyntheticPointerInput(_device, buf, 1))
                Log.Write("InjectSyntheticPointerInput failed: " + Marshal.GetLastWin32Error());
        }
        finally
        {
            Marshal.FreeHGlobal(buf);
        }
    }

    private static void MouseFallback(DisplayInfo t, string a, double x, double y)
    {
        var j = new JsonObject { ["k"] = "move", ["x"] = x, ["y"] = y };
        InputInjector.Handle(t, j);
        if (a == "d" && !_mouseDown)
        {
            SendMouseButton(true);
            _mouseDown = true;
        }
        else if ((a == "u" || a == "x") && _mouseDown)
        {
            SendMouseButton(false);
            _mouseDown = false;
        }
    }

    private static void SendMouseButton(bool down)
    {
        var i = new INPUT { type = 0, mi = new MOUSEINPUT { dwFlags = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP } };
        SendInput(1, new[] { i }, Marshal.SizeOf<INPUT>());
    }
}
