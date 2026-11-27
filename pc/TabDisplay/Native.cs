using System.Runtime.InteropServices;

namespace TabDisplay;

/// <summary>Windows API の宣言</summary>
internal static unsafe class Native
{
    // ---------- 画面 ----------

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DISPLAY_DEVICE
    {
        public int cb;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceString;
        public uint StateFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceID;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceKey;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DEVMODE
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public ushort dmSpecVersion;
        public ushort dmDriverVersion;
        public ushort dmSize;
        public ushort dmDriverExtra;
        public uint dmFields;
        public int dmPositionX;
        public int dmPositionY;
        public uint dmDisplayOrientation;
        public uint dmDisplayFixedOutput;
        public short dmColor;
        public short dmDuplex;
        public short dmYResolution;
        public short dmTTOption;
        public short dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public ushort dmLogPixels;
        public uint dmBitsPerPel;
        public uint dmPelsWidth;
        public uint dmPelsHeight;
        public uint dmDisplayFlags;
        public uint dmDisplayFrequency;
        public uint dmICMMethod;
        public uint dmICMIntent;
        public uint dmMediaType;
        public uint dmDitherType;
        public uint dmReserved1;
        public uint dmReserved2;
        public uint dmPanningWidth;
        public uint dmPanningHeight;

        public static DEVMODE Create()
        {
            var d = new DEVMODE { dmDeviceName = "", dmFormName = "" };
            d.dmSize = (ushort)Marshal.SizeOf<DEVMODE>();
            return d;
        }
    }

    public const uint DISPLAY_DEVICE_ATTACHED_TO_DESKTOP = 0x1;
    public const uint DISPLAY_DEVICE_PRIMARY_DEVICE = 0x4;
    public const uint DISPLAY_DEVICE_MIRRORING_DRIVER = 0x8;

    public const uint DM_POSITION = 0x20;
    public const uint DM_DISPLAYORIENTATION = 0x80;
    public const uint DM_BITSPERPEL = 0x40000;
    public const uint DM_PELSWIDTH = 0x80000;
    public const uint DM_PELSHEIGHT = 0x100000;
    public const uint DM_DISPLAYFREQUENCY = 0x400000;

    public const uint CDS_UPDATEREGISTRY = 0x1;
    public const uint CDS_SET_PRIMARY = 0x10;
    public const uint CDS_NORESET = 0x10000000;

    public const int ENUM_CURRENT_SETTINGS = -1;
    public const int DISP_CHANGE_SUCCESSFUL = 0;

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern bool EnumDisplayDevices(string? lpDevice, uint iDevNum, ref DISPLAY_DEVICE lpDisplayDevice, uint dwFlags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern bool EnumDisplaySettingsEx(string? lpszDeviceName, int iModeNum, ref DEVMODE lpDevMode, uint dwFlags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int ChangeDisplaySettingsEx(string? lpszDeviceName, ref DEVMODE lpDevMode, IntPtr hwnd, uint dwflags, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "ChangeDisplaySettingsEx")]
    public static extern int ChangeDisplaySettingsExApply(string? lpszDeviceName, IntPtr lpDevMode, IntPtr hwnd, uint dwflags, IntPtr lParam);

    public const uint SDC_TOPOLOGY_EXTEND = 0x4;
    public const uint SDC_APPLY = 0x80;

    [DllImport("user32.dll")]
    public static extern int SetDisplayConfig(uint numPathArrayElements, IntPtr pathArray, uint numModeInfoArrayElements, IntPtr modeInfoArray, uint flags);

    // ---------- 入力 ----------

    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public int mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT
    {
        public uint type;          // 0 = マウス
        public MOUSEINPUT mi;      // マウスが union の中でいちばん大きいので、これだけで大きさが合う
    }

    public const uint MOUSEEVENTF_MOVE = 0x0001;
    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    public const uint MOUSEEVENTF_WHEEL = 0x0800;
    public const uint MOUSEEVENTF_HWHEEL = 0x1000;
    public const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;
    public const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

    [DllImport("user32.dll", SetLastError = true)]
    public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    public const int SM_XVIRTUALSCREEN = 76;
    public const int SM_YVIRTUALSCREEN = 77;
    public const int SM_CXVIRTUALSCREEN = 78;
    public const int SM_CYVIRTUALSCREEN = 79;

    [DllImport("user32.dll")]
    public static extern int GetSystemMetrics(int nIndex);


    // ---------- キーボード ----------

    /// <summary>キーボード用の INPUT（x64 で 40 バイト。union はオフセット 8 から）</summary>
    [StructLayout(LayoutKind.Explicit, Size = 40)]
    public struct KEYINPUT
    {
        [FieldOffset(0)] public uint type;      // 1 = キーボード
        [FieldOffset(8)] public ushort wVk;
        [FieldOffset(10)] public ushort wScan;
        [FieldOffset(12)] public uint dwFlags;
        [FieldOffset(16)] public uint time;
        [FieldOffset(24)] public IntPtr dwExtraInfo;
    }

    public const uint KEYEVENTF_EXTENDEDKEY = 0x1;
    public const uint KEYEVENTF_KEYUP = 0x2;
    public const uint KEYEVENTF_UNICODE = 0x4;

    [DllImport("user32.dll", SetLastError = true, EntryPoint = "SendInput")]
    public static extern uint SendKeyInput(uint nInputs, KEYINPUT[] pInputs, int cbSize);

    // ---------- ペン（Windows 10 1809 以降） ----------

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int x, y; }

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int left, top, right, bottom; }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINTER_INFO
    {
        public int pointerType;
        public uint pointerId;
        public uint frameId;
        public uint pointerFlags;
        public IntPtr sourceDevice;
        public IntPtr hwndTarget;
        public POINT ptPixelLocation;
        public POINT ptHimetricLocation;
        public POINT ptPixelLocationRaw;
        public POINT ptHimetricLocationRaw;
        public uint dwTime;
        public uint historyCount;
        public int InputData;
        public uint dwKeyStates;
        public ulong PerformanceCount;
        public int ButtonChangeType;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINTER_PEN_INFO
    {
        public POINTER_INFO pointerInfo;
        public uint penFlags;
        public uint penMask;
        public uint pressure;
        public uint rotation;
        public int tiltX;
        public int tiltY;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINTER_TOUCH_INFO
    {
        public POINTER_INFO pointerInfo;
        public uint touchFlags;
        public uint touchMask;
        public RECT rcContact;
        public RECT rcContactRaw;
        public uint orientation;
        public uint pressure;
    }

    public const int PT_PEN = 3;
    public const int POINTER_FEEDBACK_DEFAULT = 1;
    public const uint POINTER_FLAG_INRANGE = 0x2;
    public const uint POINTER_FLAG_INCONTACT = 0x4;
    public const uint POINTER_FLAG_FIRSTBUTTON = 0x10;
    public const uint POINTER_FLAG_SECONDBUTTON = 0x20;
    public const uint POINTER_FLAG_DOWN = 0x10000;
    public const uint POINTER_FLAG_UPDATE = 0x20000;
    public const uint POINTER_FLAG_UP = 0x40000;
    public const uint PEN_FLAG_BARREL = 0x1;
    public const uint PEN_FLAG_ERASER = 0x4;
    public const uint PEN_MASK_PRESSURE = 0x1;
    public const uint PEN_MASK_TILT_X = 0x4;
    public const uint PEN_MASK_TILT_Y = 0x8;

    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr CreateSyntheticPointerDevice(int pointerType, uint maxCount, int mode);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool InjectSyntheticPointerInput(IntPtr device, IntPtr pointerInfo, uint count);

    // ---------- 画面の拡大率（DisplayConfig） ----------

    public const uint QDC_ONLY_ACTIVE_PATHS = 0x2;

    [DllImport("user32.dll")]
    public static extern int GetDisplayConfigBufferSizes(uint flags, out uint numPathArrayElements, out uint numModeInfoArrayElements);

    [DllImport("user32.dll")]
    public static extern int QueryDisplayConfig(uint flags, ref uint numPathArrayElements, byte[] pathArray,
        ref uint numModeInfoArrayElements, byte[] modeInfoArray, IntPtr currentTopologyId);

    [DllImport("user32.dll")]
    public static extern int DisplayConfigGetDeviceInfo(IntPtr requestPacket);

    [DllImport("user32.dll")]
    public static extern int DisplayConfigSetDeviceInfo(IntPtr setPacket);

    // ---------- DXGI（ffmpeg に渡すグラボ番号・出力番号を調べる） ----------

    [StructLayout(LayoutKind.Sequential)]
    public struct DXGI_OUTPUT_DESC
    {
        public fixed char DeviceName[32];
        public int Left, Top, Right, Bottom;
        public int AttachedToDesktop;
        public int Rotation;
        public IntPtr Monitor;
    }

    [DllImport("dxgi.dll")]
    private static extern int CreateDXGIFactory1(ref Guid riid, out IntPtr ppFactory);

    private static readonly Guid IID_IDXGIFactory1 = new("770aae78-f26f-4dba-a829-253c83d1b387");

    private static void Release(IntPtr p)
    {
        if (p == IntPtr.Zero) return;
        var vt = *(void***)p;
        ((delegate* unmanaged[Stdcall]<IntPtr, uint>)vt[2])(p);
    }

    /// <summary>
    /// 画面の名前（\\.\DISPLAY3 など）から、DXGI のグラボ番号と出力番号を探す。
    /// ffmpeg の ddagrab は「グラボ番号（-init_hw_device d3d11va=名前:番号）」と「出力番号（output_idx）」で画面を選ぶ。
    /// </summary>
    public static (int adapter, int output)? FindDxgiOutput(string deviceName)
    {
        var iid = IID_IDXGIFactory1;
        if (CreateDXGIFactory1(ref iid, out var factory) < 0 || factory == IntPtr.Zero) return null;
        try
        {
            var fvt = *(void***)factory;
            var enumAdapters = (delegate* unmanaged[Stdcall]<IntPtr, uint, IntPtr*, int>)fvt[7];
            for (uint a = 0; a < 16; a++)
            {
                IntPtr adapter = IntPtr.Zero;
                if (enumAdapters(factory, a, &adapter) < 0) break;
                try
                {
                    var avt = *(void***)adapter;
                    var enumOutputs = (delegate* unmanaged[Stdcall]<IntPtr, uint, IntPtr*, int>)avt[7];
                    for (uint o = 0; o < 16; o++)
                    {
                        IntPtr output = IntPtr.Zero;
                        if (enumOutputs(adapter, o, &output) < 0) break;
                        try
                        {
                            var ovt = *(void***)output;
                            var getDesc = (delegate* unmanaged[Stdcall]<IntPtr, DXGI_OUTPUT_DESC*, int>)ovt[7];
                            DXGI_OUTPUT_DESC desc = default;
                            if (getDesc(output, &desc) >= 0)
                            {
                                var name = new string(desc.DeviceName);
                                var z = name.IndexOf('\0');
                                if (z >= 0) name = name[..z];
                                if (string.Equals(name, deviceName, StringComparison.OrdinalIgnoreCase))
                                    return ((int)a, (int)o);
                            }
                        }
                        finally { Release(output); }
                    }
                }
                finally { Release(adapter); }
            }
        }
        finally { Release(factory); }
        return null;
    }

    // ---------- その他 ----------

    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
}
