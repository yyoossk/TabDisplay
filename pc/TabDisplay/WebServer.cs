using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;

namespace TabDisplay;

/// <summary>
/// ブラウザーで受ける：https://PC の IP:38502/?t=合言葉 を開くと、アプリなしで PC の画面が見られる。
///  ・ページ（HTML・JavaScript）を返し、WebSocket の上でアプリと同じやりとりをする
///  ・映像はブラウザーの WebCodecs で H.264 を再生（Chrome・Edge など）
///  ・証明書は PC が自分で作ったものなので、初回はブラウザーの警告で「詳細 → このまま進む」を選ぶ
/// </summary>
public static class WebServer
{
    public const int Port = 38502;

    /// <summary>TLS のあと、HTTP の頼みを読む。WebSocket なら包んだ Stream を返す（ページを返したときは null）</summary>
    public static Stream? Accept(TcpClient c)
    {
        var ssl = new SslStream(c.GetStream(), false);
        ssl.AuthenticateAsServer(CertStore.Cert, false, SslProtocols.Tls12 | SslProtocols.Tls13, false);
        var head = ReadHeaders(ssl);
        var lines = head.Split("\r\n");
        var path = lines[0].Split(' ').ElementAtOrDefault(1) ?? "/";
        var headers = lines.Skip(1).Where(l => l.Contains(':'))
            .ToDictionary(l => l[..l.IndexOf(':')].Trim().ToLowerInvariant(), l => l[(l.IndexOf(':') + 1)..].Trim());

        if (headers.TryGetValue("upgrade", out var up) && up.Equals("websocket", StringComparison.OrdinalIgnoreCase)
            && headers.TryGetValue("sec-websocket-key", out var key))
        {
            var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
            var resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                       $"Sec-WebSocket-Accept: {accept}\r\n\r\n";
            ssl.Write(Encoding.ASCII.GetBytes(resp));
            return new WsStream(ssl);
        }

        var body = path.StartsWith("/favicon") ? Array.Empty<byte>() : Encoding.UTF8.GetBytes(Page);
        var status = body.Length == 0 ? "404 Not Found" : "200 OK";
        var h = $"HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {body.Length}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        ssl.Write(Encoding.ASCII.GetBytes(h));
        ssl.Write(body);
        ssl.Flush();
        ssl.Dispose();
        return null;
    }

    private static string ReadHeaders(Stream s)
    {
        var sb = new StringBuilder();
        var one = new byte[1];
        while (sb.Length < 16384)
        {
            if (s.Read(one, 0, 1) <= 0) break;
            sb.Append((char)one[0]);
            if (sb.Length >= 4 && sb[^1] == '\n' && sb[^2] == '\r' && sb[^3] == '\n' && sb[^4] == '\r') break;
        }
        return sb.ToString();
    }

    /// <summary>ブラウザー用のページ（文字は日本語・英語をブラウザーの言語で切り替え）</summary>
    private const string Page = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>TabDisplay</title>
<style>
html,body{margin:0;height:100%;background:#000;color:#fff;font-family:sans-serif;overflow:hidden;touch-action:none}
#c{position:absolute;inset:0;margin:auto;max-width:100%;max-height:100%}
#m{position:absolute;left:0;right:0;top:40%;text-align:center;padding:16px}
#fs{position:absolute;right:10px;bottom:10px;opacity:.6;background:#0008;color:#fff;border:0;font-size:20px;padding:8px 12px}
</style></head><body>
<canvas id="c"></canvas><div id="m"></div><button id="fs">⛶</button>
<script>
const ja = navigator.language.startsWith('ja');
const T = ja ? {conn:'接続しています…', nosup:'このブラウザーは対応していません。Chrome か Edge で開いてください。', token:'合言葉が違います。PC の QR コードをもう一度読み取ってください。', lost:'切れました。つなぎ直しています…', pconly:'PC が「PC のみ」になっています。', wait:'映像を待っています…'}
             : {conn:'Connecting…', nosup:'This browser is not supported. Open in Chrome or Edge.', token:'Wrong key. Scan the QR code on the PC again.', lost:'Disconnected. Reconnecting…', pconly:'The PC is set to "PC only".', wait:'Waiting for the picture…'};
const c = document.getElementById('c'), g = c.getContext('2d'), m = document.getElementById('m');
const msg = t => { m.textContent = t; m.style.display = t ? 'block' : 'none'; };
document.getElementById('fs').onclick = () => document.documentElement.requestFullscreen && document.documentElement.requestFullscreen();
if (!('VideoDecoder' in window)) { msg(T.nosup); throw 0; }
const token = new URLSearchParams(location.search).get('t') || '';
let id = localStorage.getItem('tdid'); if (!id) { id = crypto.randomUUID ? crypto.randomUUID() : String(Math.random()); localStorage.setItem('tdid', id); }
let ws, dec, waitKey = true;
const enc = new TextEncoder(), decT = new TextDecoder();
function pack(type, obj) {
  const p = obj instanceof Uint8Array ? obj : enc.encode(JSON.stringify(obj));
  const b = new Uint8Array(5 + p.length); b[0] = type; new DataView(b.buffer).setInt32(1, p.length); b.set(p, 5); return b;
}
const send = (t, o) => { if (ws && ws.readyState === 1) ws.send(pack(t, o)); };
function size() {
  const r = devicePixelRatio || 1;
  return [Math.round(screen.width * r) & ~1, Math.round(screen.height * r) & ~1, Math.round(160 * r)];
}
function hex(n) { return n.toString(16).padStart(2, '0'); }
function connect() {
  msg(T.conn);
  ws = new WebSocket('wss://' + location.host + '/ws'); ws.binaryType = 'arraybuffer';
  ws.onopen = () => { const [w, h, dpi] = size(); send(1, {token, id, name: 'Browser', w, h, dpi, ver: 1, hevc: false}); };
  ws.onclose = () => { msg(T.lost); setTimeout(connect, 1500); };
  ws.onmessage = e => {
    const b = new Uint8Array(e.data), t = b[0], p = b.subarray(5);
    if (t === 10) {                      // 設定
      const j = JSON.parse(decT.decode(p));
      const sps = Uint8Array.from(atob(j.sps), x => x.charCodeAt(0));
      c.width = j.w; c.height = j.h;
      if (dec) try { dec.close(); } catch (_) {}
      dec = new VideoDecoder({ output: f => { g.drawImage(f, 0, 0, c.width, c.height); f.close(); }, error: () => { waitKey = true; send(5, new Uint8Array(0)); } });
      dec.configure({ codec: 'avc1.' + hex(sps[1]) + hex(sps[2]) + hex(sps[3]), optimizeForLatency: true });
      waitKey = true;
    } else if (t === 11 && dec && dec.state === 'configured') {   // 映像
      const key = (p[8] & 1) === 1;
      if (waitKey && !key) return;
      waitKey = false;
      const ts = Number(new DataView(p.buffer, p.byteOffset, 8).getBigInt64(0));
      dec.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: ts, data: p.subarray(9) }));
      msg('');
    } else if (t === 13) {
      const s = JSON.parse(decT.decode(p)).s;
      msg(s === 'pc_only' ? T.pconly : s === 'ok' ? T.wait : s);
    } else if (t === 14) { msg(T.token); ws.onclose = null; ws.close(); }
  };
}
setInterval(() => send(4, {tab: Math.round(performance.now() * 1000)}), 1000);
// タッチ：タップ → クリック / 長押し → 右クリック / なぞる → スクロール
let down = null, longT = null, moved = false, acc = 0;
const norm = e => { const r = c.getBoundingClientRect(); return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height]; };
c.addEventListener('pointerdown', e => {
  down = {x: e.clientX, y: e.clientY, ly: e.clientY, n: norm(e)}; moved = false; acc = 0;
  longT = setTimeout(() => { send(2, {k: 'rclick', x: down.n[0], y: down.n[1]}); down.long = true; }, 500);
});
c.addEventListener('pointermove', e => {
  if (!down) return;
  if (!moved && Math.hypot(e.clientX - down.x, e.clientY - down.y) > 10) { moved = true; clearTimeout(longT); send(2, {k: 'move', x: down.n[0], y: down.n[1]}); }
  if (moved) { acc += (e.clientY - down.ly) / 40 * 120; down.ly = e.clientY; const wy = Math.trunc(acc); if (wy) { acc -= wy; send(2, {k: 'scroll', wx: 0, wy}); } }
});
c.addEventListener('pointerup', e => {
  clearTimeout(longT);
  if (down && !moved && !down.long) { const [x, y] = norm(e); send(2, {k: 'click', x, y}); }
  down = null;
});
addEventListener('resize', () => { const [w, h] = size(); send(3, {w, h}); });
connect();
</script></body></html>
""";
}

/// <summary>WebSocket の上で、アプリと同じやりとり（1 メッセージ ＝ WebSocket の 1 フレーム）をするための Stream</summary>
internal sealed class WsStream : Stream
{
    private readonly Stream _s;
    private byte[] _cur = Array.Empty<byte>();
    private int _pos;
    private readonly object _wlock = new();

    public WsStream(Stream s) => _s = s;

    public override int Read(byte[] buffer, int offset, int count)
    {
        while (_pos >= _cur.Length)
        {
            var msg = ReadMessage();
            if (msg == null) return 0;
            _cur = msg;
            _pos = 0;
        }
        int n = Math.Min(count, _cur.Length - _pos);
        Array.Copy(_cur, _pos, buffer, offset, n);
        _pos += n;
        return n;
    }

    /// <summary>データのメッセージを 1 つ読む（ping には pong を返す。close なら null）</summary>
    private byte[]? ReadMessage()
    {
        var all = new MemoryStream();
        while (true)
        {
            var h = new byte[2];
            _s.ReadExactly(h);
            bool fin = (h[0] & 0x80) != 0;
            int op = h[0] & 0x0f;
            bool masked = (h[1] & 0x80) != 0;
            long len = h[1] & 0x7f;
            if (len == 126) { var e = new byte[2]; _s.ReadExactly(e); len = (e[0] << 8) | e[1]; }
            else if (len == 127) { var e = new byte[8]; _s.ReadExactly(e); len = (long)System.Buffers.Binary.BinaryPrimitives.ReadUInt64BigEndian(e); }
            if (len > 4 * 1024 * 1024) throw new IOException("ws frame too large");
            var mask = new byte[4];
            if (masked) _s.ReadExactly(mask);
            var data = new byte[len];
            _s.ReadExactly(data);
            if (masked) for (int i = 0; i < data.Length; i++) data[i] ^= mask[i & 3];
            if (op == 8) return null;
            if (op == 9) { WriteFrame(0xA, data); continue; }
            if (op == 0xA) continue;
            all.Write(data);
            if (fin) return all.ToArray();
        }
    }

    public override void Write(byte[] buffer, int offset, int count) =>
        WriteFrame(0x2, buffer.AsSpan(offset, count).ToArray());

    private void WriteFrame(int op, byte[] data)
    {
        lock (_wlock)
        {
            var head = new MemoryStream(10);
            head.WriteByte((byte)(0x80 | op));
            if (data.Length < 126) head.WriteByte((byte)data.Length);
            else if (data.Length <= 0xffff) { head.WriteByte(126); head.WriteByte((byte)(data.Length >> 8)); head.WriteByte((byte)data.Length); }
            else
            {
                head.WriteByte(127);
                var e = new byte[8];
                System.Buffers.Binary.BinaryPrimitives.WriteUInt64BigEndian(e, (ulong)data.Length);
                head.Write(e);
            }
            _s.Write(head.ToArray());
            _s.Write(data);
            _s.Flush();
        }
    }

    public override void Flush() => _s.Flush();
    protected override void Dispose(bool disposing) { if (disposing) _s.Dispose(); base.Dispose(disposing); }
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}
