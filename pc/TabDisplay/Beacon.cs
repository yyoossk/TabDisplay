using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace TabDisplay;

/// <summary>
/// Wi-Fi に 2 秒ごとに「ここにいます」と知らせる（UDP 38501 番のブロードキャスト）。
/// タブレットは前につないだ PC を見つけて、IP が変わっていても自動でつなぐ。
/// 合言葉そのものは出さず、合言葉から作った ID だけを出す。
/// </summary>
public sealed class Beacon : IDisposable
{
    public const int Port = 38501;
    private readonly System.Threading.Timer _timer;
    private readonly byte[] _payload;

    public Beacon(AppSettings s)
    {
        _payload = Encoding.UTF8.GetBytes(new JsonObject
        {
            ["app"] = "tabdisplay",
            ["n"] = Environment.MachineName,
            ["p"] = s.Port,
            ["i"] = PcId(s.Token),
        }.ToJsonString());
        _timer = new System.Threading.Timer(_ => Send(), null, 500, 2000);
    }

    /// <summary>タブレット側の Target.pcIdOf と同じ計算</summary>
    public static string PcId(string token) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)).AsSpan(0, 8)).ToLowerInvariant();

    private void Send()
    {
        try
        {
            foreach (var (local, bcast) in Targets())
            {
                try
                {
                    using var u = new UdpClient(new IPEndPoint(local, 0)) { EnableBroadcast = true };
                    u.Send(_payload, _payload.Length, new IPEndPoint(bcast, Port));
                }
                catch { }
            }
        }
        catch { }
    }

    /// <summary>ネットワークごとの（自分の IP, そのネットワークのブロードキャスト先）</summary>
    private static IEnumerable<(IPAddress, IPAddress)> Targets()
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var a in ni.GetIPProperties().UnicastAddresses)
            {
                if (a.Address.AddressFamily != AddressFamily.InterNetwork || a.IPv4Mask == null) continue;
                var ip = a.Address.GetAddressBytes();
                var mask = a.IPv4Mask.GetAddressBytes();
                var b = new byte[4];
                for (int i = 0; i < 4; i++) b[i] = (byte)(ip[i] | ~mask[i]);
                yield return (a.Address, new IPAddress(b));
            }
        }
    }

    public void Dispose() => _timer.Dispose();
}
