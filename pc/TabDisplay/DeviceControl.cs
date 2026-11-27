using System.Runtime.InteropServices;
using System.Text;

namespace TabDisplay;

/// <summary>
/// 仮想ディスプレイドライバー（ハードウェア ID: Root\MttVDD）のデバイスを探す・オン／オフする。
/// 探すのは管理者なしでできる。オン／オフは管理者が要る（Helper で行う）。
/// </summary>
public static class DeviceControl
{
    public const string HardwareId = @"Root\MttVDD";
    public static readonly Guid DisplayClass = new("4d36e968-e325-11ce-bfc1-08002be10318");

    [StructLayout(LayoutKind.Sequential)]
    private struct SP_DEVINFO_DATA
    {
        public int cbSize;
        public Guid ClassGuid;
        public uint DevInst;
        public IntPtr Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SP_PROPCHANGE_PARAMS
    {
        public int cbSize;            // SP_CLASSINSTALL_HEADER.cbSize（= 8）
        public uint InstallFunction;  // SP_CLASSINSTALL_HEADER.InstallFunction
        public uint StateChange;
        public uint Scope;
        public uint HwProfile;
    }

    private const uint DIGCF_PRESENT = 0x2;
    private const uint SPDRP_HARDWAREID = 0x1;
    private const uint DIF_PROPERTYCHANGE = 0x12;
    private const uint DICS_ENABLE = 1;
    private const uint DICS_DISABLE = 2;
    private const uint DICS_PROPCHANGE = 3;
    private const uint DICS_FLAG_GLOBAL = 1;
    private const uint DN_STARTED = 0x8;
    private const uint DN_HAS_PROBLEM = 0x400;
    private const uint CM_PROB_DISABLED = 22;

    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern IntPtr SetupDiGetClassDevs(ref Guid classGuid, IntPtr enumerator, IntPtr hwndParent, uint flags);

    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern bool SetupDiEnumDeviceInfo(IntPtr set, uint index, ref SP_DEVINFO_DATA data);

    [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "SetupDiGetDeviceRegistryPropertyW")]
    private static extern bool SetupDiGetDeviceRegistryProperty(IntPtr set, ref SP_DEVINFO_DATA data, uint property,
        out uint regType, byte[]? buffer, uint size, out uint required);

    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern bool SetupDiSetClassInstallParams(IntPtr set, ref SP_DEVINFO_DATA data, ref SP_PROPCHANGE_PARAMS p, int size);

    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern bool SetupDiCallClassInstaller(uint function, IntPtr set, ref SP_DEVINFO_DATA data);

    [DllImport("setupapi.dll", SetLastError = true)]
    private static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);

    [DllImport("cfgmgr32.dll")]
    private static extern int CM_Get_DevNode_Status(out uint status, out uint problem, uint devInst, uint flags);

    public enum State { NotInstalled, Disabled, Enabled }

    /// <summary>今の状態（管理者なしで OK）</summary>
    public static State GetState()
    {
        State result = State.NotInstalled;
        ForEach((set, data) =>
        {
            if (CM_Get_DevNode_Status(out var st, out var prob, data.DevInst, 0) == 0)
            {
                result = (st & DN_HAS_PROBLEM) != 0 && prob == CM_PROB_DISABLED ? State.Disabled
                       : (st & DN_STARTED) != 0 ? State.Enabled : State.Disabled;
            }
            else
            {
                result = State.Disabled;
            }
            return true;
        });
        return result;
    }

    /// <summary>オン／オフ／再起動（管理者が要る）</summary>
    public static bool Change(string action)
    {
        uint change = action switch
        {
            "off" => DICS_DISABLE,
            "restart" => DICS_PROPCHANGE,
            _ => DICS_ENABLE,
        };
        bool ok = false;
        ForEach((set, data) =>
        {
            if (action == "on" && GetStateOf(data.DevInst) == State.Enabled) change = DICS_PROPCHANGE;
            var p = new SP_PROPCHANGE_PARAMS
            {
                cbSize = 8,
                InstallFunction = DIF_PROPERTYCHANGE,
                StateChange = change,
                Scope = DICS_FLAG_GLOBAL,
                HwProfile = 0,
            };
            ok = SetupDiSetClassInstallParams(set, ref data, ref p, Marshal.SizeOf<SP_PROPCHANGE_PARAMS>())
                 && SetupDiCallClassInstaller(DIF_PROPERTYCHANGE, set, ref data);
            Log.Write($"DeviceControl {action} ({change}) -> {ok} err={Marshal.GetLastWin32Error()}");
            return true;
        });
        return ok;
    }

    private static State GetStateOf(uint devInst)
    {
        if (CM_Get_DevNode_Status(out var st, out var prob, devInst, 0) != 0) return State.Disabled;
        if ((st & DN_HAS_PROBLEM) != 0 && prob == CM_PROB_DISABLED) return State.Disabled;
        return (st & DN_STARTED) != 0 ? State.Enabled : State.Disabled;
    }

    /// <summary>Root\MttVDD のデバイスを探して f を呼ぶ（f が true を返したら終わり）</summary>
    private static void ForEach(Func<IntPtr, SP_DEVINFO_DATA, bool> f)
    {
        var g = DisplayClass;
        var set = SetupDiGetClassDevs(ref g, IntPtr.Zero, IntPtr.Zero, DIGCF_PRESENT);
        if (set == IntPtr.Zero || set == new IntPtr(-1)) return;
        try
        {
            for (uint i = 0; ; i++)
            {
                var data = new SP_DEVINFO_DATA { cbSize = Marshal.SizeOf<SP_DEVINFO_DATA>() };
                if (!SetupDiEnumDeviceInfo(set, i, ref data)) break;
                SetupDiGetDeviceRegistryProperty(set, ref data, SPDRP_HARDWAREID, out _, null, 0, out var req);
                if (req == 0) continue;
                var buf = new byte[req];
                if (!SetupDiGetDeviceRegistryProperty(set, ref data, SPDRP_HARDWAREID, out _, buf, req, out _)) continue;
                var ids = Encoding.Unicode.GetString(buf).Split('\0', StringSplitOptions.RemoveEmptyEntries);
                if (ids.Any(id => id.Equals(HardwareId, StringComparison.OrdinalIgnoreCase) ||
                                  id.Equals("MttVDD", StringComparison.OrdinalIgnoreCase)))
                {
                    if (f(set, data)) return;
                }
            }
        }
        finally
        {
            SetupDiDestroyDeviceInfoList(set);
        }
    }
}
