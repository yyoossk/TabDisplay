using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace TabDisplay;

/// <summary>
/// 通信の暗号化（TLS）に使う、この PC だけの証明書。初回に作って保存する。
/// タブレットは QR（または USB の起動）で受け取った「指紋（SHA-256）」と一致するかで本物か確かめる。
/// </summary>
public static class CertStore
{
    private static string PfxPath => Path.Combine(AppSettings.Dir, "tls.pfx");
    private static X509Certificate2? _cert;

    public static X509Certificate2 Cert => _cert ??= LoadOrCreate();

    /// <summary>タブレットに渡す指紋（小文字 16 進）</summary>
    public static string Fingerprint => Convert.ToHexString(SHA256.HashData(Cert.RawData)).ToLowerInvariant();

    private static X509Certificate2 LoadOrCreate()
    {
        try
        {
            if (File.Exists(PfxPath))
                return new X509Certificate2(PfxPath, (string?)null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.PersistKeySet);
        }
        catch (Exception e) { Log.Write("tls load: " + e.Message); }

        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest("CN=TabDisplay " + Environment.MachineName, rsa,
            HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        using var tmp = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(30));
        var pfx = tmp.Export(X509ContentType.Pfx);
        Directory.CreateDirectory(AppSettings.Dir);
        File.WriteAllBytes(PfxPath, pfx);
        // Windows の TLS（SChannel）は一時的な鍵を使えないので、保存した形から読み直す
        return new X509Certificate2(pfx, (string?)null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.PersistKeySet);
    }
}
