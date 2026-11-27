using System.Net.NetworkInformation;
using System.Net.Sockets;
using QRCoder;

namespace TabDisplay;

/// <summary>Wi-Fi 接続用の QR コードを出す窓</summary>
public sealed class QrForm : Form
{
    public QrForm(AppSettings s)
    {
        Text = L.T("qr_title");
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        StartPosition = FormStartPosition.CenterParent;

        var col = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            AutoSize = true,
            Padding = new Padding(16),
            WrapContents = false,
        };
        Controls.Add(col);

        var ips = LocalAddresses();
        if (ips.Count == 0)
        {
            col.Controls.Add(new Label { Text = L.T("qr_no_ip"), AutoSize = true });
            return;
        }

        // tabdisplay://connect?h=IP1,IP2&p=38500&t=合言葉&f=証明書の指紋&n=PC名
        var url = "tabdisplay://connect?h=" + string.Join(",", ips) +
                  "&p=" + s.Port +
                  "&t=" + s.Token +
                  "&f=" + CertStore.Fingerprint +
                  "&n=" + Uri.EscapeDataString(Environment.MachineName);

        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(url, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(8);
        using var ms = new MemoryStream(png);
        var pic = new PictureBox
        {
            Image = Image.FromStream(ms),
            SizeMode = PictureBoxSizeMode.AutoSize,
        };
        col.Controls.Add(pic);
        col.Controls.Add(new Label { Text = L.T("qr_help"), AutoSize = true, MaximumSize = new Size(420, 0) });
        col.Controls.Add(new Label
        {
            Text = Environment.MachineName + "  " + string.Join(" / ", ips.Select(ip => ip + ":" + s.Port)),
            AutoSize = true,
            ForeColor = Color.DimGray,
        });
    }

    /// <summary>この PC の IPv4 アドレス。ゲートウェイのある（ふつうの）ネットワークを先にする</summary>
    public static List<string> LocalAddresses()
    {
        var list = new List<(string ip, int score)>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            var props = ni.GetIPProperties();
            var hasGateway = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork &&
                                                             !g.Address.Equals(System.Net.IPAddress.Any));
            var name = (ni.Name + " " + ni.Description).ToLowerInvariant();
            var isVirtual = name.Contains("virtual") || name.Contains("vmware") || name.Contains("hyper-v") ||
                            name.Contains("vethernet") || name.Contains("wsl") || name.Contains("vpn");
            foreach (var a in props.UnicastAddresses)
            {
                if (a.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var ip = a.Address.ToString();
                if (ip.StartsWith("169.254.")) continue;
                int score = (hasGateway ? 2 : 0) + (isVirtual ? 0 : 1) +
                            (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 1 : 0);
                list.Add((ip, score));
            }
        }
        return list.OrderByDescending(x => x.score).Select(x => x.ip).Distinct().Take(4).ToList();
    }
}
