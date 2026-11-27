using System.Net.Security;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace TabDisplay;

/// <summary>
/// つなぐ部分の自動テスト（TabDisplay.exe --selftest）。GitHub でビルドするたびに動かす。
/// 画面やドライバーは使わない（「PC のみ」で動かす）。結果はコンソールと終了コードで返す。
/// </summary>
internal static class SelfTest
{
    private static int _fails;

    public static int Run()
    {
        var s = new AppSettings { Mode = ShowMode.PcOnly, Port = 38590, ShareClipboard = false, Token = AppSettings.NewToken() };
        L.Init(s.Language);
        Directory.CreateDirectory(AppSettings.Dir);
        try { File.Delete(ResultFile); } catch { }
        var server = new Server(s);
        Check("server start", server.Start());
        var fp = CertStore.Fingerprint;

        // 合言葉が違うと断られる
        Exchange(s, fp, Hello("wrong", ""), c =>
        {
            var (t, d) = Protocol.Read(c);
            Check("wrong token → error", t == Protocol.P_ERROR && (string?)Protocol.Json(d)["e"] == "token");
        });

        // 正しい合言葉なら状態が届き、PING に PONG が返る
        Exchange(s, fp, Hello(s.Token, ""), c =>
        {
            var gotStatus = false;
            var gotPong = false;
            var gotCheck = false;
            c.Write(Protocol.Pack(Protocol.T_PING, new JsonObject { ["tab"] = 12345 }));
            c.Write(Protocol.Pack(Protocol.T_CHECK, new JsonObject { ["step"] = "link" }));
            var until = DateTime.UtcNow.AddSeconds(10);
            while (DateTime.UtcNow < until && !(gotStatus && gotPong && gotCheck))
            {
                var (t, d) = Protocol.Read(c);
                if (t == Protocol.P_STATUS) gotStatus = true;
                if (t == Protocol.P_PONG && (long?)Protocol.Json(d)["tab"] == 12345) gotPong = true;
                if (t == Protocol.P_CHECK && (string?)Protocol.Json(d)["step"] == "link") gotCheck = true;
            }
            Check("status received", gotStatus);
            Check("ping → pong", gotPong);
            Check("check → answer", gotCheck);
            c.Write(Protocol.Pack(Protocol.T_CRASH, new JsonObject { ["text"] = "selftest", ["build"] = 0 }));
            c.Write(Protocol.Pack(Protocol.T_BYE, Array.Empty<byte>()));
        });

        // PIN を設定すると、PIN なしでは断られる
        s.Pin = "4321";
        Exchange(s, fp, Hello(s.Token, ""), c =>
        {
            var (t, d) = Protocol.Read(c);
            Check("missing pin → error", t == Protocol.P_ERROR && (string?)Protocol.Json(d)["e"] == "pin");
        });
        Exchange(s, fp, Hello(s.Token, "4321"), c =>
        {
            var (t, _) = Protocol.Read(c);
            Check("correct pin → accepted", t != Protocol.P_ERROR);
        });

        // ブラウザー用のページ
        try
        {
            using var tcp = new TcpClient("127.0.0.1", WebServer.Port);
            using var ssl = new SslStream(tcp.GetStream(), false, (_, _, _, _) => true);
            ssl.AuthenticateAsClient("tabdisplay");
            ssl.Write(Encoding.ASCII.GetBytes("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"));
            var buf = new byte[64];
            var n = ssl.Read(buf);
            Check("web page 200", Encoding.ASCII.GetString(buf, 0, n).Contains(" 200"));
        }
        catch (Exception e) { Check("web page: " + e.Message, false); }

        // 形式のテスト：タブレット ID の計算（Android 側のテストと同じ値になること）
        Check("pcId", Beacon.PcId("abc") == "ba7816bf8f01cfea");

        var last = _fails == 0 ? "SELFTEST OK" : $"SELFTEST FAILED ({_fails})";
        Console.WriteLine(last);
        try { File.AppendAllText(ResultFile, last + Environment.NewLine); } catch { }
        return _fails == 0 ? 0 : 1;
    }

    private static byte[] Hello(string token, string pin) => Protocol.Pack(Protocol.T_HELLO, new JsonObject
    {
        ["token"] = token, ["id"] = "selftest", ["name"] = "selftest", ["w"] = 1280, ["h"] = 800, ["dpi"] = 160,
        ["sdk"] = 30, ["ver"] = Protocol.Version, ["app"] = 0, ["inputOnly"] = false, ["pin"] = pin,
    });

    private static void Exchange(AppSettings s, string fp, byte[] hello, Action<Stream> body)
    {
        try
        {
            using var tcp = new TcpClient("127.0.0.1", s.Port) { ReceiveTimeout = 10000 };
            using var ssl = new SslStream(tcp.GetStream(), false, (_, cert, _, _) =>
                cert != null && Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(cert.GetRawCertData())).ToLowerInvariant() == fp);
            ssl.AuthenticateAsClient("tabdisplay");
            ssl.Write(hello);
            body(ssl);
        }
        catch (Exception e) { Check("exchange: " + e.Message, false); }
        Thread.Sleep(300);
    }

    /// <summary>WinExe なのでコンソールに出ないことがある。結果はファイルにも書く</summary>
    public static string ResultFile => Path.Combine(AppSettings.Dir, "selftest.txt");

    private static void Check(string name, bool ok)
    {
        Console.WriteLine((ok ? "PASS " : "FAIL ") + name);
        try { File.AppendAllText(ResultFile, (ok ? "PASS " : "FAIL ") + name + Environment.NewLine); } catch { }
        if (!ok) _fails++;
    }
}
